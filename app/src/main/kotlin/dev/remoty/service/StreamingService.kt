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
import dev.remoty.nfc.NfcSessionManager
import dev.remoty.nfc.NfcSessionState
import dev.remoty.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the streaming connection alive
 * when the app is in the background.
 *
 * Holds both [CameraSessionManager] and [NfcSessionManager] references
 * so sessions survive configuration changes and background transitions.
 */
class StreamingService : Service() {

    private val binder = LocalBinder()
    private var serviceScope: CoroutineScope? = null

    var cameraSessionManager: CameraSessionManager? = null
        private set

    var nfcSessionManager: NfcSessionManager? = null
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
        when (intent?.action) {
            ACTION_STOP_CAMERA -> {
                cameraSessionManager?.stop()
            }
            ACTION_STOP_NFC -> {
                nfcSessionManager?.stop()
            }
            else -> {
                val roleName = intent?.getStringExtra(EXTRA_ROLE) ?: DeviceRole.PROVIDER.name
                val notification = buildNotification(
                    isCameraStreaming = false,
                    isNfcActive = false,
                    role = roleName,
                )
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        return START_STICKY
    }

    /**
     * Initialize the session managers with the given channel and role.
     * Called by the activity after binding.
     */
    fun initSession(ch: RemotyChannel, role: DeviceRole) {
        channel = ch

        val cameraManager = CameraSessionManager(this, role, ch)
        cameraSessionManager = cameraManager

        val nfcManager = NfcSessionManager(role, ch)
        nfcSessionManager = nfcManager

        // Update notification when camera or NFC state changes
        serviceScope?.launch {
            combine(cameraManager.state, nfcManager.state) { cam, nfc -> cam to nfc }
                .collectLatest { (camState, nfcState) ->
                    val isCameraStreaming = camState == CameraSessionState.STREAMING
                    val isNfcActive = nfcState != NfcSessionState.IDLE
                    val notification = buildNotification(isCameraStreaming, isNfcActive, role.name)
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIFICATION_ID, notification)
                }
        }
    }

    override fun onDestroy() {
        cameraSessionManager?.stop()
        nfcSessionManager?.stop()
        channel?.close()
        serviceScope?.cancel()
        serviceScope = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(
        isCameraStreaming: Boolean,
        isNfcActive: Boolean,
        role: String,
    ): Notification {
        val isProvider = role == DeviceRole.PROVIDER.name
        val features = buildList {
            if (isCameraStreaming) add(if (isProvider) "📷 Camera streaming" else "📷 Receiving camera")
            if (isNfcActive) add(if (isProvider) "📱 NFC reader active" else "📱 NFC relay active")
        }

        val contentText = when {
            features.isNotEmpty() -> features.joinToString(" · ")
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
                if (isCameraStreaming) {
                    addAction(
                        android.R.drawable.ic_media_pause,
                        "Stop Camera",
                        PendingIntent.getService(
                            this@StreamingService, 1,
                            Intent(this@StreamingService, StreamingService::class.java)
                                .setAction(ACTION_STOP_CAMERA),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                    )
                }
                if (isNfcActive) {
                    addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Stop NFC",
                        PendingIntent.getService(
                            this@StreamingService, 2,
                            Intent(this@StreamingService, StreamingService::class.java)
                                .setAction(ACTION_STOP_NFC),
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
        const val ACTION_STOP_NFC = "dev.remoty.STOP_NFC"

        fun createIntent(context: Context, role: DeviceRole): Intent {
            return Intent(context, StreamingService::class.java).apply {
                putExtra(EXTRA_ROLE, role.name)
            }
        }
    }
}
