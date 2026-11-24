package com.example.duallens3dcamera.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.opencv.android.OpenCVLoader
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
import org.opencv.features2d.SIFT
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs

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


    internal fun ensureOpenCv(): Boolean {
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
            Imgproc.INTER_CUBIC
        )
        cropped.release()
        return resized
    }

    data class Nv21Frame(
        val nv21: ByteArray,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int = 0
    )

    fun yuvToJpegBytes(
        frame: Nv21Frame,
        jpegQuality: Int = 100,
        maxOutputDim: Int = 6144
    ): ByteArray? {
        if (!ensureOpenCv()) return null

        var bgr: Mat? = null
        try {
            bgr = nv21ToBgrMat(frame.nv21, frame.width, frame.height) ?: return null
            bgr = rotateBgr(bgr!!, frame.rotationDegrees)
            bgr = downscaleIfNeeded(bgr!!, maxOutputDim)
            return encodeJpegBytes(bgr!!, jpegQuality)
        } finally {
            bgr?.release()
        }
    }

    fun alignAndCreateSbsJpegBytes(
        wideRight: Nv21Frame,
        ultraLeft: Nv21Frame,
        zoom2xEnabled: Boolean = false,
        ultraInnerFractionForFeatures: Float = 0.80f,
        fallbackUltraOverlapFraction: Float = 0.66f,
        maxOutputDim: Int = 6144,
        featureMaxDim: Int = 1600,
        orbMaxFeatures: Int = 2000,
        ransacReprojThreshold: Double = 3.0,
        minInliers: Int = 35
    ): ByteArray? {
        if (!ensureOpenCv()) return null

        var wideBgr: Mat? = null
        var ultraBgr: Mat? = null
        var wideGray: Mat? = null
        var ultraGray: Mat? = null
        var wideSmall: Mat? = null
        var ultraSmall: Mat? = null
        var affineSmall: Mat? = null
        var affineFull: Mat? = null
        var ultraAligned: Mat? = null
        var sbs: Mat? = null

        try {
            wideBgr = nv21ToBgrMat(wideRight.nv21, wideRight.width, wideRight.height) ?: return null
            ultraBgr = nv21ToBgrMat(ultraLeft.nv21, ultraLeft.width, ultraLeft.height) ?: return null

            // Rotate to match what JPEG_ORIENTATION used to do.
            wideBgr = rotateBgr(requireNotNull(wideBgr), wideRight.rotationDegrees)
            ultraBgr = rotateBgr(requireNotNull(ultraBgr), ultraLeft.rotationDegrees)

            // Safety downscale to prevent pathological memory usage.
            wideBgr = downscaleIfNeeded(requireNotNull(wideBgr), maxOutputDim)
            ultraBgr = downscaleIfNeeded(requireNotNull(ultraBgr), maxOutputDim)

            // 2x mode: crop ultrawide to central region before alignment.
            // This narrows the UW FoV by ~2x so matching to wide2x reuses the same overlap heuristic.
            if (zoom2xEnabled) {
                Log.i(TAG, "2x mode: center-cropping ultra to simulate ~2x")
                ultraBgr = centerCropThenResizeBackBgr(requireNotNull(ultraBgr), cropFraction = 0.50f)
            }

            val wideW = requireNotNull(wideBgr).cols()
            val wideH = requireNotNull(wideBgr).rows()
            val ultraW = requireNotNull(ultraBgr).cols()
            val ultraH = requireNotNull(ultraBgr).rows()

            wideGray = Mat()
            ultraGray = Mat()
            Imgproc.cvtColor(requireNotNull(wideBgr), wideGray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.cvtColor(requireNotNull(ultraBgr), ultraGray, Imgproc.COLOR_BGR2GRAY)

            val scaleForFeatures = computeUniformScaleForMaxDim(
                maxDim = featureMaxDim,
                w1 = requireNotNull(wideGray).cols(),
                h1 = requireNotNull(wideGray).rows(),
                w2 = requireNotNull(ultraGray).cols(),
                h2 = requireNotNull(ultraGray).rows()
            )

            wideSmall = Mat()
            ultraSmall = Mat()
            if (scaleForFeatures < 0.999) {
                Imgproc.resize(
                    requireNotNull(wideGray),
                    wideSmall,
                    CvSize(
                        requireNotNull(wideGray).cols() * scaleForFeatures,
                        requireNotNull(wideGray).rows() * scaleForFeatures
                    ),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA
                )
                Imgproc.resize(
                    requireNotNull(ultraGray),
                    ultraSmall,
                    CvSize(
                        requireNotNull(ultraGray).cols() * scaleForFeatures,
                        requireNotNull(ultraGray).rows() * scaleForFeatures
                    ),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA
                )
            } else {
                // No downscale needed; copy so the rest of the code can assume wideSmall/ultraSmall are valid.
                requireNotNull(wideGray).copyTo(wideSmall)
                requireNotNull(ultraGray).copyTo(ultraSmall)
            }

            val expectedScale = 1.0 / fallbackUltraOverlapFraction.toDouble()

            // Estimate affine in the downscaled "feature" coordinate space.
            affineSmall = estimateUltraToWideAffineRansac(
                ultraGray = requireNotNull(ultraSmall),
                wideGray = requireNotNull(wideSmall),
                ultraInnerFraction = ultraInnerFractionForFeatures,
                nFeatures = orbMaxFeatures,
                ransacReprojThresholdPx = ransacReprojThreshold,
                minInliers = minInliers
            )

            // Reject obviously-bad affines before scaling up.
            if (affineSmall != null) {
                val ok = isAffinePlausible(
                    affine = requireNotNull(affineSmall),
                    ultraW = requireNotNull(ultraSmall).cols(),
                    ultraH = requireNotNull(ultraSmall).rows(),
                    wideW = requireNotNull(wideSmall).cols(),
                    wideH = requireNotNull(wideSmall).rows(),
                    expectedScale = expectedScale
                )
                if (!ok) {
                    Log.w(TAG, "Rejecting implausible affine from RANSAC; will fallback")
                    try { affineSmall?.release() } catch (_: Exception) {}
                    affineSmall = null
                }
            }

            // Scale affine back up to full-resolution (only translation scales).
            if (affineSmall != null) {
                val m = DoubleArray(6)
                requireNotNull(affineSmall).get(0, 0, m)

                val scaleBack = if (scaleForFeatures > 0.0) (1.0 / scaleForFeatures) else 1.0

                affineFull = Mat(2, 3, CvType.CV_64F)
                requireNotNull(affineFull).put(0, 0, m[0], m[1], m[2] * scaleBack)
                requireNotNull(affineFull).put(1, 0, m[3], m[4], m[5] * scaleBack)

                // Validate in full-res coordinate space too.
                val okFull = isAffinePlausible(
                    affine = requireNotNull(affineFull),
                    ultraW = ultraW,
                    ultraH = ultraH,
                    wideW = wideW,
                    wideH = wideH,
                    expectedScale = expectedScale
                )
                if (!okFull) {
                    Log.w(TAG, "Rejecting implausible full-res affine; will fallback")
                    try { affineFull?.release() } catch (_: Exception) {}
                    affineFull = null
                }
            }

            // Fallback chain: last good affine -> centered scale heuristic
            if (affineFull == null) {
                val last = loadLastGoodAffine()
                if (last != null && isAffinePlausible(last, ultraW, ultraH, wideW, wideH, expectedScale)) {
                    Log.w(TAG, "Using cached lastGoodAffine fallback")
                    affineFull = last
                } else {
                    try { last?.release() } catch (_: Exception) {}
                    Log.w(TAG, "Using centered-scale fallback affine")
                    affineFull = fallbackCenteredScaleAffine(
                        ultraWidth = ultraW,
                        ultraHeight = ultraH,
                        wideWidth = wideW,
                        wideHeight = wideH,
                        overlapFraction = fallbackUltraOverlapFraction
                    )
                }
            } else {
                // Cache the full-res affine for next time.
                storeLastGoodAffine(requireNotNull(affineFull))
            }

            if (DEBUG_AFFINE_LOGS) {
                logAffine("FINAL", requireNotNull(affineFull))
            }

            // Warp ultrawide into wide geometry.
            ultraAligned = Mat()

            // If the affine scale is >1 (and it usually is when matching UW → W FOV),
            // use INTER_CUBIC to prevent softening fine detail / edges
            val m = DoubleArray(6)
            affineFull.get(0, 0, m)
            val scale = kotlin.math.hypot(m[0], m[3]) // approx uniform scale
            val interp = if (scale > 1.01) Imgproc.INTER_CUBIC else Imgproc.INTER_LINEAR

            Imgproc.warpAffine(
                requireNotNull(ultraBgr),
                ultraAligned,
                requireNotNull(affineFull),
                CvSize(wideW.toDouble(), wideH.toDouble()),
                interp,
                Core.BORDER_CONSTANT,
                Scalar(0.0, 0.0, 0.0)
            )

            // Assemble SBS: LEFT = aligned ultra, RIGHT = wide
            sbs = Mat(wideH, wideW * 2, CvType.CV_8UC3)
            requireNotNull(ultraAligned).copyTo(requireNotNull(sbs).submat(0, wideH, 0, wideW))
            requireNotNull(wideBgr).copyTo(requireNotNull(sbs).submat(0, wideH, wideW, wideW * 2))

            return encodeJpegBytes(requireNotNull(sbs), 100)
        } catch (e: Exception) {
            Log.e(TAG, "alignAndCreateSbsJpegBytes(YUV) failed: ${e.message}", e)
            return null
        } finally {
            try { affineSmall?.release() } catch (_: Exception) {}
            try { affineFull?.release() } catch (_: Exception) {}
            try { wideGray?.release() } catch (_: Exception) {}
            try { ultraGray?.release() } catch (_: Exception) {}
            try { wideSmall?.release() } catch (_: Exception) {}
            try { ultraSmall?.release() } catch (_: Exception) {}
            try { ultraAligned?.release() } catch (_: Exception) {}
            try { sbs?.release() } catch (_: Exception) {}
            try { wideBgr?.release() } catch (_: Exception) {}
            try { ultraBgr?.release() } catch (_: Exception) {}
        }
    }

    // -------------------- Stereo-corrected SBS (tools/stereo_fix port) --------------------

    /**
     * Last good photo alignment (ultrawide -> wide similarity plus window shift, in rotated still
     * pixels). The live 3D preview reuses it, scaled to its small frames.
     */
    data class Alignment(
        val a: Double, val b: Double, val tx: Double, val ty: Double,
        val wideW: Int, val wideH: Int, val ultraW: Int, val ultraH: Int
    )

    @Volatile var lastAlignment: Alignment? = null

    /** Last fit with >= 120 inliers, keyed by (wide width, ultra width, ultra height). */
    @Volatile private var lastStrongModel: Pair<StereoAlignMath.Model, Triple<Int, Int, Int>>? = null

    /**
     * Builds a stereo-corrected SBS JPEG, following tools/stereo_fix:
     * SIFT matches -> RANSAC similarity -> vertical-only refinement with ultrawide k1 distortion
     * (StereoAlignMath) -> stereo window -> single initUndistortRectifyMap warp -> Lab histogram
     * colour match -> crop to the shared area -> parallel (L|R) or cross-eyed (R|L) layout.
     *
     * [wideIsLeft] selects the eye assignment. Returns null on failure so callers can fall back
     * to [alignAndCreateSbsJpegBytes].
     */
    fun correctAndCreateSbsJpegBytes(
        wide: Nv21Frame,
        ultra: Nv21Frame,
        wideIsLeft: Boolean,
        crossEyed: Boolean,
        /** true: both views meet at the average colour; false: ultrawide matched to the main lens. */
        colourAverage: Boolean = true,
        windowPercentile: Double = 98.0,
        maxOutputDim: Int = 6144,
        featureWidth: Int = 1600,
        expectedScale: Double = 1.5
    ): ByteArray? {
        if (!ensureOpenCv()) return null
        val mats = ArrayList<Mat>()
        fun <T : Mat> keep(m: T): T { mats.add(m); return m }
        val t0 = System.nanoTime()
        try {
            var wideBgr = nv21ToBgrMat(wide.nv21, wide.width, wide.height) ?: return null
            wideBgr = downscaleIfNeeded(rotateBgr(wideBgr, wide.rotationDegrees), maxOutputDim)
            keep(wideBgr)
            var ultraBgr = nv21ToBgrMat(ultra.nv21, ultra.width, ultra.height) ?: return null
            ultraBgr = downscaleIfNeeded(rotateBgr(ultraBgr, ultra.rotationDegrees), maxOutputDim)
            keep(ultraBgr)
            val wW = wideBgr.cols(); val wH = wideBgr.rows()
            val uW = ultraBgr.cols(); val uH = ultraBgr.rows()

            // 1) Features.
            val matches = siftMatches(wideBgr, ultraBgr, featureWidth) ?: return null
            val (refPts, movPts) = matches
            val n = refPts.size / 2
            if (n < 40) { Log.w(TAG, "corrected SBS: only $n matches"); return null }

            // 2) RANSAC similarity for inliers.
            val from = keep(MatOfPoint2f(*Array(n) { Point(movPts[2 * it], movPts[2 * it + 1]) }))
            val to = keep(MatOfPoint2f(*Array(n) { Point(refPts[2 * it], refPts[2 * it + 1]) }))
            val inl = keep(Mat())
            val init = keep(Calib3d.estimateAffinePartial2D(from, to, inl, Calib3d.RANSAC, 0.03 * uW, 5000, 0.999, 10))
            if (init.empty()) return null
            val inlArr = ByteArray(n); inl.get(0, 0, inlArr)
            val base = BooleanArray(n) { inlArr[it].toInt() != 0 }

            // 3) Vertical-only refinement + distortion.
            val refX = DoubleArray(n) { refPts[2 * it] }; val refY = DoubleArray(n) { refPts[2 * it + 1] }
            val movX = DoubleArray(n) { movPts[2 * it] }; val movY = DoubleArray(n) { movPts[2 * it + 1] }
            val fit = StereoAlignMath.refine(refX, refY, movX, movY, base, uW, uH, fitDistortion = true)
            val fm = fit.model
            val plausible = fm.scale in expectedScale * 0.75..expectedScale * 1.35 && abs(fm.rotationDeg) <= 5.0
            // The lenses are rigid, so a strong earlier fit is better than a weak current one
            // (e.g. a hand right in front of the lenses, or a blank wall).
            val reuse = lastStrongModel?.takeIf { it.second == Triple(wW, uW, uH) }?.first
            val m: StereoAlignMath.Model
            val useMask: BooleanArray
            when {
                plausible && fit.inlierCount >= 60 -> {
                    m = fm; useMask = fit.inliers
                    if (fit.inlierCount >= 120) lastStrongModel = m to Triple(wW, uW, uH)
                }
                reuse != null -> {
                    Log.i(TAG, "corrected SBS: weak fit (inliers=${fit.inlierCount}); reusing last strong alignment")
                    m = reuse; useMask = base
                }
                plausible && fit.inlierCount >= 25 -> { m = fm; useMask = fit.inliers }
                else -> {
                    Log.w(TAG, "corrected SBS: implausible fit scale=${fm.scale} rot=${fm.rotationDeg} inliers=${fit.inlierCount}")
                    return null
                }
            }
            val (cX, cY) = if (m === fm) fit.corrX to fit.corrY else StereoAlignMath.undistort(movX, movY, m.k, m.cx, m.cy, m.r)
            val d = StereoAlignMath.horizontalOffsets(refX, cX, cY, m.a, m.b)
            val masked = d.filterIndexed { i, _ -> useMask[i] }.toDoubleArray()
            val dIn = if (masked.isNotEmpty()) masked else d
            val tx = StereoAlignMath.windowShift(dIn, wideIsLeft, windowPercentile)
            lastAlignment = Alignment(m.a, m.b, tx, m.ty, wW, wH, uW, uH)

            // 4) Symmetric warp: both views take half the scale, angle and shift onto a shared
            //    canvas (StereoAlignMath.symmetricPlan); ultrawide distortion is removed in the
            //    same remap. The main image is already distortion-corrected by the camera.
            val plan = StereoAlignMath.symmetricPlan(m, tx, wW, wH)
            val cW = plan.width; val cH = plan.height
            val canvas = CvSize(cW.toDouble(), cH.toDouble())
            val r = m.r
            val camK = keep(Mat(3, 3, CvType.CV_64F)).apply { put(0, 0, r, 0.0, m.cx, 0.0, r, m.cy, 0.0, 0.0, 1.0) }
            val newK = keep(Mat(3, 3, CvType.CV_64F)).apply { put(0, 0, *plan.ultraNewK) }
            val dist = keep(Mat(1, 5, CvType.CV_64F)).apply { put(0, 0, m.k, 0.0, 0.0, 0.0, 0.0) }
            val map1 = keep(Mat()); val map2 = keep(Mat())
            Calib3d.initUndistortRectifyMap(camK, dist, keep(Mat.eye(3, 3, CvType.CV_64F)), newK,
                canvas, CvType.CV_16SC2, map1, map2)
            val warped = keep(Mat())
            Imgproc.remap(ultraBgr, warped, map1, map2, Imgproc.INTER_CUBIC, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0))
            val ones = keep(Mat(uH, uW, CvType.CV_8UC1, Scalar(255.0)))
            val valid = keep(Mat())
            Imgproc.remap(ones, valid, map1, map2, Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar(0.0))
            map1.release(); map2.release()

            val hw = keep(Mat(2, 3, CvType.CV_64F)).apply { put(0, 0, *plan.wideAffine) }
            val wideW = keep(Mat())
            Imgproc.warpAffine(wideBgr, wideW, hw, canvas, Imgproc.INTER_AREA, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0))
            val wideValid = keep(Mat())
            Imgproc.warpAffine(keep(Mat(wH, wW, CvType.CV_8UC1, Scalar(255.0))), wideValid, hw, canvas,
                Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar(0.0))
            Core.bitwise_and(valid, wideValid, valid)
            Imgproc.erode(valid, valid, keep(Imgproc.getStructuringElement(Imgproc.MORPH_RECT, CvSize(5.0, 5.0))))

            // 5) Colour: both to the average (default) or the ultrawide to the main lens, in Lab.
            if (colourAverage) colourBalanceLab(wideW, warped, valid) else colourMatchLab(warped, wideW, valid)

            // 6) Crop to the largest fully valid rectangle (computed on a 1/8 mask, then scaled).
            val rect = validCropRect(valid, cW, cH) ?: return null
            val wideC = wideW.submat(rect)
            val ultraC = warped.submat(rect)
            val left = if (wideIsLeft) wideC else ultraC
            val right = if (wideIsLeft) ultraC else wideC
            val first = if (crossEyed) right else left
            val second = if (crossEyed) left else right
            val sbs = keep(Mat(rect.height, rect.width * 2, CvType.CV_8UC3))
            first.copyTo(sbs.submat(0, rect.height, 0, rect.width))
            second.copyTo(sbs.submat(0, rect.height, rect.width, rect.width * 2))

            Log.i(TAG, "corrected SBS: matches=$n inliers=${fit.inlierCount} scale=${"%.4f".format(m.scale)} " +
                "rot=${"%.3f".format(m.rotationDeg)} ty=${"%.1f".format(m.ty)} k=${"%.4f".format(m.k)} " +
                "tx=${"%.1f".format(tx)} canvas=${cW}x$cH crop=$rect colourAvg=$colourAverage " +
                "wideLeft=$wideIsLeft cross=$crossEyed " +
                "time=${(System.nanoTime() - t0) / 1_000_000}ms")
            return encodeJpegBytes(sbs, 95)
        } catch (e: Exception) {
            Log.e(TAG, "corrected SBS failed: ${e.message}", e)
            return null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "corrected SBS out of memory", e)
            return null
        } finally {
            for (mat in mats) try { mat.release() } catch (_: Exception) {}
        }
    }

    /** SIFT + FLANN ratio-test matches; returns (refXY, movXY) interleaved in full-res pixels. */
    internal fun siftMatches(ref: Mat, mov: Mat, work: Int): Pair<DoubleArray, DoubleArray>? {
        val clahe = Imgproc.createCLAHE(2.0, CvSize(8.0, 8.0))
        fun prep(img: Mat): Pair<Mat, Double> {
            val s = work.toDouble() / img.cols()
            val small = Mat(); val gray = Mat()
            Imgproc.resize(img, small, CvSize(img.cols() * s, img.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_BGR2GRAY)
            small.release()
            clahe.apply(gray, gray)
            return gray to s
        }
        val (gR, sR) = prep(ref)
        val (gM, sM) = prep(mov)
        val sift = SIFT.create(6000)
        val kR = MatOfKeyPoint(); val kM = MatOfKeyPoint(); val dR = Mat(); val dM = Mat()
        try {
            sift.detectAndCompute(gR, Mat(), kR, dR)
            sift.detectAndCompute(gM, Mat(), kM, dM)
            if (dR.empty() || dM.empty()) return null
            val matcher = DescriptorMatcher.create(DescriptorMatcher.FLANNBASED)
            val knn = ArrayList<MatOfDMatch>()
            matcher.knnMatch(dM, dR, knn, 2)
            val kpR = kR.toArray(); val kpM = kM.toArray()
            val refXY = ArrayList<Double>(); val movXY = ArrayList<Double>()
            for (pair in knn) {
                val arr = pair.toArray()
                if (arr.size == 2 && arr[0].distance < 0.72f * arr[1].distance) {
                    val p = kpR[arr[0].trainIdx].pt; val q = kpM[arr[0].queryIdx].pt
                    refXY.add(p.x / sR); refXY.add(p.y / sR)
                    movXY.add(q.x / sM); movXY.add(q.y / sM)
                }
                pair.release()
            }
            return refXY.toDoubleArray() to movXY.toDoubleArray()
        } finally {
            gR.release(); gM.release(); kR.release(); kM.release(); dR.release(); dM.release()
        }
    }

    /** In-place histogram match of [src] to [ref] per Lab channel, using pixels where [mask] != 0. */
    private fun colourMatchLab(src: Mat, ref: Mat, mask: Mat) {
        val srcLab = Mat(); val refLab = Mat()
        Imgproc.cvtColor(src, srcLab, Imgproc.COLOR_BGR2Lab)
        Imgproc.cvtColor(ref, refLab, Imgproc.COLOR_BGR2Lab)
        val sCh = ArrayList<Mat>(); val rCh = ArrayList<Mat>()
        Core.split(srcLab, sCh); Core.split(refLab, rCh)
        try {
            for (c in 0 until 3) {
                val sHist = histogram(sCh[c], mask)
                val rHist = histogram(rCh[c], mask)
                val lut = StereoAlignMath.matchLinearLut(sHist, rHist)
                val lutMat = Mat(1, 256, CvType.CV_8UC1)
                lutMat.put(0, 0, ByteArray(256) { lut[it].toByte() })
                Core.LUT(sCh[c], lutMat, sCh[c])
                lutMat.release()
            }
            Core.merge(sCh, srcLab)
            Imgproc.cvtColor(srcLab, src, Imgproc.COLOR_Lab2BGR)
        } finally {
            srcLab.release(); refLab.release()
            sCh.forEach { it.release() }; rCh.forEach { it.release() }
        }
    }

    /** In-place: maps both images to the average of their Lab histograms over [mask]. */
    private fun colourBalanceLab(a: Mat, b: Mat, mask: Mat) {
        val la = Mat(); val lb = Mat()
        Imgproc.cvtColor(a, la, Imgproc.COLOR_BGR2Lab)
        Imgproc.cvtColor(b, lb, Imgproc.COLOR_BGR2Lab)
        val ca = ArrayList<Mat>(); val cb = ArrayList<Mat>()
        Core.split(la, ca); Core.split(lb, cb)
        try {
            for (c in 0 until 3) {
                val (lutA, lutB) = StereoAlignMath.averageTargetLuts(histogram(ca[c], mask), histogram(cb[c], mask))
                for ((ch, lut) in listOf(ca[c] to lutA, cb[c] to lutB)) {
                    val lm = Mat(1, 256, CvType.CV_8UC1)
                    lm.put(0, 0, ByteArray(256) { lut[it].toByte() })
                    Core.LUT(ch, lm, ch)
                    lm.release()
                }
            }
            Core.merge(ca, la); Core.merge(cb, lb)
            Imgproc.cvtColor(la, a, Imgproc.COLOR_Lab2BGR)
            Imgproc.cvtColor(lb, b, Imgproc.COLOR_Lab2BGR)
        } finally {
            la.release(); lb.release(); ca.forEach { it.release() }; cb.forEach { it.release() }
        }
    }

    internal fun histogram(channel: Mat, mask: Mat): DoubleArray {
        val hist = Mat()
        Imgproc.calcHist(listOf(channel), MatOfInt(0), mask, hist, MatOfInt(256), org.opencv.core.MatOfFloat(0f, 256f))
        val f = FloatArray(256); hist.get(0, 0, f); hist.release()
        return DoubleArray(256) { f[it].toDouble() }
    }

    /** Largest all-valid rectangle, found on a 1/8 scale mask and scaled back conservatively. */
    internal fun validCropRect(valid: Mat, w: Int, h: Int): Rect? {
        val f = 8
        val sw = w / f; val sh = h / f
        if (sw < 20 || sh < 20) return null
        val small = Mat()
        Imgproc.resize(valid, small, CvSize(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val bytes = ByteArray(sw * sh); small.get(0, 0, bytes); small.release()
        val mask = BooleanArray(sw * sh) { (bytes[it].toInt() and 0xFF) == 255 }
        val rr = StereoAlignMath.largestValidRect(mask, sw, sh)
        val x0 = rr[0] * f; val y0 = rr[1] * f
        val x1 = if (rr[2] == sw) w else rr[2] * f
        val y1 = if (rr[3] == sh) h else rr[3] * f
        if (x1 - x0 < w / 2 || y1 - y0 < h / 2) return null
        return Rect(x0, y0, x1 - x0, y1 - y0)
    }

    private fun nv21ToBgrMat(nv21: ByteArray, width: Int, height: Int): Mat? {
        val expected = width * height * 3 / 2
        if (nv21.size < expected) {
            Log.e(TAG, "NV21 too small: got=${nv21.size} expected>=$expected")
            return null
        }
        val yuv = Mat(height + height / 2, width, CvType.CV_8UC1)
        yuv.put(0, 0, nv21)
        val bgr = Mat()
        Imgproc.cvtColor(yuv, bgr, Imgproc.COLOR_YUV2BGR_NV21)
        yuv.release()
        return bgr
    }

    private fun rotateBgr(src: Mat, rotationDegrees: Int): Mat {
        val deg = ((rotationDegrees % 360) + 360) % 360
        if (deg == 0) return src
        val dst = Mat()
        when (deg) {
            90 -> Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(src, dst, Core.ROTATE_180)
            270 -> Core.rotate(src, dst, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> {
                Log.w(TAG, "Unexpected rotation=$deg; leaving unrotated")
                src.copyTo(dst)
            }
        }
        src.release()
        return dst
    }

    private fun downscaleIfNeeded(src: Mat, maxDim: Int): Mat {
        val w = src.cols()
        val h = src.rows()
        val m = max(w, h)
        if (m <= maxDim) return src
        val scale = maxDim.toDouble() / m.toDouble()
        val dst = Mat()
        Imgproc.resize(src, dst, CvSize(w * scale, h * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        src.release()
        return dst
    }

    private fun encodeJpegBytes(bgr: Mat, quality: Int): ByteArray? {
        val out = MatOfByte()
        val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality.coerceIn(0, 100))
        return try {
            val ok = Imgcodecs.imencode(".jpg", bgr, out, params)
            if (!ok) null else out.toArray()
        } finally {
            params.release()
            out.release()
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
        refineIters: Long = 20, // 10
        minInliers: Int = 35
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
        Imgproc.rectangle(
            mask,
            Point(x0.toDouble(), y0.toDouble()),
            Point(x1.toDouble(), y1.toDouble()),
            Scalar(255.0),
            -1
        )

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
            val capped = good.take(kotlin.math.min(good.size, 250))

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
            if (affine.empty() || inlierCount < minInliers) return null

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
