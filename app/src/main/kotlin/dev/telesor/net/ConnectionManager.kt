package dev.telesor.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dev.telesor.crypto.SessionCrypto
import dev.telesor.data.ConnectionState
import dev.telesor.data.DeviceRole
import dev.telesor.data.PairedDevice
import dev.telesor.data.TelesorPacket
import dev.telesor.data.TelesorPreferences
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
 * Manages the full lifecycle of a Telesor connection:
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
    private val preferences: TelesorPreferences,
) {
    private var scope: CoroutineScope? = null
    private var keepaliveJob: Job? = null
    private var readLoopJob: Job? = null
    private var reconnectJob: Job? = null

    private var _channel: TelesorChannel? = null
    val channel: TelesorChannel? get() = _channel

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
    var onPacketReceived: ((TelesorPacket) -> Unit)? = null

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
                val ch = TelesorChannel(crypto)
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
            _connectionState.value = ConnectionState.UPGRADING_TO_WIFI

            // The provider's TCP server may not be listening yet (race between
            // BLE pairing completion and TCP bind). Retry a few times with
            // increasing delays to give the provider time to start.
            var lastError: Exception? = null
            for (attempt in 0 until INITIAL_CONNECT_RETRIES) {
                try {
                    if (attempt > 0) {
                        val backoff = INITIAL_CONNECT_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(3))
                        Log.i(TAG, "Consumer connect attempt ${attempt + 1}, waiting ${backoff}ms")
                        delay(backoff)
                    }
                    val ch = TelesorChannel(crypto)
                    ch.connect(host, port)
                    _channel = ch
                    onChannelEstablished(ch)
                    return@launch
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "Consumer connect attempt ${attempt + 1} failed: ${e.message}")
                }
            }

            Log.e(TAG, "Consumer connect failed after $INITIAL_CONNECT_RETRIES attempts", lastError)
            _connectionState.value = ConnectionState.ERROR
            scheduleReconnect()
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
                _channel?.send(TelesorPacket.Bye)
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

    private fun onChannelEstablished(ch: TelesorChannel) {
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
                val caps = dev.telesor.data.DeviceCapabilities(
                    hasCamera = currentRole == DeviceRole.PROVIDER,
                    hasFrontCamera = currentRole == DeviceRole.PROVIDER,
                    hasBackCamera = currentRole == DeviceRole.PROVIDER,
                    hasNfc = currentRole == DeviceRole.PROVIDER,
                )
                ch.send(
                    TelesorPacket.Hello(
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

    private fun handlePacket(packet: TelesorPacket) {
        when (packet) {
            is TelesorPacket.Ping -> {
                scope?.launch(Dispatchers.IO) {
                    try {
                        _channel?.send(TelesorPacket.Pong(echoTimestamp = packet.timestamp))
                    } catch (_: Exception) {}
                }
            }
            is TelesorPacket.Pong -> {
                val rtt = System.currentTimeMillis() - packet.echoTimestamp
                _latencyMs.value = rtt
            }
            is TelesorPacket.Bye -> {
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

    private fun startKeepalive(ch: TelesorChannel) {
        keepaliveJob?.cancel()
        keepaliveJob = scope?.launch(Dispatchers.IO) {
            while (isActive) {
                delay(KEEPALIVE_INTERVAL_MS)
                try {
                    ch.send(TelesorPacket.Ping(timestamp = System.currentTimeMillis()))
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
                val ch = TelesorChannel(crypto)

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

        /** Number of TCP connect retries before falling back to reconnect logic. */
        private const val INITIAL_CONNECT_RETRIES = 6

        /** Base delay between initial connect retries (doubles each attempt). */
        private const val INITIAL_CONNECT_DELAY_MS = 500L
    }
}
