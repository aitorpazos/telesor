package dev.telesor.camera

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import dev.telesor.net.TelesorChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

private const val TAG = "H264Decoder"

/**
 * Consumer-side H.264 decoder.
 *
 * Receives encoded frames from [TelesorChannel.incomingFrames], decodes them
 * with hardware MediaCodec, and renders to a [Surface].
 *
 * The Surface can be:
 * - A SurfaceView/TextureView for in-app preview
 * - A VirtualCamera's input Surface for system-wide virtual camera
 * - Both (via a surface multiplexer)
 */
class H264Decoder(
    private val width: Int,
    private val height: Int,
) {
    private var codec: MediaCodec? = null
    private var isConfigured = false

    /**
     * Start the decoder. The [outputSurface] receives decoded frames.
     *
     * Does NOT start decoding — call [decodeLoop] for that.
     * The decoder is lazily configured when the first SPS/PPS config frame arrives.
     */
    fun prepare(outputSurface: Surface) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            // Low latency decoding
            setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }

        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        decoder.configure(format, outputSurface, null, 0)
        decoder.start()
        codec = decoder
        isConfigured = true
        Log.i(TAG, "Decoder prepared: ${width}x${height}")
    }

    /**
     * Main decode loop. Reads frames from the channel and decodes them.
     *
     * Frame format from sender:
     *   [1 byte flags][8 bytes timestamp][H.264 data]
     *   flags: 0x01 = config (SPS/PPS), 0x02 = keyframe, 0x00 = P-frame
     */
    suspend fun decodeLoop(channel: TelesorChannel) = withContext(Dispatchers.IO) {
        val decoder = codec ?: throw IllegalStateException("Decoder not prepared")
        val bufferInfo = MediaCodec.BufferInfo()
        var frameCount = 0L
        var configReceived = false

        try {
            while (coroutineContext.isActive) {
                val rawFrame = channel.incomingFrames.receiveCatching().getOrNull() ?: break

                if (rawFrame.size < 9) {
                    Log.w(TAG, "Frame too small: ${rawFrame.size}")
                    continue
                }

                // Parse header
                val flags = rawFrame[0]
                var timestamp = 0L
                for (i in 1..8) {
                    timestamp = (timestamp shl 8) or (rawFrame[i].toLong() and 0xFF)
                }
                val h264Data = rawFrame.copyOfRange(9, rawFrame.size)

                val isConfig = flags == 0x01.toByte()
                val isKeyFrame = flags == 0x02.toByte()

                if (isConfig) {
                    configReceived = true
                    Log.d(TAG, "Config frame received: ${h264Data.size} bytes")
                }

                // Don't feed P-frames until we have config + keyframe
                if (!configReceived && !isConfig) {
                    continue
                }

                // Feed to decoder
                val inputIndex = decoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex)!!
                    inputBuffer.clear()
                    inputBuffer.put(h264Data)

                    val codecFlags = when {
                        isConfig -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                        isKeyFrame -> MediaCodec.BUFFER_FLAG_KEY_FRAME
                        else -> 0
                    }

                    decoder.queueInputBuffer(inputIndex, 0, h264Data.size, timestamp, codecFlags)
                }

                // Drain output — render to surface
                drainOutput(decoder, bufferInfo)

                frameCount++
                if (frameCount % 300 == 0L) {
                    Log.d(TAG, "Decoded $frameCount frames")
                }
            }
        } catch (e: Exception) {
            if (coroutineContext.isActive) Log.e(TAG, "Decode loop error", e)
        } finally {
            try {
                decoder.stop()
                decoder.release()
            } catch (_: Exception) {}
            codec = null
            isConfigured = false
            Log.i(TAG, "Decoder stopped after $frameCount frames")
        }
    }

    private fun drainOutput(decoder: MediaCodec, bufferInfo: MediaCodec.BufferInfo) {
        while (true) {
            val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
            if (outputIndex < 0) break
            // Render to surface (true = render)
            decoder.releaseOutputBuffer(outputIndex, true)
        }
    }

    fun stop() {
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {}
        codec = null
        isConfigured = false
    }
}
