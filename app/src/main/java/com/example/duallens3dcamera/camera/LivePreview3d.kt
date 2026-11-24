package com.example.duallens3dcamera.camera

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.example.duallens3dcamera.util.StereoSbs
import com.example.duallens3dcamera.util.YuvMat
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size as CvSize
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * Live 3D preview: two small YUV streams (wide + ultrawide) are paired per frame, the ultrawide is
 * aligned with the last photo's correction (or a centred default), and a side-by-side or red-cyan
 * anaglyph bitmap is produced for an overlay view. Runs on its own thread.
 */
class LivePreview3d(
    wideSize: Size,
    ultraSize: Size,
    /** Same rotation as saved photos, so the preview matches the result. */
    private val rotationDegrees: Int,
    /** Extra rotation to show the result upright in the portrait-locked UI. */
    private val displayRotationDegrees: Int,
    private val wideIsLeft: Boolean,
    private val crossEyed: Boolean,
    private val anaglyph: Boolean,
    /** Default ultrawide->wide scale when no photo alignment is known yet. */
    private val defaultScale: Double,
    private val sink: (Bitmap) -> Unit
) : AutoCloseable {

    companion object {
        private const val TAG = "LivePreview3d"
        private const val MAX_PAIR_SKEW_NS = 25_000_000L

        /** Picks a small 4:3-ish YUV size (about 640 wide) from [sizes]. */
        fun pickSmallSize(sizes: Array<Size>?): Size? {
            if (sizes.isNullOrEmpty()) return null
            val fourThree = sizes.filter { abs(it.width.toDouble() / it.height - 4.0 / 3.0) < 0.02 && it.width in 480..1024 }
            val pool = fourThree.ifEmpty { sizes.filter { it.width in 480..1280 } }.ifEmpty { sizes.toList() }
            return pool.minByOrNull { abs(it.width - 640) }
        }
    }

    private val thread = HandlerThread("LivePreview3d").apply { start() }
    private val handler = Handler(thread.looper)
    private val wideReader = ImageReader.newInstance(wideSize.width, wideSize.height, ImageFormat.YUV_420_888, 3)
    private val ultraReader = ImageReader.newInstance(ultraSize.width, ultraSize.height, ImageFormat.YUV_420_888, 3)
    private var pendingWide: Image? = null
    private var pendingUltra: Image? = null
    private val bitmaps = arrayOfNulls<Bitmap>(2)
    private var bitmapIndex = 0
    @Volatile private var closed = false

    val wideSurface: Surface get() = wideReader.surface
    val ultraSurface: Surface get() = ultraReader.surface

    init {
        StereoSbs.ensureOpenCv()
        wideReader.setOnImageAvailableListener({ r -> onImage(r, isWide = true) }, handler)
        ultraReader.setOnImageAvailableListener({ r -> onImage(r, isWide = false) }, handler)
    }

    private fun onImage(reader: ImageReader, isWide: Boolean) {
        if (closed) return
        val img = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return
        if (isWide) { pendingWide?.close(); pendingWide = img } else { pendingUltra?.close(); pendingUltra = img }
        val w = pendingWide ?: return
        val u = pendingUltra ?: return
        val skew = w.timestamp - u.timestamp
        if (abs(skew) > MAX_PAIR_SKEW_NS) {
            // Drop the older one and wait for its partner.
            if (skew > 0) { u.close(); pendingUltra = null } else { w.close(); pendingWide = null }
            return
        }
        try {
            compose(w, u)
        } catch (e: Exception) {
            Log.w(TAG, "compose failed: ${e.message}")
        } finally {
            w.close(); u.close(); pendingWide = null; pendingUltra = null
        }
    }

    private fun compose(wImg: Image, uImg: Image) {
        var wide = YuvMat.rotate(YuvMat.toBgr(wImg), rotationDegrees)
        var ultra = YuvMat.rotate(YuvMat.toBgr(uImg), rotationDegrees)
        val warped = Mat()
        val out = Mat()
        val rgba = Mat()
        try {
            val m = affineForPreview(wide.cols(), wide.rows(), ultra.cols(), ultra.rows())
            Imgproc.warpAffine(ultra, warped, m, wide.size(), Imgproc.INTER_LINEAR)
            m.release()
            val left = if (wideIsLeft) wide else warped
            val right = if (wideIsLeft) warped else wide
            if (anaglyph) {
                dubois(left, right, out)
            } else {
                Core.hconcat(if (crossEyed) listOf(right, left) else listOf(left, right), out)
            }
            val shown = YuvMat.rotate(out.clone(), displayRotationDegrees)
            Imgproc.cvtColor(shown, rgba, Imgproc.COLOR_BGR2RGBA)
            shown.release()
            val bmp = reuseBitmap(rgba.cols(), rgba.rows())
            Utils.matToBitmap(rgba, bmp)
            if (!closed) sink(bmp)
        } finally {
            wide.release(); ultra.release(); warped.release(); out.release(); rgba.release()
        }
    }

    /** Ultrawide -> wide 2x3 affine for the small preview frames. */
    private fun affineForPreview(wW: Int, wH: Int, uW: Int, uH: Int): Mat {
        val al = StereoSbs.lastAlignment
        val m = Mat(2, 3, CvType.CV_64F)
        if (al != null && al.wideW > 0 && al.ultraW > 0) {
            val rW = wW.toDouble() / al.wideW
            val rU = uW.toDouble() / al.ultraW
            val k = rW / rU
            m.put(0, 0, al.a * k, -al.b * k, al.tx * rW, al.b * k, al.a * k, al.ty * rW)
        } else {
            // Centred default (no stereo window yet). defaultScale is for equal-size frames, so
            // convert it to these preview sizes.
            val s = defaultScale * wW.toDouble() / uW
            m.put(0, 0, s, 0.0, wW / 2.0 - s * uW / 2.0, 0.0, s, wH / 2.0 - s * uH / 2.0)
        }
        return m
    }

    /** Dubois red-cyan anaglyph (same matrices as tools/stereo_fix). */
    private fun dubois(left: Mat, right: Mat, out: Mat) {
        // OpenCV is BGR: build matrices on BGR order, output BGR.
        val ml = Mat(3, 3, CvType.CV_64F).apply {
            // rows = out B, G, R ; cols = in B, G, R
            put(0, 0, -0.005, -0.021, -0.015, -0.016, -0.038, -0.040, 0.176, 0.500, 0.456)
        }
        val mr = Mat(3, 3, CvType.CV_64F).apply {
            put(0, 0, 1.226, -0.113, -0.072, -0.018, 0.734, 0.378, -0.002, -0.088, -0.043)
        }
        val lf = Mat(); val rf = Mat(); val lt = Mat(); val rt = Mat()
        try {
            left.convertTo(lf, CvType.CV_32FC3, 1.0 / 255.0)
            right.convertTo(rf, CvType.CV_32FC3, 1.0 / 255.0)
            Core.transform(lf, lt, ml)
            Core.transform(rf, rt, mr)
            Core.add(lt, rt, lt)
            lt.convertTo(out, CvType.CV_8UC3, 255.0)
        } finally {
            ml.release(); mr.release(); lf.release(); rf.release(); lt.release(); rt.release()
        }
    }

    private fun reuseBitmap(w: Int, h: Int): Bitmap {
        bitmapIndex = 1 - bitmapIndex
        val cur = bitmaps[bitmapIndex]
        if (cur != null && cur.width == w && cur.height == h) return cur
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmaps[bitmapIndex] = b
        return b
    }

    override fun close() {
        closed = true
        handler.post {
            pendingWide?.close(); pendingUltra?.close(); pendingWide = null; pendingUltra = null
            try { wideReader.close() } catch (_: Exception) {}
            try { ultraReader.close() } catch (_: Exception) {}
            thread.quitSafely()
        }
    }
}
