package com.example.duallens3dcamera.image

import android.graphics.ImageFormat
import android.media.Image

/**
 * Convert an [Image] in [ImageFormat.YUV_420_888] format to an NV21 byte array.
 *
 * NV21 layout:
 *   - Y plane: width*height bytes
 *   - Interleaved VU plane: width*height/2 bytes
 */
fun Image.toNv21(): ByteArray {
    require(format == ImageFormat.YUV_420_888) {
        "Expected YUV_420_888 but was $format"
    }

    val width = width
    val height = height
    val ySize = width * height
    val uvSize = width * height / 2
    val out = ByteArray(ySize + uvSize)

    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    // ---- Y ----
    val yBuffer = yPlane.buffer
    yBuffer.rewind()
    val yRowStride = yPlane.rowStride
    val yPixelStride = yPlane.pixelStride

    var outPos = 0
    if (yPixelStride == 1 && yRowStride == width) {
        yBuffer.get(out, 0, ySize)
        outPos = ySize
    } else {
        val yBytes = ByteArray(yBuffer.remaining())
        yBuffer.get(yBytes)
        for (row in 0 until height) {
            val rowStart = row * yRowStride
            for (col in 0 until width) {
                out[outPos++] = yBytes[rowStart + col * yPixelStride]
            }
        }
    }

    // ---- VU (NV21) ----
    val chromaWidth = (width + 1) / 2
    val chromaHeight = (height + 1) / 2

    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    uBuffer.rewind()
    vBuffer.rewind()

    val uRowStride = uPlane.rowStride
    val vRowStride = vPlane.rowStride
    val uPixelStride = uPlane.pixelStride
    val vPixelStride = vPlane.pixelStride

    val uBytes = ByteArray(uBuffer.remaining())
    val vBytes = ByteArray(vBuffer.remaining())
    uBuffer.get(uBytes)
    vBuffer.get(vBytes)

    for (row in 0 until chromaHeight) {
        val uRowStart = row * uRowStride
        val vRowStart = row * vRowStride
        for (col in 0 until chromaWidth) {
            out[outPos++] = vBytes[vRowStart + col * vPixelStride] // V
            out[outPos++] = uBytes[uRowStart + col * uPixelStride] // U
        }
    }

    return out
}
