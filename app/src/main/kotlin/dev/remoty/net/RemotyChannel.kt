package dev.remoty.net

import android.util.Log
import dev.remoty.crypto.SessionCrypto
import dev.remoty.data.RemotyPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "RemotyChannel"
private const val MAX_MESSAGE_SIZE = 4 * 1024 * 1024 // 4 MB for control messages
private const val FRAME_TAG: Byte = 0x01
private const val CONTROL_TAG: Byte = 0x02

/**
 * Encrypted bidirectional channel over TCP.
 *
 * Messages are framed as:
 *   [1 byte tag][4 bytes big-endian length][encrypted payload]
 *
 * Tag 0x01 = raw frame data (H.264 NALUs for camera)
 * Tag 0x02 = JSON control message (RemotyPacket)
 */
class RemotyChannel(
    private val crypto: SessionCrypto,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private var socket: Socket? = null
    private var dataIn: DataInputStream? = null
    private var dataOut: DataOutputStream? = null

    /** Incoming control packets. */
    val incomingPackets = Channel<RemotyPacket>(Channel.BUFFERED)

    /** Incoming raw frame data (camera H.264). */
    val incomingFrames = Channel<ByteArray>(Channel.BUFFERED)

    /** Start a TCP server and wait for one connection. Returns the bound port. */
    suspend fun listen(port: Int = 0): Int = withContext(Dispatchers.IO) {
        val serverSocket = ServerSocket()
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress("0.0.0.0", port))
        val boundPort = serverSocket.localPort
        Log.i(TAG, "Listening on port $boundPort")

        val client = serverSocket.accept()
        serverSocket.close()
        attach(client)
        boundPort
    }

    /** Connect to a remote host. */
    suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        val sock = Socket()
        sock.connect(InetSocketAddress(host, port), 10_000)
        attach(sock)
    }

    private fun attach(sock: Socket) {
        socket = sock
        sock.tcpNoDelay = true
        sock.soTimeout = 0 // blocking reads, managed by coroutine cancellation
        dataIn = DataInputStream(sock.getInputStream().buffered(65536))
        dataOut = DataOutputStream(sock.getOutputStream().buffered(65536))
        Log.i(TAG, "Connected to ${sock.remoteSocketAddress}")
    }

    /** Start reading loop. Call from a coroutine scope. */
    suspend fun readLoop() = coroutineScope {
        val input = dataIn ?: throw IllegalStateException("Not connected")

        launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val tag = input.readByte()
                    val length = input.readInt()

                    if (length < 0 || length > MAX_MESSAGE_SIZE) {
                        Log.e(TAG, "Invalid message length: $length")
                        break
                    }

                    val encrypted = ByteArray(length)
                    input.readFully(encrypted)
                    val decrypted = crypto.decrypt(encrypted)

                    when (tag) {
                        FRAME_TAG -> incomingFrames.trySend(decrypted)
                        CONTROL_TAG -> {
                            val jsonStr = String(decrypted, Charsets.UTF_8)
                            val packet = json.decodeFromString<RemotyPacket>(jsonStr)
                            incomingPackets.trySend(packet)
                        }
                        else -> Log.w(TAG, "Unknown tag: $tag")
                    }
                }
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "Read loop error", e)
            } finally {
                close()
            }
        }
    }

    /** Send a control packet. */
    suspend fun send(packet: RemotyPacket) = withContext(Dispatchers.IO) {
        val output = dataOut ?: throw IllegalStateException("Not connected")
        val jsonStr = json.encodeToString(packet)
        val encrypted = crypto.encrypt(jsonStr.toByteArray(Charsets.UTF_8))

        synchronized(output) {
            output.writeByte(CONTROL_TAG.toInt())
            output.writeInt(encrypted.size)
            output.write(encrypted)
            output.flush()
        }
    }

    /** Send raw frame data (camera). */
    suspend fun sendFrame(data: ByteArray) = withContext(Dispatchers.IO) {
        val output = dataOut ?: throw IllegalStateException("Not connected")
        val encrypted = crypto.encrypt(data)

        synchronized(output) {
            output.writeByte(FRAME_TAG.toInt())
            output.writeInt(encrypted.size)
            output.write(encrypted)
            output.flush()
        }
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        dataIn = null
        dataOut = null
        incomingPackets.close()
        incomingFrames.close()
    }

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false
}
