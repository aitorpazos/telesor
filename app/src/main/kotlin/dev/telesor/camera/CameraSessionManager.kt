package dev.telesor.camera

import android.content.Context
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.lifecycle.LifecycleOwner
import dev.telesor.data.DeviceRole
import dev.telesor.data.TelesorPacket
import dev.telesor.net.TelesorChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "CameraSession"

/**
 * State of the camera streaming session.
 */
enum class CameraSessionState {
    IDLE,
    STARTING,
    STREAMING,
    STOPPING,
    ERROR,
}

/**
 * Orchestrates the full camera streaming pipeline for both roles.
 *
 * **Provider side:**
 *   CameraCapture → H264Encoder → CameraStreamSender → TelesorChannel
 *
 * **Consumer side:**
 *   TelesorChannel → H264Decoder → Surface (preview + VirtualCamera)
 */
class CameraSessionManager(
    private val context: Context,
    private val role: DeviceRole,
    private val channel: TelesorChannel,
) {
    private var scope: CoroutineScope? = null

    // Provider components
    private var cameraCapture: CameraCapture? = null
    private var encoder: H264Encoder? = null
    private var sender: CameraStreamSender? = null

    // Consumer components
    private var decoder: H264Decoder? = null
    private var virtualCameraManager: VirtualCameraManager? = null

    private val _state = MutableStateFlow(CameraSessionState.IDLE)
    val state: StateFlow<CameraSessionState> = _state

    private val _streamInfo = MutableStateFlow<StreamInfo?>(null)
    val streamInfo: StateFlow<StreamInfo?> = _streamInfo

    data class StreamInfo(
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val codecMime: String = "video/avc",
    )

    // ─── Provider Methods ──────────────────────────────────────────────

    /**
     * Start camera capture and encoding on the provider side.
     * Called when a StartCamera packet is received from the consumer.
     */
    fun startProvider(
        lifecycleOwner: LifecycleOwner,
        request: TelesorPacket.StartCamera,
    ) {
        if (role != DeviceRole.PROVIDER) return
        if (_state.value == CameraSessionState.STREAMING) return

        _state.value = CameraSessionState.STARTING
        val sessionScope = CoroutineScope(Dispatchers.Default + Job())
        scope = sessionScope

        sessionScope.launch {
            try {
                // 1. Start camera capture
                val capture = CameraCapture(context)
                cameraCapture = capture

                val useFront = request.facing == TelesorPacket.CameraFacing.FRONT
                val actualSize: Size = capture.start(
                    lifecycleOwner = lifecycleOwner,
                    width = request.width,
                    height = request.height,
                    fps = request.fps,
                    useFrontCamera = useFront,
                )

                // 2. Start H.264 encoder
                val enc = H264Encoder(
                    width = actualSize.width,
                    height = actualSize.height,
                    fps = request.fps,
                    bitrate = request.bitrate,
                )
                encoder = enc
                enc.start()

                // 3. Create stream sender
                val snd = CameraStreamSender(channel, enc)
                sender = snd

                // 4. Send CameraStarted acknowledgment
                val info = StreamInfo(
                    width = actualSize.width,
                    height = actualSize.height,
                    fps = request.fps,
                    bitrate = request.bitrate,
                )
                _streamInfo.value = info

                channel.send(
                    TelesorPacket.CameraStarted(
                        width = info.width,
                        height = info.height,
                        fps = info.fps,
                        bitrate = info.bitrate,
                    )
                )

                _state.value = CameraSessionState.STREAMING

                // 5. Launch encode + send loops in parallel
                launch { enc.encodeLoop(capture.frameChannel) }
                launch { snd.sendLoop() }

                Log.i(TAG, "Provider streaming: ${info.width}x${info.height}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start provider", e)
                _state.value = CameraSessionState.ERROR
                stopProvider()
            }
        }
    }

    fun stopProvider() {
        if (role != DeviceRole.PROVIDER) return
        _state.value = CameraSessionState.STOPPING

        encoder?.stop()
        cameraCapture?.stop()

        scope?.cancel()
        scope = null
        cameraCapture = null
        encoder = null
        sender = null

        _state.value = CameraSessionState.IDLE
        _streamInfo.value = null

        CoroutineScope(Dispatchers.IO).launch {
            try { channel.send(TelesorPacket.CameraStopped) } catch (_: Exception) {}
        }

        Log.i(TAG, "Provider stopped")
    }

    // ─── Consumer Methods ──────────────────────────────────────────────

    /**
     * Start receiving and decoding camera stream on the consumer side.
     * Called after receiving CameraStarted from the provider.
     *
     * @param previewSurface  Surface for in-app preview display
     * @param useVirtualCamera  If true, also create a system-level VirtualCamera
     */
    fun startConsumer(
        cameraStarted: TelesorPacket.CameraStarted,
        previewSurface: Surface?,
        useVirtualCamera: Boolean = true,
    ) {
        if (role != DeviceRole.CONSUMER) return
        if (_state.value == CameraSessionState.STREAMING) return

        _state.value = CameraSessionState.STARTING
        val sessionScope = CoroutineScope(Dispatchers.Default + Job())
        scope = sessionScope

        val info = StreamInfo(
            width = cameraStarted.width,
            height = cameraStarted.height,
            fps = cameraStarted.fps,
            bitrate = cameraStarted.bitrate,
            codecMime = cameraStarted.codecMime,
        )
        _streamInfo.value = info

        sessionScope.launch {
            try {
                // Determine which surface to decode to
                var decodeSurface = previewSurface

                // Try to create VirtualCamera if requested and available
                if (useVirtualCamera && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    val vcm = VirtualCameraManager(context)
                    virtualCameraManager = vcm

                    val created = vcm.create(
                        width = info.width,
                        height = info.height,
                        fps = info.fps,
                    )

                    if (created && vcm.cameraSurface != null) {
                        decodeSurface = vcm.cameraSurface
                        Log.i(TAG, "Using VirtualCamera surface")
                    } else {
                        Log.w(TAG, "VirtualCamera not available, using preview surface only")
                        virtualCameraManager?.destroy()
                        virtualCameraManager = null
                    }
                }

                if (decodeSurface == null) {
                    Log.e(TAG, "No output surface available")
                    _state.value = CameraSessionState.ERROR
                    return@launch
                }

                // Start decoder
                val dec = H264Decoder(info.width, info.height)
                decoder = dec
                dec.prepare(decodeSurface)

                _state.value = CameraSessionState.STREAMING

                // Run decode loop
                dec.decodeLoop(channel)

                Log.i(TAG, "Consumer streaming: ${info.width}x${info.height}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start consumer", e)
                _state.value = CameraSessionState.ERROR
                stopConsumer()
            }
        }
    }

    fun stopConsumer() {
        if (role != DeviceRole.CONSUMER) return
        _state.value = CameraSessionState.STOPPING

        decoder?.stop()
        virtualCameraManager?.destroy()

        scope?.cancel()
        scope = null
        decoder = null
        virtualCameraManager = null

        _state.value = CameraSessionState.IDLE
        _streamInfo.value = null
        Log.i(TAG, "Consumer stopped")
    }

    // ─── Common ────────────────────────────────────────────────────────

    fun stop() {
        when (role) {
            DeviceRole.PROVIDER -> stopProvider()
            DeviceRole.CONSUMER -> stopConsumer()
        }
    }

    /**
     * Handle incoming protocol packets related to camera.
     * Call this from the main packet dispatch loop.
     */
    fun handlePacket(
        packet: TelesorPacket,
        lifecycleOwner: LifecycleOwner? = null,
        previewSurface: Surface? = null,
    ) {
        when (packet) {
            is TelesorPacket.StartCamera -> {
                if (role == DeviceRole.PROVIDER && lifecycleOwner != null) {
                    startProvider(lifecycleOwner, packet)
                }
            }
            is TelesorPacket.CameraStarted -> {
                if (role == DeviceRole.CONSUMER) {
                    startConsumer(packet, previewSurface)
                }
            }
            is TelesorPacket.StopCamera -> {
                stopProvider()
            }
            is TelesorPacket.CameraStopped -> {
                stopConsumer()
            }
            is TelesorPacket.RequestKeyFrame -> {
                CoroutineScope(Dispatchers.IO).launch {
                    sender?.sendConfig()
                }
            }
            else -> { /* not a camera packet */ }
        }
    }
}
