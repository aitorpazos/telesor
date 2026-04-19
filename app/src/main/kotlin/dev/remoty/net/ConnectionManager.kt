package dev.remoty.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dev.remoty.crypto.SessionCrypto
import dev.remoty.data.ConnectionState
import dev.remoty.data.DeviceRole
import dev.remoty.data.PairedDevice
import dev.remoty.data.RemotyPacket
import dev.remoty.data.RemotyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private const val TAG = "ConnectionManager"

/**
 * Manages the full lifecycle of a Remoty connection:
 *   - BLE discovery & pairing
 *   - WiFi TCP channel establishment
 *   - Keepalive (Ping/Pong)
 *   - Auto-reconnect on disconnect
 *   - Graceful shutdown
 *
 * This is the single source of truth for connection state.
 * UI observes [connectionState] and [channel] to drive the screens.
 */
class ConnectionManager(
    private val context: Context,
    private val preferences: RemotyPreferences,
) {
    private var scope: CoroutineScope? = null
    private var keepaliveJob: Job? = null
    private var readLoopJob: Job? = null
    private var reconnectJob: Job? = null

    private var _channel: RemotyChannel? = null
    val channel: RemotyChannel? get() = _channel

    private var currentRole: DeviceRole? = null
    private var currentCrypto: SessionCrypto? = null
    private var remoteHost: String? = null
    private var remotePort: Int = 0
    private var listeningPort: Int = 0

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _latencyMs = MutableStateFlow(-1L)
    val latencyMs: StateFlow<Long> = _latencyMs

    /** Callback for incoming control packets. Set by the service/activity. */
    var onPacketReceived: ((RemotyPacket) -> Unit)? = null

    /** Callback when connection is fully established (after Hello exchange). */
    var onConnected: (() -> Unit)? = null

    /** Callback when connection is lost (before reconnect attempt). */
    var onDisconnected: (() -> Unit)? = null

    // ─── Reconnect config ──────────────────────────────────────────────

    private var reconnectEnabled = true
    private var reconnectAttempt = 0
    private val maxReconnectAttempts = 10

    private fun reconnectDelayMs(): Long {
        // Exponential backoff: 1s, 2s, 4s, 8s, 16s, 30s max
        val base = 1000L * (1L shl reconnectAttempt.coerceAtMost(5))
        return base.coerceAtMost(30_000L)
    }

    // ─── Public API ────────────────────────────────────────────────────

    /**
     * Start a connection as provider (listen for incoming TCP).
     * Called after BLE pairing completes.
     */
    fun startAsProvider(
        crypto: SessionCrypto,
        port: Int,
    ) {
        currentRole = DeviceRole.PROVIDER
        currentCrypto = crypto
        listeningPort = port
        reconnectAttempt = 0

        val connectionScope = CoroutineScope(Dispatchers.IO + Job())
        scope = connectionScope

        connectionScope.launch {
            try {
                _connectionState.value = ConnectionState.UPGRADING_TO_WIFI
                val ch = RemotyChannel(crypto)
                val boundPort = ch.listen(port)
                listeningPort = boundPort
                _channel = ch
                onChannelEstablished(ch)
            } catch (e: Exception) {
                Log.e(TAG, "Provider listen failed", e)
                _connectionState.value = ConnectionState.ERROR
                scheduleReconnect()
            }
        }
    }

    /**
     * Start a connection as consumer (connect to provider's TCP).
     * Called after BLE pairing completes.
     */
    fun startAsConsumer(
        crypto: SessionCrypto,
        host: String,
        port: Int,
    ) {
        currentRole = DeviceRole.CONSUMER
        currentCrypto = crypto
        remoteHost = host
        remotePort = port
        reconnectAttempt = 0

        val connectionScope = CoroutineScope(Dispatchers.IO + Job())
        scope = connectionScope

        connectionScope.launch {
            try {
                _connectionState.value = ConnectionState.UPGRADING_TO_WIFI
                val ch = RemotyChannel(crypto)
                ch.connect(host, port)
                _channel = ch
                onChannelEstablished(ch)
            } catch (e: Exception) {
                Log.e(TAG, "Consumer connect failed", e)
                _connectionState.value = ConnectionState.ERROR
                scheduleReconnect()
            }
        }
    }

    /**
     * Graceful disconnect. Sends Bye and tears everything down.
     * Does NOT auto-reconnect.
     */
    fun disconnect() {
        reconnectEnabled = false
        reconnectJob?.cancel()
        reconnectJob = null

        scope?.launch(Dispatchers.IO) {
            try {
                _channel?.send(RemotyPacket.Bye)
            } catch (_: Exception) {}
            teardown()
        } ?: teardown()
    }

    /**
     * Full cleanup. Called on service destroy.
     */
    fun destroy() {
        reconnectEnabled = false
        reconnectJob?.cancel()
        teardown()
    }

    // ─── Internal ──────────────────────────────────────────────────────

    private fun onChannelEstablished(ch: RemotyChannel) {
        _connectionState.value = ConnectionState.CONNECTED
        reconnectAttempt = 0

        // Start read loop
        readLoopJob = scope?.launch {
            try {
                // Launch the channel read loop in background
                launch { ch.readLoop() }

                // Dispatch incoming packets
                for (packet in ch.incomingPackets) {
                    handlePacket(packet)
                }
            } catch (e: Exception) {
                if (scope?.isActive == true) {
                    Log.e(TAG, "Read loop terminated", e)
                }
            } finally {
                handleConnectionLost()
            }
        }

        // Start keepalive
        startKeepalive(ch)

        // Send Hello
        scope?.launch(Dispatchers.IO) {
            try {
                val deviceId = preferences.getDeviceId()
                val caps = dev.remoty.data.DeviceCapabilities(
                    hasCamera = currentRole == DeviceRole.PROVIDER,
                    hasFrontCamera = currentRole == DeviceRole.PROVIDER,
                    hasBackCamera = currentRole == DeviceRole.PROVIDER,
                    hasNfc = currentRole == DeviceRole.PROVIDER,
                )
                ch.send(
                    RemotyPacket.Hello(
                        deviceId = deviceId,
                        deviceName = android.os.Build.MODEL,
                        capabilities = caps,
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send Hello", e)
            }
        }

        onConnected?.invoke()
        Log.i(TAG, "Connection established as ${currentRole?.name}")
    }

    private fun handlePacket(packet: RemotyPacket) {
        when (packet) {
            is RemotyPacket.Ping -> {
                scope?.launch(Dispatchers.IO) {
                    try {
                        _channel?.send(RemotyPacket.Pong(echoTimestamp = packet.timestamp))
                    } catch (_: Exception) {}
                }
            }
            is RemotyPacket.Pong -> {
                val rtt = System.currentTimeMillis() - packet.echoTimestamp
                _latencyMs.value = rtt
            }
            is RemotyPacket.Bye -> {
                Log.i(TAG, "Remote sent Bye — disconnecting")
                reconnectEnabled = false
                teardown()
            }
            else -> {
                // Forward to registered handler (camera, NFC, etc.)
                onPacketReceived?.invoke(packet)
            }
        }
    }

    private fun startKeepalive(ch: RemotyChannel) {
        keepaliveJob?.cancel()
        keepaliveJob = scope?.launch(Dispatchers.IO) {
            while (isActive) {
                delay(KEEPALIVE_INTERVAL_MS)
                try {
                    ch.send(RemotyPacket.Ping(timestamp = System.currentTimeMillis()))
                } catch (e: Exception) {
                    Log.w(TAG, "Keepalive ping failed", e)
                    break
                }
            }
        }
    }

    private fun handleConnectionLost() {
        Log.w(TAG, "Connection lost")
        _connectionState.value = ConnectionState.DISCONNECTED
        _latencyMs.value = -1L
        onDisconnected?.invoke()

        keepaliveJob?.cancel()
        keepaliveJob = null
        readLoopJob = null
        _channel?.close()
        _channel = null

        if (reconnectEnabled) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!reconnectEnabled) return
        if (reconnectAttempt >= maxReconnectAttempts) {
            Log.e(TAG, "Max reconnect attempts ($maxReconnectAttempts) reached")
            _connectionState.value = ConnectionState.ERROR
            return
        }

        val delayMs = reconnectDelayMs()
        reconnectAttempt++
        Log.i(TAG, "Reconnect attempt $reconnectAttempt in ${delayMs}ms")

        reconnectJob = scope?.launch(Dispatchers.IO) {
            delay(delayMs)
            if (!isActive) return@launch

            val crypto = currentCrypto ?: return@launch
            val role = currentRole ?: return@launch

            try {
                _connectionState.value = ConnectionState.UPGRADING_TO_WIFI
                val ch = RemotyChannel(crypto)

                when (role) {
                    DeviceRole.PROVIDER -> {
                        ch.listen(listeningPort)
                    }
                    DeviceRole.CONSUMER -> {
                        val host = remoteHost ?: return@launch
                        ch.connect(host, remotePort)
                    }
                }

                _channel = ch
                onChannelEstablished(ch)
            } catch (e: Exception) {
                Log.e(TAG, "Reconnect attempt $reconnectAttempt failed", e)
                if (reconnectEnabled && isActive) {
                    scheduleReconnect()
                }
            }
        }
    }

    private fun teardown() {
        keepaliveJob?.cancel()
        keepaliveJob = null
        readLoopJob?.cancel()
        readLoopJob = null
        reconnectJob?.cancel()
        reconnectJob = null

        _channel?.close()
        _channel = null

        scope?.cancel()
        scope = null

        _connectionState.value = ConnectionState.DISCONNECTED
        _latencyMs.value = -1L

        Log.i(TAG, "Connection torn down")
    }

    companion object {
        /** Keepalive ping interval. */
        private const val KEEPALIVE_INTERVAL_MS = 10_000L
    }
}
