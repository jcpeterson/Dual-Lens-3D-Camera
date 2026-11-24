package com.example.duallens3dcamera.util

import android.graphics.ImageFormat
import android.media.Image
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.min

/** YUV_420_888 [Image] -> BGR [Mat] conversion that honours crop, row and pixel strides. */
object YuvMat {

    fun toBgr(img: Image): Mat {
        require(img.format == ImageFormat.YUV_420_888) { "Unexpected image format ${img.format}" }
        val crop = img.cropRect
        val w = crop.width() and 1.inv()
        val h = crop.height() and 1.inv()
        val out = ByteArray(w * h * 3 / 2)
        var off = 0
        for (p in 0 until 3) {
            val plane = img.planes[p]
            val buf = plane.buffer
            val rs = plane.rowStride
            val ps = plane.pixelStride
            val sub = if (p == 0) 1 else 2
            val pw = w / sub
            val ph = h / sub
            val x0 = crop.left / sub
            val y0 = crop.top / sub
            val row = ByteArray(rs)
            for (r in 0 until ph) {
                val start = (y0 + r) * rs
                val len = min(rs, buf.limit() - start)
                buf.position(start)
                buf.get(row, 0, len)
                if (ps == 1) {
                    System.arraycopy(row, x0, out, off, pw)
                } else {
                    for (c in 0 until pw) out[off + c] = row[(x0 + c) * ps]
                }
                off += pw
            }
        }
        val yuv = Mat(h * 3 / 2, w, CvType.CV_8UC1)
        yuv.put(0, 0, out)
        val bgr = Mat()
        Imgproc.cvtColor(yuv, bgr, Imgproc.COLOR_YUV2BGR_I420)
        yuv.release()
        return bgr
    }

    /** Rotates clockwise by 0/90/180/270 degrees; releases [m] when a new Mat is returned. */
    fun rotate(m: Mat, degrees: Int): Mat {
        val code = when ((degrees % 360 + 360) % 360) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return m
        }
        val out = Mat()
        Core.rotate(m, out, code)
        m.release()
        return out
    }
}
