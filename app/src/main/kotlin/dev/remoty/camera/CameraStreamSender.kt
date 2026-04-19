package dev.remoty.camera

import android.util.Log
import dev.remoty.data.RemotyPacket
import dev.remoty.net.RemotyChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

private const val TAG = "CameraStreamSender"

/**
 * Provider-side: reads encoded H.264 frames from [H264Encoder.outputChannel]
 * and sends them over the [RemotyChannel] to the consumer.
 *
 * Also handles sending the initial SPS/PPS config data and responding to
 * StartCamera / StopCamera protocol messages.
 */
class CameraStreamSender(
    private val channel: RemotyChannel,
    private val encoder: H264Encoder,
) {
    private var spsData: ByteArray? = null

    /**
     * Main send loop. Reads from encoder output and sends over the channel.
     * Runs until cancelled or encoder output closes.
     */
    suspend fun sendLoop() {
        var frameCount = 0L
        val startTime = System.currentTimeMillis()

        try {
            while (coroutineContext.isActive) {
                val encoded = encoder.outputChannel.receiveCatching().getOrNull() ?: break

                if (encoded.isConfig) {
                    // Cache SPS/PPS for late joiners or reconnection
                    spsData = encoded.data
                    Log.d(TAG, "SPS/PPS config: ${encoded.data.size} bytes")
                }

                // Send frame over encrypted channel
                // Frame header: [1 byte flags][8 bytes timestamp][data]
                val flags: Byte = when {
                    encoded.isConfig -> 0x01
                    encoded.isKeyFrame -> 0x02
                    else -> 0x00
                }

                val header = ByteArray(9)
                header[0] = flags
                // Write timestamp as big-endian long
                val ts = encoded.presentationTimeUs
                for (i in 0..7) {
                    header[8 - i] = (ts shr (i * 8) and 0xFF).toByte()
                }

                val payload = header + encoded.data
                channel.sendFrame(payload)

                frameCount++
                if (frameCount % 300 == 0L) {
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                    val avgFps = frameCount / elapsed
                    Log.d(TAG, "Sent $frameCount frames, avg ${String.format("%.1f", avgFps)} fps")
                }
            }
        } catch (e: Exception) {
            if (coroutineContext.isActive) Log.e(TAG, "Send loop error", e)
        }
        Log.i(TAG, "Send loop ended after $frameCount frames")
    }

    /**
     * Send cached SPS/PPS config data. Useful when consumer reconnects
     * or needs to reinitialize the decoder.
     */
    suspend fun sendConfig() {
        val config = spsData ?: return
        val header = ByteArray(9)
        header[0] = 0x01 // config flag
        val payload = header + config
        channel.sendFrame(payload)
        Log.d(TAG, "Re-sent SPS/PPS config")
    }
}
