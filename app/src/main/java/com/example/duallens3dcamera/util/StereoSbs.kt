package com.example.duallens3dcamera.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Core
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size as CvSize
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

/**
 * Stereo SBS helper:
 * - SBS output: LEFT = aligned/cropped ultrawide, RIGHT = wide (unchanged geometry)
 * - Alignment: Calib3d.estimateAffinePartial2D + RANSAC (rotation+scale+translation only)
 * - Feature detection restricted to inner 80% of ultrawide (mask) to avoid margin junk
 * - Output JPEG quality = 100
 *
 * Returns SBS JPEG bytes; caller saves to MediaStore + writes EXIF date taken.
 */
object StereoSbs {
    private const val TAG = "StereoSbs"

    @Volatile private var lastGoodAffine6: DoubleArray? = null

    // report alignments params or not
    private const val DEBUG_AFFINE_LOGS = true

    // prevents the 1x affine from being reused right after a zoom-mode flip
    fun resetLastGoodAffine() {
        lastGoodAffine6 = null
    }

    private fun logAffine(label: String, affine: Mat) {
        if (!DEBUG_AFFINE_LOGS) return
        val m = DoubleArray(6)
        affine.get(0, 0, m)
        val scale = Math.hypot(m[0], m[3])
        val thetaDeg = Math.atan2(m[3], m[0]) * 180.0 / Math.PI
        val tx = m[2]
        val ty = m[5]
        Log.i(TAG, "Affine $label: scale=$scale thetaDeg=$thetaDeg tx=$tx ty=$ty")
    }

    private fun storeLastGoodAffine(affine: Mat) {
        val arr = DoubleArray(6)
        affine.get(0, 0, arr)
        lastGoodAffine6 = arr
    }

    private fun loadLastGoodAffine(): Mat? {
        val arr = lastGoodAffine6 ?: return null
        val m = Mat(2, 3, CvType.CV_64F)
        m.put(0, 0, *arr)
        return m
    }

    private val triedInit = AtomicBoolean(false)
    private val openCvReady = AtomicBoolean(false)

    private fun isAffinePlausible(
        affine: Mat,
        ultraW: Int,
        ultraH: Int,
        wideW: Int,
        wideH: Int,
        expectedScale: Double
    ): Boolean {
        if (affine.empty() || affine.rows() != 2 || affine.cols() != 3) return false

        val m = DoubleArray(6)
        affine.get(0, 0, m)

        // reject NaN/Inf
        if (m.any { it.isNaN() || it.isInfinite() }) return false

        val a00 = m[0]
        val a01 = m[1]
        val tx  = m[2]
        val a10 = m[3]
        val a11 = m[4]
        val ty  = m[5]

        // scale + rotation sanity
        val scale = Math.hypot(a00, a10) // sqrt(a00^2 + a10^2)
        val thetaDeg = Math.atan2(a10, a00) * 180.0 / Math.PI

        // ExpectedScale ~= 1 / ultra3aFraction (e.g. 1/0.5 = 2.0)
        // Allow some room but not insane values.
        val minScale = expectedScale * 0.75
        val maxScale = expectedScale * 1.35
        if (scale < minScale || scale > maxScale) return false

        // The two rear cameras shouldn’t differ by big rotation.
        if (abs(thetaDeg) > 15.0) return false

        // center mapping sanity (should land roughly inside/near the wide frame)
        val cxU = ultraW / 2.0
        val cyU = ultraH / 2.0
        val cxMapped = a00 * cxU + a01 * cyU + tx
        val cyMapped = a10 * cxU + a11 * cyU + ty

        val marginX = wideW * 0.35
        val marginY = wideH * 0.35
        if (cxMapped < -marginX || cxMapped > wideW + marginX) return false
        if (cyMapped < -marginY || cyMapped > wideH + marginY) return false

        // Strong check: the wide frame corners should back-project inside the ultrawide image.
        val det = a00 * a11 - a01 * a10
        if (abs(det) < 1e-8) return false

        val inv00 =  a11 / det
        val inv01 = -a01 / det
        val inv10 = -a10 / det
        val inv11 =  a00 / det

        fun backProject(xw: Double, yw: Double): Pair<Double, Double> {
            val x = xw - tx
            val y = yw - ty
            val xu = inv00 * x + inv01 * y
            val yu = inv10 * x + inv11 * y
            return Pair(xu, yu)
        }

        val marginU = 0.08 * min(ultraW.toDouble(), ultraH.toDouble()) // ~8% margin
        val corners = arrayOf(
            backProject(0.0, 0.0),
            backProject(wideW.toDouble(), 0.0),
            backProject(0.0, wideH.toDouble()),
            backProject(wideW.toDouble(), wideH.toDouble())
        )

        for ((xu, yu) in corners) {
            if (xu < -marginU || xu > ultraW + marginU) return false
            if (yu < -marginU || yu > ultraH + marginU) return false
        }

        return true
    }


