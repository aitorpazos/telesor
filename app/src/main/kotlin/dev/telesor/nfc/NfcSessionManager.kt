package dev.telesor.nfc

import android.app.Activity
import android.util.Log
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

private const val TAG = "NfcSession"

/**
 * State of the NFC relay session.
 */
enum class NfcSessionState {
    IDLE,
    STARTING,
    WAITING_FOR_TAG,
    TAG_CONNECTED,
    STOPPING,
    ERROR,
}

/**
 * Orchestrates the full NFC relay pipeline for both roles.
 *
 * **Provider side:**
 *   NfcReaderManager detects tags → APDU commands arrive from consumer
 *   → transceive on physical tag → send response back
 *
 * **Consumer side:**
 *   RelayHostApduService receives APDUs from external NFC reader
 *   → sends NfcApduCommand to provider → waits for NfcApduResponse
 *   → returns response to external reader
 */
class NfcSessionManager(
    private val role: DeviceRole,
    private val channel: TelesorChannel,
) {
    private var scope: CoroutineScope? = null

    // Provider components
    private var nfcReaderManager: NfcReaderManager? = null

    // Consumer components
    private val pendingResponses = PendingApduResponses()

    private val _state = MutableStateFlow(NfcSessionState.IDLE)
    val state: StateFlow<NfcSessionState> = _state

    private val _tagInfo = MutableStateFlow<TagInfo?>(null)
    val tagInfo: StateFlow<TagInfo?> = _tagInfo

    data class TagInfo(
        val uid: String,
        val techList: List<String>,
        val isIsoDep: Boolean,
    )

    // ─── Provider Methods ──────────────────────────────────────────────

    /**
     * Start NFC reader mode on the provider.
     * Called when a NfcStartRelay packet is received from the consumer.
     */
    fun startProvider(activity: Activity) {
        if (role != DeviceRole.PROVIDER) return
        if (_state.value == NfcSessionState.WAITING_FOR_TAG || _state.value == NfcSessionState.TAG_CONNECTED) return

        _state.value = NfcSessionState.STARTING
        val sessionScope = CoroutineScope(Dispatchers.Default + Job())
        scope = sessionScope

        val reader = NfcReaderManager()
        nfcReaderManager = reader
        reader.start(activity)

        _state.value = NfcSessionState.WAITING_FOR_TAG

        // Notify consumer that relay is active
        sessionScope.launch(Dispatchers.IO) {
            try {
                channel.send(TelesorPacket.NfcRelayStarted)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send NfcRelayStarted", e)
            }
        }

        // Monitor tag events and forward to consumer
        sessionScope.launch {
            for (event in reader.tagEvents) {
                when (event) {
                    is NfcReaderManager.TagEvent.Detected -> {
                        val uid = event.uid.toHex()
                        val techs = event.techList
                        val hasIsoDep = techs.any { it.contains("IsoDep") }

                        _tagInfo.value = TagInfo(uid, techs, hasIsoDep)
                        _state.value = if (hasIsoDep) {
                            NfcSessionState.TAG_CONNECTED
                        } else {
                            NfcSessionState.WAITING_FOR_TAG
                        }

                        launch(Dispatchers.IO) {
                            try {
                                channel.send(
                                    TelesorPacket.NfcTagDetected(
                                        uid = uid,
                                        techList = techs,
                                        atsHex = event.ats?.toHex(),
                                    )
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to send NfcTagDetected", e)
                            }
                        }

                        Log.i(TAG, "Tag detected: uid=$uid, isoDep=$hasIsoDep")
                    }

                    is NfcReaderManager.TagEvent.Lost -> {
                        _tagInfo.value = null
                        _state.value = NfcSessionState.WAITING_FOR_TAG

                        launch(Dispatchers.IO) {
                            try {
                                channel.send(TelesorPacket.NfcTagLost)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to send NfcTagLost", e)
                            }
                        }

                        Log.i(TAG, "Tag lost")
                    }
                }
            }
        }

        Log.i(TAG, "Provider NFC relay started")
    }

    /**
     * Handle an APDU command from the consumer on the provider side.
     * Transceives with the physical tag and sends the response back.
     */
    fun handleApduCommand(command: TelesorPacket.NfcApduCommand) {
        if (role != DeviceRole.PROVIDER) return

        val reader = nfcReaderManager
        if (reader == null || !reader.isTagConnected) {
            Log.w(TAG, "No tag connected for APDU relay")
            scope?.launch(Dispatchers.IO) {
                try {
                    channel.send(
                        TelesorPacket.NfcApduResponse(
                            response = "6985", // Conditions not satisfied
                            requestId = command.requestId,
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send error response", e)
                }
            }
            return
        }

        scope?.launch(Dispatchers.IO) {
            try {
                val commandBytes = command.apdu.hexToByteArray()
                val responseBytes = reader.transceive(commandBytes)

                channel.send(
                    TelesorPacket.NfcApduResponse(
                        response = responseBytes.toHex(),
                        requestId = command.requestId,
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "APDU transceive/relay failed", e)
                try {
                    channel.send(
                        TelesorPacket.NfcApduResponse(
                            response = "6F00", // No precise diagnosis
                            requestId = command.requestId,
                        )
                    )
                } catch (_: Exception) {}
            }
        }
    }

    fun stopProvider(activity: Activity) {
        if (role != DeviceRole.PROVIDER) return
        _state.value = NfcSessionState.STOPPING

        nfcReaderManager?.stop(activity)
        nfcReaderManager = null

        scope?.cancel()
        scope = null

        _tagInfo.value = null
        _state.value = NfcSessionState.IDLE

        CoroutineScope(Dispatchers.IO).launch {
            try { channel.send(TelesorPacket.NfcRelayStopped) } catch (_: Exception) {}
        }

        Log.i(TAG, "Provider NFC relay stopped")
    }

    // ─── Consumer Methods ──────────────────────────────────────────────

    /**
     * Start NFC relay on the consumer side.
     * Registers this session as the active APDU relay for the HCE service.
     */
    fun startConsumer() {
        if (role != DeviceRole.CONSUMER) return
        if (_state.value != NfcSessionState.IDLE) return

        _state.value = NfcSessionState.STARTING
        val sessionScope = CoroutineScope(Dispatchers.Default + Job())
        scope = sessionScope

        // Register as the active relay for the HCE service
        val relay = object : ApduRelay {
            override suspend fun relayApdu(command: ByteArray, requestId: Long): ByteArray {
                val deferred = pendingResponses.expect(requestId)

                // Send command to provider
                channel.send(
                    TelesorPacket.NfcApduCommand(
                        apdu = command.toHex(),
                        requestId = requestId,
                    )
                )

                // Wait for response (timeout handled by caller)
                return deferred.await()
            }

            override fun onDeactivated() {
                pendingResponses.cancelAll()
            }
        }
        RelayHostApduService.activeRelay = relay

        // Request provider to start NFC reader
        sessionScope.launch(Dispatchers.IO) {
            try {
                channel.send(TelesorPacket.NfcStartRelay)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send NfcStartRelay", e)
                _state.value = NfcSessionState.ERROR
            }
        }

        Log.i(TAG, "Consumer NFC relay started — waiting for provider")
    }

    /**
     * Handle an APDU response from the provider on the consumer side.
     * Delivers the response to the waiting HCE service thread.
     */
    fun handleApduResponse(response: TelesorPacket.NfcApduResponse) {
        if (role != DeviceRole.CONSUMER) return

        val responseBytes = response.response.hexToByteArray()
        val delivered = pendingResponses.deliver(response.requestId, responseBytes)
        if (!delivered) {
            Log.w(TAG, "Unmatched APDU response for requestId=${response.requestId}")
        }
    }

    fun stopConsumer() {
        if (role != DeviceRole.CONSUMER) return
        _state.value = NfcSessionState.STOPPING

        // Unregister from HCE service
        RelayHostApduService.activeRelay = null
        pendingResponses.cancelAll()

        scope?.cancel()
        scope = null

        _tagInfo.value = null
        _state.value = NfcSessionState.IDLE

        CoroutineScope(Dispatchers.IO).launch {
            try { channel.send(TelesorPacket.NfcStopRelay) } catch (_: Exception) {}
        }

        Log.i(TAG, "Consumer NFC relay stopped")
    }

    // ─── Common ────────────────────────────────────────────────────────

    fun stop(activity: Activity? = null) {
        when (role) {
            DeviceRole.PROVIDER -> activity?.let { stopProvider(it) }
            DeviceRole.CONSUMER -> stopConsumer()
        }
    }

    /**
     * Handle incoming protocol packets related to NFC.
     * Call this from the main packet dispatch loop.
     *
     * @param activity Required on provider side for NFC reader mode
     */
    fun handlePacket(packet: TelesorPacket, activity: Activity? = null) {
        when (packet) {
            // Consumer → Provider: start reader
            is TelesorPacket.NfcStartRelay -> {
                if (role == DeviceRole.PROVIDER && activity != null) {
                    startProvider(activity)
                }
            }

            // Provider → Consumer: reader started
            is TelesorPacket.NfcRelayStarted -> {
                if (role == DeviceRole.CONSUMER) {
                    _state.value = NfcSessionState.WAITING_FOR_TAG
                    Log.i(TAG, "Provider confirmed NFC relay started")
                }
            }

            // Consumer → Provider: stop reader
            is TelesorPacket.NfcStopRelay -> {
                if (role == DeviceRole.PROVIDER && activity != null) {
                    stopProvider(activity)
                }
            }

            // Provider → Consumer: reader stopped
            is TelesorPacket.NfcRelayStopped -> {
                if (role == DeviceRole.CONSUMER) {
                    _state.value = NfcSessionState.IDLE
                    _tagInfo.value = null
                    Log.i(TAG, "Provider confirmed NFC relay stopped")
                }
            }

            // Consumer → Provider: APDU command
            is TelesorPacket.NfcApduCommand -> {
                handleApduCommand(packet)
            }

            // Provider → Consumer: APDU response
            is TelesorPacket.NfcApduResponse -> {
                handleApduResponse(packet)
            }

            // Provider → Consumer: tag detected
            is TelesorPacket.NfcTagDetected -> {
                if (role == DeviceRole.CONSUMER) {
                    val hasIsoDep = packet.techList.any { it.contains("IsoDep") }
                    _tagInfo.value = TagInfo(packet.uid, packet.techList, hasIsoDep)
                    _state.value = if (hasIsoDep) {
                        NfcSessionState.TAG_CONNECTED
                    } else {
                        NfcSessionState.WAITING_FOR_TAG
                    }
                    Log.i(TAG, "Remote tag detected: uid=${packet.uid}")
                }
            }

            // Provider → Consumer: tag lost
            is TelesorPacket.NfcTagLost -> {
                if (role == DeviceRole.CONSUMER) {
                    _tagInfo.value = null
                    _state.value = NfcSessionState.WAITING_FOR_TAG
                    Log.i(TAG, "Remote tag lost")
                }
            }

            else -> { /* not an NFC packet */ }
        }
    }
}
