package dev.remoty.camera

import android.annotation.SuppressLint
import android.companion.virtual.VirtualDeviceManager
import android.companion.virtual.VirtualDeviceParams
import android.companion.virtual.camera.VirtualCamera
import android.companion.virtual.camera.VirtualCameraCallback
import android.companion.virtual.camera.VirtualCameraConfig
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import dev.remoty.shizuku.ShizukuHelper
import java.util.concurrent.Executors

private const val TAG = "VirtualCameraManager"

/**
 * Manages creation of a system-level VirtualCamera via VirtualDeviceManager.
 *
 * This requires Android 15 (API 35) and Shizuku for the CREATE_VIRTUAL_DEVICE
 * permission (which is @SystemApi / signature|privileged).
 *
 * When active, the virtual camera appears in CameraManager.getCameraIdList()
 * for ALL apps on the device — video calls, QR scanners, etc. all see it.
 *
 * Architecture:
 *   Remote camera frames → H264Decoder → Surface ← VirtualCamera reads from
 *
 * The VirtualCamera provides a Surface via onStreamConfigured callback.
 * We connect the H264Decoder's output to that Surface.
 */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM) // API 35
class VirtualCameraManager(private val context: Context) {

    private var virtualDevice: android.companion.virtual.VirtualDevice? = null
    private var virtualCamera: VirtualCamera? = null
    private val callbackExecutor = Executors.newSingleThreadExecutor()

    /** Surface provided by VirtualCamera for us to write decoded frames to. */
    @Volatile
    var cameraSurface: Surface? = null
        private set

    /** Listener called when the virtual camera surface is ready. */
    var onSurfaceReady: ((Surface) -> Unit)? = null

    /**
     * Create a VirtualDevice and register a VirtualCamera.
     *
     * This call goes through Shizuku to access the @SystemApi.
     *
     * @param width   Camera resolution width
     * @param height  Camera resolution height
     * @param fps     Max frame rate
     * @param facing  LENS_FACING_FRONT or LENS_FACING_BACK
     * @return true if creation succeeded
     */
    @SuppressLint("WrongConstant")
    fun create(
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 30,
        facing: Int = CameraCharacteristics.LENS_FACING_FRONT,
    ): Boolean {
        if (!ShizukuHelper.isAvailable()) {
            Log.e(TAG, "Shizuku not available — cannot create virtual camera")
            return false
        }

        try {
            val vdm = context.getSystemService(Context.VIRTUAL_DEVICE_MANAGER_SERVICE)
                as? VirtualDeviceManager
                ?: run {
                    Log.e(TAG, "VirtualDeviceManager not available")
                    return false
                }

            // Create virtual device params
            val deviceParams = VirtualDeviceParams.Builder()
                .setName("Remoty Camera Device")
                .setDevicePolicy(
                    VirtualDeviceParams.POLICY_TYPE_CAMERA,
                    VirtualDeviceParams.DEVICE_POLICY_CUSTOM
                )
                .build()

            // Create the virtual device
            // Note: This requires CREATE_VIRTUAL_DEVICE permission via Shizuku
            val vd = vdm.createVirtualDevice(
                /* associationId = */ 0, // Requires CompanionDeviceManager association
                deviceParams
            )
            virtualDevice = vd

            // Configure virtual camera
            val cameraConfig = VirtualCameraConfig.Builder("Remoty Remote Camera")
                .addStreamConfig(width, height, ImageFormat.YUV_420_888, fps)
                .setLensFacing(facing)
                .setVirtualCameraCallback(callbackExecutor, virtualCameraCallback)
                .build()

            val vc = vd.createVirtualCamera(cameraConfig)
            virtualCamera = vc

            Log.i(TAG, "Virtual camera created: ${width}x${height} @ ${fps}fps")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create virtual camera", e)
            destroy()
            return false
        }
    }

    private val virtualCameraCallback = object : VirtualCameraCallback {
        override fun onStreamConfigured(
            streamId: Int,
            surface: Surface,
            width: Int,
            height: Int,
            format: Int,
        ) {
            Log.i(TAG, "Stream configured: id=$streamId, ${width}x${height}, format=$format")
            cameraSurface = surface
            onSurfaceReady?.invoke(surface)
        }

        override fun onStreamClosed(streamId: Int) {
            Log.i(TAG, "Stream closed: id=$streamId")
            cameraSurface = null
        }

        override fun onProcessCaptureRequest(streamId: Int, frameId: Long) {
            // The system is requesting a frame. Our decoder continuously writes
            // to the surface, so this is handled automatically.
            // For more precise control, we could signal the decoder here.
        }
    }

    /**
     * Destroy the virtual camera and virtual device.
     */
    fun destroy() {
        try {
            virtualCamera?.close()
        } catch (_: Exception) {}
        virtualCamera = null
        cameraSurface = null

        try {
            virtualDevice?.close()
        } catch (_: Exception) {}
        virtualDevice = null

        Log.i(TAG, "Virtual camera destroyed")
    }

    val isActive: Boolean
        get() = virtualCamera != null && cameraSurface != null
}
