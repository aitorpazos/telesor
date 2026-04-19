package dev.remoty.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.remoty.camera.CameraSessionManager
import dev.remoty.camera.CameraSessionState
import dev.remoty.data.DeviceRole
import dev.remoty.net.RemotyChannel
import dev.remoty.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the streaming connection alive
 * when the app is in the background.
 *
 * Also holds the [CameraSessionManager] reference so camera streaming
 * survives configuration changes and background transitions.
 */
class StreamingService : Service() {

    private val binder = LocalBinder()
    private var serviceScope: CoroutineScope? = null

    var cameraSessionManager: CameraSessionManager? = null
        private set

    var channel: RemotyChannel? = null
        private set

    inner class LocalBinder : Binder() {
        val service: StreamingService get() = this@StreamingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceScope = CoroutineScope(Dispatchers.Main + Job())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val roleName = intent?.getStringExtra(EXTRA_ROLE) ?: DeviceRole.PROVIDER.name
        val notification = buildNotification(
            isStreaming = false,
            role = roleName,
        )
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    /**
     * Initialize the camera session manager with the given channel and role.
     * Called by the activity after binding.
     */
    fun initSession(ch: RemotyChannel, role: DeviceRole) {
        channel = ch
        val manager = CameraSessionManager(this, role, ch)
        cameraSessionManager = manager

        // Update notification when camera state changes
        serviceScope?.launch {
            manager.state.collectLatest { state ->
                val isStreaming = state == CameraSessionState.STREAMING
                val notification = buildNotification(isStreaming, role.name)
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    override fun onDestroy() {
        cameraSessionManager?.stop()
        channel?.close()
        serviceScope?.cancel()
        serviceScope = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(isStreaming: Boolean, role: String): Notification {
        val contentText = when {
            isStreaming && role == DeviceRole.PROVIDER.name -> "Streaming camera to remote device"
            isStreaming && role == DeviceRole.CONSUMER.name -> "Receiving camera from remote device"
            else -> "Connected — sharing sensors"
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Remoty")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .apply {
                if (isStreaming) {
                    addAction(
                        android.R.drawable.ic_media_pause,
                        "Stop",
                        PendingIntent.getService(
                            this@StreamingService, 1,
                            Intent(this@StreamingService, StreamingService::class.java)
                                .setAction(ACTION_STOP_CAMERA),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                    )
                }
            }
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Remoty Streaming",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Active sensor sharing session"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "remoty_streaming"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_ROLE = "role"
        const val ACTION_STOP_CAMERA = "dev.remoty.STOP_CAMERA"

        fun createIntent(context: Context, role: DeviceRole): Intent {
            return Intent(context, StreamingService::class.java).apply {
                putExtra(EXTRA_ROLE, role.name)
            }
        }
    }
}
