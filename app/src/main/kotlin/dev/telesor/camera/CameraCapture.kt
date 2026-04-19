package dev.telesor.camera

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "CameraCapture"

/**
 * A raw YUV frame captured from the camera.
 */
data class YuvFrame(
    val yPlane: ByteArray,
    val uPlane: ByteArray,
    val vPlane: ByteArray,
    val yRowStride: Int,
    val uvRowStride: Int,
    val uvPixelStride: Int,
    val width: Int,
    val height: Int,
    val timestampNs: Long,
)

/**
 * Captures camera frames via CameraX and delivers YUV data for encoding.
 *
 * Provider-side component: opens the camera, runs an ImageAnalysis use case,
 * and pushes raw YUV_420_888 frames into a channel for the encoder to consume.
 */
class CameraCapture(private val context: Context) {

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null

    /** Channel that the encoder reads from. Buffered to absorb jitter. */
    val frameChannel = Channel<YuvFrame>(capacity = 2)

    /**
     * Start capturing from the specified camera at the requested resolution.
     * Must be called from a coroutine; suspends until the camera is bound.
     *
     * @param lifecycleOwner  Activity/Fragment lifecycle for CameraX binding
     * @param width           Requested width (closest match used)
     * @param height          Requested height
     * @param fps             Target frame rate (best-effort)
     * @param useFrontCamera  true for front camera, false for back
     * @param previewSurface  Optional Preview.SurfaceProvider for local viewfinder
     * @return Actual resolution selected by CameraX
     */
    suspend fun start(
        lifecycleOwner: LifecycleOwner,
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 30,
        useFrontCamera: Boolean = false,
        previewSurface: Preview.SurfaceProvider? = null,
    ): Size = withContext(Dispatchers.Main) {
        val provider = getCameraProvider()
        cameraProvider = provider
        provider.unbindAll()

        val cameraSelector = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val targetSize = Size(width, height)

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(targetSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .build()

        // ImageAnalysis delivers YUV_420_888 frames
        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                    processFrame(imageProxy)
                }
            }

        // Optional local preview
        val preview = previewSurface?.let {
            Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()
                .also { p -> p.surfaceProvider = it }
        }

        val useCases = listOfNotNull(preview, imageAnalysis).toTypedArray()
        provider.bindToLifecycle(lifecycleOwner, cameraSelector, *useCases)

        // Return actual resolution from the analysis use case
        val actualRes = imageAnalysis.resolutionInfo?.resolution ?: targetSize
        Log.i(TAG, "Camera started: ${actualRes.width}x${actualRes.height}")
        actualRes
    }

    fun stop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        frameChannel.close()
        Log.i(TAG, "Camera stopped")
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            if (imageProxy.format != ImageFormat.YUV_420_888) {
                Log.w(TAG, "Unexpected format: ${imageProxy.format}")
                return
            }

            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val yBytes = ByteArray(yBuffer.remaining()).also { yBuffer.get(it) }
            val uBytes = ByteArray(uBuffer.remaining()).also { uBuffer.get(it) }
            val vBytes = ByteArray(vBuffer.remaining()).also { vBuffer.get(it) }

            val frame = YuvFrame(
                yPlane = yBytes,
                uPlane = uBytes,
                vPlane = vBytes,
                yRowStride = imageProxy.planes[0].rowStride,
                uvRowStride = imageProxy.planes[1].rowStride,
                uvPixelStride = imageProxy.planes[1].pixelStride,
                width = imageProxy.width,
                height = imageProxy.height,
                timestampNs = imageProxy.imageInfo.timestamp,
            )

            // Non-blocking send; drops frame if encoder is behind
            frameChannel.trySend(frame)
        } finally {
            imageProxy.close()
        }
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }, Dispatchers.Main.asExecutor())
        }

    private fun kotlinx.coroutines.CoroutineDispatcher.asExecutor(): java.util.concurrent.Executor =
        java.util.concurrent.Executor { command -> command.run() }
}
