package dev.remoty.camera

import org.junit.Assert.*
import org.junit.Test

class H264EncoderTest {

    @Test
    fun `convertToI420 with planar input (pixelStride=1, rowStride=width)`() {
        val w = 4
        val h = 4
        val ySize = w * h
        val uvW = w / 2
        val uvH = h / 2
        val uvSize = uvW * uvH

        // Create test frame with known values
        val yPlane = ByteArray(ySize) { (it + 1).toByte() }
        val uPlane = ByteArray(uvSize) { (it + 100).toByte() }
        val vPlane = ByteArray(uvSize) { (it + 200).toByte() }

        val frame = YuvFrame(
            yPlane = yPlane,
            uPlane = uPlane,
            vPlane = vPlane,
            yRowStride = w,
            uvRowStride = uvW,
            uvPixelStride = 1,
            width = w,
            height = h,
            timestampNs = 0L,
        )

        val result = H264Encoder.convertToI420(frame, ySize + uvSize * 2)
        assertEquals(ySize + uvSize * 2, result.size)

        // Y plane should be copied as-is
        for (i in 0 until ySize) {
            assertEquals("Y[$i]", yPlane[i], result[i])
        }

        // U plane
        for (i in 0 until uvSize) {
            assertEquals("U[$i]", uPlane[i], result[ySize + i])
        }

        // V plane
        for (i in 0 until uvSize) {
            assertEquals("V[$i]", vPlane[i], result[ySize + uvSize + i])
        }
    }

    @Test
    fun `convertToI420 with interleaved UV (pixelStride=2)`() {
        val w = 4
        val h = 4
        val ySize = w * h
        val uvW = w / 2
        val uvH = h / 2
        val uvSize = uvW * uvH

        val yPlane = ByteArray(ySize) { 0x10 }

        // Interleaved UV: U0 V0 U1 V1 (pixelStride=2, rowStride=w)
        val uPlane = ByteArray(uvH * w) // rowStride = w, pixelStride = 2
        val vPlane = ByteArray(uvH * w)
        for (row in 0 until uvH) {
            for (col in 0 until uvW) {
                val idx = row * w + col * 2
                uPlane[idx] = (row * uvW + col + 50).toByte()
                vPlane[idx] = (row * uvW + col + 150).toByte()
            }
        }

        val frame = YuvFrame(
            yPlane = yPlane,
            uPlane = uPlane,
            vPlane = vPlane,
            yRowStride = w,
            uvRowStride = w,
            uvPixelStride = 2,
            width = w,
            height = h,
            timestampNs = 0L,
        )

        val result = H264Encoder.convertToI420(frame, ySize + uvSize * 2)
        assertEquals(ySize + uvSize * 2, result.size)

        // Verify U and V planes are correctly de-interleaved
        var uIdx = ySize
        var vIdx = ySize + uvSize
        for (row in 0 until uvH) {
            for (col in 0 until uvW) {
                val expected_u = (row * uvW + col + 50).toByte()
                val expected_v = (row * uvW + col + 150).toByte()
                assertEquals("U[${row},${col}]", expected_u, result[uIdx++])
                assertEquals("V[${row},${col}]", expected_v, result[vIdx++])
            }
        }
    }

    @Test
    fun `convertToI420 with row stride padding`() {
        val w = 4
        val h = 2
        val yRowStride = 8 // padded
        val ySize = w * h

        // Y plane with padding: each row has 4 real bytes + 4 padding bytes
        val yPlane = ByteArray(yRowStride * h)
        for (row in 0 until h) {
            for (col in 0 until w) {
                yPlane[row * yRowStride + col] = ((row * w + col) + 1).toByte()
            }
            // Padding bytes are 0
        }

        val uvW = w / 2
        val uvH = h / 2
        val uvSize = uvW * uvH
        val uPlane = ByteArray(uvSize) { 0x55 }
        val vPlane = ByteArray(uvSize) { 0xAA.toByte() }

        val frame = YuvFrame(
            yPlane = yPlane,
            uPlane = uPlane,
            vPlane = vPlane,
            yRowStride = yRowStride,
            uvRowStride = uvW,
            uvPixelStride = 1,
            width = w,
            height = h,
            timestampNs = 0L,
        )

        val result = H264Encoder.convertToI420(frame, ySize + uvSize * 2)

        // Verify Y plane strips padding
        for (row in 0 until h) {
            for (col in 0 until w) {
                val expected = ((row * w + col) + 1).toByte()
                assertEquals("Y[${row},${col}]", expected, result[row * w + col])
            }
        }
    }

    @Test
    fun `convertToI420 output size is correct`() {
        val w = 1280
        val h = 720
        val expectedSize = w * h + (w / 2) * (h / 2) * 2 // Y + U + V

        val frame = YuvFrame(
            yPlane = ByteArray(w * h),
            uPlane = ByteArray((w / 2) * (h / 2)),
            vPlane = ByteArray((w / 2) * (h / 2)),
            yRowStride = w,
            uvRowStride = w / 2,
            uvPixelStride = 1,
            width = w,
            height = h,
            timestampNs = 0L,
        )

        val result = H264Encoder.convertToI420(frame, expectedSize)
        assertEquals(expectedSize, result.size)
    }
}