    private fun ensureOpenCv(): Boolean {
        if (openCvReady.get()) return true
        if (!triedInit.compareAndSet(false, true)) return openCvReady.get()

        return try {
            val ok = OpenCVLoader.initDebug()
            openCvReady.set(ok)
            if (!ok) Log.e(TAG, "OpenCVLoader.initDebug() returned false")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "OpenCV init failed: ${t.message}", t)
            openCvReady.set(false)
            false
        }
    }

    private fun readExifOrientation(jpeg: ByteArray): Int {
        return try {
            ByteArrayInputStream(jpeg).use { ins ->
                val exif = ExifInterface(ins)
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    /**
     * Applies EXIF orientation to a Mat (BGR).
     * Returns a Mat that is "upright" in pixel space.
     * Releases the input Mat if a new Mat is created.
     */
    private fun applyExifOrientationBgr(src: Mat, orientation: Int): Mat {
        // Treat UNDEFINED as NORMAL for our purposes.
        if (orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        ) {
            return src
        }

        val dst = Mat()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE)
            ExifInterface.ORIENTATION_ROTATE_180 -> Core.rotate(src, dst, Core.ROTATE_180)
            ExifInterface.ORIENTATION_ROTATE_270 -> Core.rotate(src, dst, Core.ROTATE_90_COUNTERCLOCKWISE)

            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Core.flip(src, dst, 1)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> Core.flip(src, dst, 0)

            // Rare for back camera, but implemented for completeness:
            ExifInterface.ORIENTATION_TRANSPOSE -> { // mirror across main diagonal
                Core.transpose(src, dst)
                Core.flip(dst, dst, 1)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> { // mirror across anti-diagonal
                Core.transpose(src, dst)
                Core.flip(dst, dst, 0)
            }

            else -> {
                // Unknown: fall back to no-op
                src.copyTo(dst)
            }
        }

        src.release()
        return dst
    }

    /**
     * Emulate a 2x zoomed ultrawide for alignment WITHOUT changing camera capture:
     *  - center-crop to [cropFraction] of width/height (0.5 => 2x)
     *  - resize back to the original dimensions
     *
     * This keeps output dimensions identical (important for the existing expectedScale gates),
     * but narrows FoV like a real 2x zoom.
     */
    private fun centerCropThenResizeBackBgr(srcBgr: Mat, cropFraction: Float = 0.50f): Mat {
        val f = cropFraction.coerceIn(0.05f, 1.0f)
        if (f >= 0.999f) return srcBgr

        val outW = srcBgr.cols()
        val outH = srcBgr.rows()

        val cropW = max(1, (outW * f).toInt())
        val cropH = max(1, (outH * f).toInt())
        val x0 = ((outW - cropW) / 2).coerceAtLeast(0)
        val y0 = ((outH - cropH) / 2).coerceAtLeast(0)

        val roi = Rect(x0, y0, cropW, cropH)

        val cropped = srcBgr.submat(roi).clone()
        srcBgr.release()

        val resized = Mat()
        Imgproc.resize(
            cropped,
            resized,
            CvSize(outW.toDouble(), outH.toDouble()),
            0.0,
            0.0,
            Imgproc.INTER_LINEAR
        )
        cropped.release()
        return resized
    }


    /**
     * Creates an SBS JPEG:
     *   LEFT  = aligned/cropped ultrawide
     *   RIGHT = wide (not warped/scaled/cropped)
     *
     * @param wideRightJpeg wide JPEG bytes (RIGHT side of SBS)
     * @param ultraLeftJpeg ultrawide JPEG bytes (LEFT side of SBS)
     * @param ultraInnerFractionForFeatures 0.80 => detect features only in inner 80% of ultrawide
     * @param fallbackUltraOverlapFraction only used if ORB/RANSAC fails;
     *        passing the logical zoom-out lower bound (e.g. ~0.5) is a decent heuristic.
     * @param maxOutputDim cap decode size to avoid OOM on 50MP devices. Set Int.MAX_VALUE to try full res.
     * @param featureMaxDim max dimension for feature matching working images (speed knob)
     */
    fun alignAndCreateSbsJpegBytes(
        wideRightJpeg: ByteArray,
        ultraLeftJpeg: ByteArray,
        zoom2xEnabled: Boolean = false,
        // 80% is important because on pixel 7, the UW is ~.7x and we need some margin
        ultraInnerFractionForFeatures: Float = 0.80f,
        fallbackUltraOverlapFraction: Float = 0.70f,
        maxOutputDim: Int = 6144,
        // featureMaxDim increases the “working resolution” for feature matching without changing
        // the final SBS output size
        featureMaxDim: Int = 1920, // 1280 // 1600
        jpegQuality: Int = 100
    ): ByteArray? {
        if (!ensureOpenCv()) return null

        val wideExifOri = readExifOrientation(wideRightJpeg)
        val ultraExifOri = readExifOrientation(ultraLeftJpeg)

        val wideBmp = decodeJpegSafely(wideRightJpeg, maxOutputDim) ?: return null
        val ultraBmp = decodeJpegSafely(ultraLeftJpeg, maxOutputDim) ?: run {
            wideBmp.recycle()
            return null
        }

        var wideRgba: Mat? = null
        var ultraRgba: Mat? = null
        var wideBgr: Mat? = null
        var ultraBgr: Mat? = null
        var wideGray: Mat? = null
        var ultraGray: Mat? = null

        var wideGraySmall: Mat? = null
        var ultraGraySmall: Mat? = null

        var alignedUltra: Mat? = null
        var sbs: Mat? = null

        var affineOutToRelease: Mat? = null

        try {

            // Bitmap -> Mat (RGBA)
            wideRgba = Mat()
            ultraRgba = Mat()
            Utils.bitmapToMat(wideBmp, wideRgba)
            Utils.bitmapToMat(ultraBmp, ultraRgba)

            // RGBA -> BGR (3ch) (less memory than 4ch)
            wideBgr = Mat()
            ultraBgr = Mat()
            Imgproc.cvtColor(wideRgba, wideBgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(ultraRgba, ultraBgr, Imgproc.COLOR_RGBA2BGR)

            // Apply EXIF orientation in pixel space so SBS is upright without relying on EXIF rotation.
            wideBgr = applyExifOrientationBgr(requireNotNull(wideBgr), wideExifOri)
            ultraBgr = applyExifOrientationBgr(requireNotNull(ultraBgr), ultraExifOri)

            // If 2x mode is enabled, emulate a 2x ultrawide by center-cropping and resizing back
            // (keeps dimensions the same, but narrows FoV).
            if (zoom2xEnabled) {
                Log.i(TAG, "2x mode: pre-cropping ultrawide to 50% and resizing back before alignment")
                ultraBgr = centerCropThenResizeBackBgr(requireNotNull(ultraBgr), cropFraction = 0.50f)
            }

            // BGR -> Gray for features
            wideGray = Mat()
            ultraGray = Mat()
            Imgproc.cvtColor(wideBgr, wideGray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.cvtColor(ultraBgr, ultraGray, Imgproc.COLOR_BGR2GRAY)

            // Optional uniform downscale for speed (same factor for both)
            val scaleForFeatures = computeUniformScaleForMaxDim(
                maxDim = featureMaxDim,
                w1 = wideGray.cols(),
                h1 = wideGray.rows(),
                w2 = ultraGray.cols(),
                h2 = ultraGray.rows()
            )

            if (scaleForFeatures < 0.999) {
                val newWideW = max(1, (wideGray.cols() * scaleForFeatures).toInt())
                val newWideH = max(1, (wideGray.rows() * scaleForFeatures).toInt())
                val newUltraW = max(1, (ultraGray.cols() * scaleForFeatures).toInt())
                val newUltraH = max(1, (ultraGray.rows() * scaleForFeatures).toInt())

                wideGraySmall = Mat()
                ultraGraySmall = Mat()
                Imgproc.resize(wideGray, wideGraySmall, CvSize(newWideW.toDouble(), newWideH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                Imgproc.resize(ultraGray, ultraGraySmall, CvSize(newUltraW.toDouble(), newUltraH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            } else {
                wideGraySmall = wideGray
                ultraGraySmall = ultraGray
            }

            val affineSmall = estimateUltraToWideAffineRansac(
                ultraGray = requireNotNull(ultraGraySmall),
                wideGray = requireNotNull(wideGraySmall),
                ultraInnerFraction = ultraInnerFractionForFeatures.coerceIn(0.50f, 0.95f)
            )

            // Expected scale from logical zoom-out overlap fraction (ultra3aFraction).
            val expectedScale = 1.0 / fallbackUltraOverlapFraction.coerceIn(0.20f, 0.95f).toDouble()

            // Convert affine from feature-scale coords to full-res coords if needed.
            var candidate: Mat? = if (affineSmall != null && !affineSmall.empty() && scaleForFeatures < 0.999) {
                val a = Mat()
                affineSmall.convertTo(a, CvType.CV_64F)
                val m = DoubleArray(6)
                a.get(0, 0, m)
                m[2] /= scaleForFeatures
                m[5] /= scaleForFeatures
                a.put(0, 0, *m)
                try { affineSmall.release() } catch (_: Exception) {}
                a
            } else {
                affineSmall
            }

            val wideW = wideBgr.cols()
            val wideH = wideBgr.rows()
            val ultraW = ultraBgr.cols()
            val ultraH = ultraBgr.rows()

            val affineOut: Mat = if (candidate != null && isAffinePlausible(candidate, ultraW, ultraH, wideW, wideH, expectedScale)) {
                logAffine("accepted(candidate)", candidate)
                storeLastGoodAffine(candidate)
                candidate
            } else {
                candidate?.release()

                // Best fallback: reuse last good affine (very stable across frames on a fixed phone).
                val last = loadLastGoodAffine()
                if (last != null && isAffinePlausible(last, ultraW, ultraH, wideW, wideH, expectedScale)) {
                    Log.w(TAG, "Using lastGoodAffine fallback (current affine invalid)")
                    logAffine("fallback(lastGood)", last)
                    last
                } else {
                    last?.release()
                    Log.w(TAG, "Using centered fallback affine (no good matches and no lastGoodAffine)")
                    val fb = fallbackCenteredScaleAffine(
                        ultraWidth = ultraW,
                        ultraHeight = ultraH,
                        wideWidth = wideW,
                        wideHeight = wideH,
                        overlapFraction = fallbackUltraOverlapFraction
                    )
                    logAffine("fallback(centered)", fb)
                    fb
                }
            }
            affineOutToRelease = affineOut

            // Warp ultrawide into wide coordinates (this inherently gives the correct crop)
            alignedUltra = Mat()
            Imgproc.warpAffine(
                ultraBgr,
                alignedUltra,
                affineOut,
                CvSize(wideBgr.cols().toDouble(), wideBgr.rows().toDouble()),
                Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT,
                Scalar(0.0, 0.0, 0.0)
            )
            // since we only need the affine until warpAffine() runs,
            // we can just release right after warping
            runCatching { affineOutToRelease.release() }

            // Compose SBS: LEFT = aligned ultrawide, RIGHT = wide (unchanged geometry)
//            val wideW = wideBgr.cols()
//            val wideH = wideBgr.rows()
            sbs = Mat(wideH, wideW * 2, wideBgr.type())

            val leftRoi = sbs.submat(Rect(0, 0, wideW, wideH))
            val rightRoi = sbs.submat(Rect(wideW, 0, wideW, wideH))
            alignedUltra.copyTo(leftRoi)
            wideBgr.copyTo(rightRoi)
            leftRoi.release()
            rightRoi.release()

            // Encode SBS to JPEG @ quality=100
            val outBytes = MatOfByte()
            val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, jpegQuality.coerceIn(0, 100))
            val ok = Imgcodecs.imencode(".jpg", sbs, outBytes, params)
            if (!ok) {
                Log.e(TAG, "Imgcodecs.imencode returned false")
                return null
            }
            return outBytes.toArray()
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OOM while creating SBS (lower maxOutputDim?): ${oom.message}")
            return null
        } catch (t: Throwable) {
            Log.e(TAG, "SBS creation failed: ${t.message}", t)
            return null
        } finally {
            if (wideGraySmall !== wideGray) try { wideGraySmall?.release() } catch (_: Exception) {}
            if (ultraGraySmall !== ultraGray) try { ultraGraySmall?.release() } catch (_: Exception) {}

            try { wideGray?.release() } catch (_: Exception) {}
            try { ultraGray?.release() } catch (_: Exception) {}
            try { wideBgr?.release() } catch (_: Exception) {}
            try { ultraBgr?.release() } catch (_: Exception) {}
            try { wideRgba?.release() } catch (_: Exception) {}
            try { ultraRgba?.release() } catch (_: Exception) {}
            try { alignedUltra?.release() } catch (_: Exception) {}
            try { sbs?.release() } catch (_: Exception) {}

            try { wideBmp.recycle() } catch (_: Exception) {}
            try { ultraBmp.recycle() } catch (_: Exception) {}
        }
    }

    // -------------------- Alignment internals --------------------

    private fun estimateUltraToWideAffineRansac(
        ultraGray: Mat,
        wideGray: Mat,
        ultraInnerFraction: Float,
        // More matches / stability: ORB nFeatures
        nFeatures: Int = 2000, // 900
        // ratioTest -> Lower = stricter = fewer wrong affines
        // (but may reduce matches in low-texture scenes)
        ratioTest: Float = 0.70f, // 0.75f
        // ransacReprojThresholdPx influences how “tight” inliers must be during RANSAC
        ransacReprojThresholdPx: Double = 2.0, // 3.0 // if decreasing, increase maxIters too
        maxIters: Long = 3000, // 2000
        confidence: Double = 0.99,
        refineIters: Long = 20 // 10
    ): Mat? {
        val orb = ORB.create(nFeatures)

        // Mask only the inner 80% (or whatever was passed) of the ULTRAWIDE image.
        val mask = Mat.zeros(ultraGray.size(), CvType.CV_8U)
        val marginX = ((1.0f - ultraInnerFraction) * ultraGray.cols() / 2.0f).toInt().coerceAtLeast(1)
        val marginY = ((1.0f - ultraInnerFraction) * ultraGray.rows() / 2.0f).toInt().coerceAtLeast(1)
        val x0 = marginX
        val y0 = marginY
        val x1 = (ultraGray.cols() - marginX - 1).coerceAtLeast(x0 + 1)
        val y1 = (ultraGray.rows() - marginY - 1).coerceAtLeast(y0 + 1)
        Imgproc.rectangle(mask, Point(x0.toDouble(), y0.toDouble()), Point(x1.toDouble(), y1.toDouble()), Scalar(255.0), -1)

        val kpUltra = MatOfKeyPoint()
        val kpWide = MatOfKeyPoint()
        val descUltra = Mat()
        val descWide = Mat()

        try {
            orb.detectAndCompute(ultraGray, mask, kpUltra, descUltra)
            orb.detectAndCompute(wideGray, Mat(), kpWide, descWide)

            if (descUltra.empty() || descWide.empty()) return null

            val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
            val knnMatches = ArrayList<MatOfDMatch>()
            matcher.knnMatch(descUltra, descWide, knnMatches, 2)

            val good = ArrayList<DMatch>(knnMatches.size)
            for (m in knnMatches) {
                val arr = m.toArray()
                if (arr.size < 2) continue
                val best = arr[0]
                val second = arr[1]
                if (best.distance < ratioTest * second.distance) {
                    good.add(best)
                }
            }
            if (good.size < 8) return null

            good.sortBy { it.distance }
            val capped = good.take(min(good.size, 250))

            val ultraPts = ArrayList<Point>(capped.size)
            val widePts = ArrayList<Point>(capped.size)

            val ultraKps = kpUltra.toArray()
            val wideKps = kpWide.toArray()

            for (dm in capped) {
                ultraPts.add(ultraKps[dm.queryIdx].pt)
                widePts.add(wideKps[dm.trainIdx].pt)
            }

            val from = MatOfPoint2f(*ultraPts.toTypedArray())
            val to = MatOfPoint2f(*widePts.toTypedArray())
            val inliers = Mat()

            val affine = Calib3d.estimateAffinePartial2D(
                from,
                to,
                inliers,
                Calib3d.RANSAC,
                ransacReprojThresholdPx,
                maxIters,
                confidence,
                refineIters
            )

            val inlierCount = try { Core.countNonZero(inliers) } catch (_: Exception) { -1 }
//            if (affine.empty() || inlierCount in 0..5) return null // too permissive
            if (affine.empty() || inlierCount < 12) return null // less permissive

            return affine
        } finally {
            try { mask.release() } catch (_: Exception) {}
            try { kpUltra.release() } catch (_: Exception) {}
            try { kpWide.release() } catch (_: Exception) {}
            try { descUltra.release() } catch (_: Exception) {}
            try { descWide.release() } catch (_: Exception) {}
        }
    }

    private fun fallbackCenteredScaleAffine(
        ultraWidth: Int,
        ultraHeight: Int,
        wideWidth: Int,
        wideHeight: Int,
        overlapFraction: Float
    ): Mat {
        val frac = overlapFraction.coerceIn(0.20f, 0.95f)
        val scale = 1.0 / frac

        val cxU = ultraWidth / 2.0
        val cyU = ultraHeight / 2.0
        val cxW = wideWidth / 2.0
        val cyW = wideHeight / 2.0

        val tx = cxW - scale * cxU
        val ty = cyW - scale * cyU

        val m = Mat(2, 3, CvType.CV_64F)
        m.put(0, 0, scale, 0.0, tx)
        m.put(1, 0, 0.0, scale, ty)
        return m
    }

    // -------------------- decode + scaling helpers --------------------

    private fun decodeJpegSafely(jpeg: ByteArray, maxDim: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null

            val sample = computeInSampleSize(w, h, maxDim)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun computeInSampleSize(srcW: Int, srcH: Int, maxDim: Int): Int {
        if (maxDim <= 0 || maxDim == Int.MAX_VALUE) return 1
        var sample = 1
        var w = srcW
        var h = srcH
        while (max(w, h) > maxDim) {
            sample *= 2
            w /= 2
            h /= 2
            if (sample >= 128) break
        }
        return sample.coerceAtLeast(1)
    }

    private fun computeUniformScaleForMaxDim(
        maxDim: Int,
        w1: Int,
        h1: Int,
        w2: Int,
        h2: Int
    ): Double {
        if (maxDim <= 0 || maxDim == Int.MAX_VALUE) return 1.0
        val m = max(max(w1, h1), max(w2, h2))
        if (m <= 0) return 1.0
        return min(1.0, maxDim.toDouble() / m.toDouble())
    }
}
