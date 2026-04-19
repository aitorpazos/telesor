package dev.remoty.camera

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
 * All VirtualDeviceManager / VirtualCamera APIs are @SystemApi, so we access
 * them via reflection to avoid compile-time dependency on hidden APIs.
 *
 * When active, the virtual camera appears in CameraManager.getCameraIdList()
 * for ALL apps on the device — video calls, QR scanners, etc. all see it.
 *
 * Architecture:
 *   Remote camera frames → H264Decoder → Surface ← VirtualCamera reads from
 */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM) // API 35
class VirtualCameraManager(private val context: Context) {

    private var virtualDevice: Any? = null   // VirtualDevice (accessed via reflection)
    private var virtualCamera: Any? = null   // VirtualCamera
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
     * All classes are accessed via reflection since they are not in the public SDK.
     *
     * @param width   Camera resolution width
     * @param height  Camera resolution height
     * @param fps     Max frame rate
     * @param facing  LENS_FACING_FRONT or LENS_FACING_BACK
     * @return true if creation succeeded
     */
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
            // Get VirtualDeviceManager via Context.getSystemService("virtual_device")
            val vdm = context.getSystemService("virtual_device")
                ?: run {
                    Log.e(TAG, "VirtualDeviceManager not available")
                    return false
                }

            // Build VirtualDeviceParams via reflection
            val paramsBuilderClass = Class.forName("android.companion.virtual.VirtualDeviceParams\$Builder")
            val paramsBuilder = paramsBuilderClass.getDeclaredConstructor().newInstance()

            // setName
            paramsBuilderClass.getMethod("setName", String::class.java)
                .invoke(paramsBuilder, "Remoty Camera Device")

            // setDevicePolicy(POLICY_TYPE_CAMERA = 0, DEVICE_POLICY_CUSTOM = 1)
            paramsBuilderClass.getMethod("setDevicePolicy", Int::class.java, Int::class.java)
                .invoke(paramsBuilder, 0 /* POLICY_TYPE_CAMERA */, 1 /* DEVICE_POLICY_CUSTOM */)

            val deviceParams = paramsBuilderClass.getMethod("build").invoke(paramsBuilder)

            // createVirtualDevice(associationId, params)
            val vdmClass = vdm.javaClass
            val paramsClass = Class.forName("android.companion.virtual.VirtualDeviceParams")
            val vd = vdmClass.getMethod("createVirtualDevice", Int::class.java, paramsClass)
                .invoke(vdm, 0, deviceParams)
            virtualDevice = vd

            // Build VirtualCameraConfig via reflection
            val configBuilderClass = Class.forName("android.companion.virtual.camera.VirtualCameraConfig\$Builder")
            val configBuilder = configBuilderClass.getDeclaredConstructor(String::class.java)
                .newInstance("Remoty Remote Camera")

            // addStreamConfig(width, height, format, fps)
            configBuilderClass.getMethod("addStreamConfig", Int::class.java, Int::class.java, Int::class.java, Int::class.java)
                .invoke(configBuilder, width, height, ImageFormat.YUV_420_888, fps)

            // setLensFacing
            configBuilderClass.getMethod("setLensFacing", Int::class.java)
                .invoke(configBuilder, facing)

            // Create a callback proxy via java.lang.reflect.Proxy
            val callbackClass = Class.forName("android.companion.virtual.camera.VirtualCameraCallback")
            val callbackProxy = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { _, method, args ->
                when (method.name) {
                    "onStreamConfigured" -> {
                        val streamId = args!![0] as Int
                        val surface = args[1] as Surface
                        val w = args[2] as Int
                        val h = args[3] as Int
                        val format = args[4] as Int
                        Log.i(TAG, "Stream configured: id=$streamId, ${w}x${h}, format=$format")
                        cameraSurface = surface
                        onSurfaceReady?.invoke(surface)
                    }
                    "onStreamClosed" -> {
                        val streamId = args!![0] as Int
                        Log.i(TAG, "Stream closed: id=$streamId")
                        cameraSurface = null
                    }
                    "onProcessCaptureRequest" -> {
                        // The system is requesting a frame. Our decoder continuously writes
                        // to the surface, so this is handled automatically.
                    }
                }
                null
            }

            // setVirtualCameraCallback(executor, callback)
            configBuilderClass.getMethod("setVirtualCameraCallback", java.util.concurrent.Executor::class.java, callbackClass)
                .invoke(configBuilder, callbackExecutor, callbackProxy)

            val cameraConfig = configBuilderClass.getMethod("build").invoke(configBuilder)

            // createVirtualCamera(config)
            val configClass = Class.forName("android.companion.virtual.camera.VirtualCameraConfig")
            val vc = vd!!.javaClass.getMethod("createVirtualCamera", configClass)
                .invoke(vd, cameraConfig)
            virtualCamera = vc

            Log.i(TAG, "Virtual camera created: ${width}x${height} @ ${fps}fps")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create virtual camera", e)
            destroy()
            return false
        }
    }

    /**
     * Destroy the virtual camera and virtual device.
     */
    fun destroy() {
        try {
            virtualCamera?.let {
                it.javaClass.getMethod("close").invoke(it)
            }
        } catch (_: Exception) {}
        virtualCamera = null
        cameraSurface = null

        try {
            virtualDevice?.let {
                it.javaClass.getMethod("close").invoke(it)
            }
        } catch (_: Exception) {}
        virtualDevice = null

        Log.i(TAG, "Virtual camera destroyed")
    }

    val isActive: Boolean
        get() = virtualCamera != null && cameraSurface != null
}
