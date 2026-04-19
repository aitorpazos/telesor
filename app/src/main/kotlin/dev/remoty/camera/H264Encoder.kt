package dev.remoty.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

private const val TAG = "H264Encoder"

/**
 * Encoded H.264 output: may be SPS/PPS config data or a coded frame.
 */
data class EncodedFrame(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val isKeyFrame: Boolean,
    val isConfig: Boolean,   // SPS/PPS — must be sent first
)

/**
 * Hardware H.264 encoder using MediaCodec.
 *
 * Consumes YUV frames from [CameraCapture.frameChannel] and produces
 * encoded H.264 NAL units into [outputChannel].
 *
 * Tuned for low-latency streaming:
 * - Baseline profile for compatibility
 * - CBR rate control
 * - 1-second keyframe interval
 * - Low latency priority
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30,
    private val bitrate: Int = 2_000_000, // 2 Mbps default
) {
    private var codec: MediaCodec? = null

    /** Encoded output frames. Consumer reads from here. */
    val outputChannel = Channel<EncodedFrame>(capacity = Channel.BUFFERED)

    /**
     * Configure and start the encoder. Call once before [encodeLoop].
     */
    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // keyframe every 1 second
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            // Low latency hints
            setInteger(MediaFormat.KEY_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time priority
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        codec = encoder
        Log.i(TAG, "Encoder started: ${width}x${height} @ ${bitrate / 1000}kbps, ${fps}fps")
    }

    /**
     * Main encode loop. Reads YUV frames from [inputChannel], feeds them
     * to the hardware encoder, and pushes encoded output to [outputChannel].
     *
     * Runs until the coroutine is cancelled or input channel is closed.
     */
    suspend fun encodeLoop(inputChannel: Channel<YuvFrame>) = withContext(Dispatchers.IO) {
        val encoder = codec ?: throw IllegalStateException("Encoder not started")
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            while (coroutineContext.isActive) {
                val frame = inputChannel.receiveCatching().getOrNull() ?: break

                // Feed input
                val inputIndex = encoder.dequeueInputBuffer(10_000) // 10ms timeout
                if (inputIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputIndex)!!
                    val yuvData = convertToI420(frame, inputBuffer.capacity())
                    inputBuffer.clear()
                    inputBuffer.put(yuvData)
                    encoder.queueInputBuffer(
                        inputIndex, 0, yuvData.size,
                        frame.timestampNs / 1000, // ns -> us
                        0
                    )
                }

                // Drain output
                drainOutput(encoder, bufferInfo)
            }
        } catch (e: Exception) {
            if (coroutineContext.isActive) Log.e(TAG, "Encode loop error", e)
        } finally {
            try {
                encoder.stop()
                encoder.release()
            } catch (_: Exception) {}
            codec = null
            outputChannel.close()
            Log.i(TAG, "Encoder stopped")
        }
    }

    private suspend fun drainOutput(encoder: MediaCodec, bufferInfo: MediaCodec.BufferInfo) {
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0) // non-blocking
            if (outputIndex < 0) break

            val outputBuffer = encoder.getOutputBuffer(outputIndex) ?: continue

            if (bufferInfo.size > 0) {
                val data = ByteArray(bufferInfo.size)
                outputBuffer.position(bufferInfo.offset)
                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                outputBuffer.get(data)

                val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                val encoded = EncodedFrame(
                    data = data,
                    presentationTimeUs = bufferInfo.presentationTimeUs,
                    isKeyFrame = isKeyFrame,
                    isConfig = isConfig,
                )
                outputChannel.trySend(encoded)
            }

            encoder.releaseOutputBuffer(outputIndex, false)
        }
    }

    fun stop() {
        try {
            codec?.signalEndOfInputStream()
        } catch (_: Exception) {}
    }

    /**
     * Convert YUV_420_888 frame to I420 (planar YUV) for the encoder.
     *
     * MediaCodec with COLOR_FormatYUV420Flexible typically wants I420 or NV12.
     * We produce I420: Y plane, then U plane, then V plane, all contiguous.
     */
    companion object {
        fun convertToI420(frame: YuvFrame, bufferCapacity: Int): ByteArray {
            val w = frame.width
            val h = frame.height
            val ySize = w * h
            val uvSize = (w / 2) * (h / 2)
            val totalSize = ySize + uvSize * 2
            val output = ByteArray(totalSize)

            // Copy Y plane (handle row stride padding)
            if (frame.yRowStride == w) {
                System.arraycopy(frame.yPlane, 0, output, 0, ySize)
            } else {
                for (row in 0 until h) {
                    System.arraycopy(
                        frame.yPlane, row * frame.yRowStride,
                        output, row * w,
                        w
                    )
                }
            }

            // Copy U and V planes (handle pixel stride and row stride)
            val uvW = w / 2
            val uvH = h / 2
            var uOffset = ySize
            var vOffset = ySize + uvSize

            if (frame.uvPixelStride == 1 && frame.uvRowStride == uvW) {
                // Already planar, direct copy
                System.arraycopy(frame.uPlane, 0, output, uOffset, uvSize)
                System.arraycopy(frame.vPlane, 0, output, vOffset, uvSize)
            } else {
                // Interleaved or padded — extract pixel by pixel
                for (row in 0 until uvH) {
                    for (col in 0 until uvW) {
                        val srcIndex = row * frame.uvRowStride + col * frame.uvPixelStride
                        output[uOffset++] = frame.uPlane[srcIndex]
                        output[vOffset++] = frame.vPlane[srcIndex]
                    }
                }
            }

            return output
        }
    }
}
