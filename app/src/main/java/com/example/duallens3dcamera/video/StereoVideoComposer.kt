package com.example.duallens3dcamera.video

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import com.example.duallens3dcamera.util.StereoAlignMath
import com.example.duallens3dcamera.util.StereoSbs
import com.example.duallens3dcamera.util.YuvMat
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Combines a wide/ultrawide recording pair into one corrected side-by-side 3D MP4.
 * Android port of tools/stereo_fix/stereo_video.py:
 *  1. Analysis pass: decode both clips, keep [SAMPLES] evenly spaced frame pairs, pool their SIFT
 *     matches, fit scale/rotation/vertical offset/ultrawide k1 from vertical errors
 *     (StereoAlignMath), place the stereo window, build one undistort+warp map, the shared crop
 *     and one colour LUT per B/G/R channel.
 *  2. Render pass: decode both clips again, warp + colour the ultrawide, crop both, place
 *     left|right (or right|left), encode H.264 and copy the wide clip's audio.
 */
class StereoVideoComposer(
    private val context: Context,
    private val wideUri: Uri,
    private val ultraUri: Uri,
    private val outUri: Uri,
    private val wideIsLeft: Boolean,
    private val crossEyed: Boolean,
    /** Force half-width SBS (3D TV style). Also used automatically if full width can't be encoded. */
    private val halfWidth: Boolean,
    /** true: both clips meet at their average colour; false: ultrawide matched to the main lens. */
    private val colourAverage: Boolean,
    private val onProgress: (stage: String, fraction: Float) -> Unit,
    private val isCancelled: () -> Boolean
) {
    data class Result(
        val frames: Int, val width: Int, val height: Int, val halfWidth: Boolean,
        val scale: Double, val rotationDeg: Double, val k: Double, val inliers: Int, val millis: Long
    )

    class CancelledException : Exception("cancelled")

    companion object {
        private const val TAG = "StereoVideoComposer"
        private const val SAMPLES = 8
        private const val TIMEOUT_US = 10_000L
    }

    private class Plan(
        val map1: Mat, val map2: Mat, val wideAffine: Mat, val canvas: Size, val crop: Rect,
        val lutUltra: Mat, val lutWide: Mat, val fit: StereoAlignMath.Fit
    )

    fun run(): Result {
        val t0 = System.currentTimeMillis()
        check(StereoSbs.ensureOpenCv()) { "OpenCV not available" }
        val total = max(1, min(countVideoSamples(wideUri), countVideoSamples(ultraUri)))
        val plan = analyse(total)
        try {
            return render(plan, total, t0)
        } finally {
            plan.map1.release(); plan.map2.release(); plan.wideAffine.release()
            plan.lutUltra.release(); plan.lutWide.release()
        }
    }

    // ------------------------------------------------------------------ analysis

    private fun analyse(total: Int): Plan {
        val picks = (0 until SAMPLES).map { (total * (0.1 + 0.8 * it / (SAMPLES - 1))).toInt() }.toSet()
        val wides = ArrayList<Mat>(); val ultras = ArrayList<Mat>()
        FrameDecoder(context, wideUri).use { dw ->
            FrameDecoder(context, ultraUri).use { du ->
                var i = 0
                while (true) {
                    if (isCancelled()) throw CancelledException()
                    val w = dw.next() ?: break
                    val u = du.next()
                    if (u == null) { w.release(); break }
                    if (i in picks) { wides.add(w); ultras.add(u) } else { w.release(); u.release() }
                    i++
                    onProgress("Analysing", 0.25f * i / total)
                }
            }
        }
        try {
            require(wides.isNotEmpty()) { "No frames decoded" }
            val rx = ArrayList<Double>(); val ry = ArrayList<Double>()
            val mx = ArrayList<Double>(); val my = ArrayList<Double>()
            val base = ArrayList<Boolean>()
            for (j in wides.indices) {
                if (isCancelled()) throw CancelledException()
                val (ref, mov) = StereoSbs.siftMatches(wides[j], ultras[j], 1600) ?: continue
                val n = ref.size / 2
                if (n < 20) continue
                val from = MatOfPoint2f(*Array(n) { Point(mov[2 * it], mov[2 * it + 1]) })
                val to = MatOfPoint2f(*Array(n) { Point(ref[2 * it], ref[2 * it + 1]) })
                val inl = Mat()
                val m = Calib3d.estimateAffinePartial2D(from, to, inl, Calib3d.RANSAC, 0.03 * ultras[j].cols(), 5000, 0.999, 10)
                if (!m.empty()) {
                    val b = ByteArray(n); inl.get(0, 0, b)
                    for (q in 0 until n) {
                        rx.add(ref[2 * q]); ry.add(ref[2 * q + 1]); mx.add(mov[2 * q]); my.add(mov[2 * q + 1])
                        base.add(b[q].toInt() != 0)
                    }
                }
                from.release(); to.release(); inl.release(); m.release()
                onProgress("Aligning", 0.25f + 0.1f * (j + 1) / wides.size)
            }
            require(rx.size >= 40) { "Too few matching points (${rx.size}) to align this clip" }

            val uW = ultras[0].cols(); val uH = ultras[0].rows()
            val wW = wides[0].cols(); val wH = wides[0].rows()
            val fit = StereoAlignMath.refine(
                rx.toDoubleArray(), ry.toDoubleArray(), mx.toDoubleArray(), my.toDoubleArray(),
                base.toBooleanArray(), uW, uH, fitDistortion = true
            )
            val md = fit.model
            require(md.scale in 1.0..2.5 && abs(md.rotationDeg) < 5.0 && fit.inlierCount >= 25) {
                "Implausible alignment (scale ${"%.3f".format(md.scale)}, rotation ${"%.2f".format(md.rotationDeg)})"
            }
            val d = StereoAlignMath.horizontalOffsets(rx.toDoubleArray(), fit.corrX, fit.corrY, md.a, md.b)
            val tx = StereoAlignMath.windowShift(d.filterIndexed { i, _ -> fit.inliers[i] }.toDoubleArray(), wideIsLeft)

            // Symmetric: both views take half the scale, angle and shift (StereoAlignMath.symmetricPlan).
            val sp = StereoAlignMath.symmetricPlan(md, tx, wW, wH)
            val canvas = Size(sp.width.toDouble(), sp.height.toDouble())
            val r = md.r
            val camK = Mat(3, 3, CvType.CV_64F).apply { put(0, 0, r, 0.0, md.cx, 0.0, r, md.cy, 0.0, 0.0, 1.0) }
            val newK = Mat(3, 3, CvType.CV_64F).apply { put(0, 0, *sp.ultraNewK) }
            val dist = Mat(1, 5, CvType.CV_64F).apply { put(0, 0, md.k, 0.0, 0.0, 0.0, 0.0) }
            val eye = Mat.eye(3, 3, CvType.CV_64F)
            val map1 = Mat(); val map2 = Mat()
            Calib3d.initUndistortRectifyMap(camK, dist, eye, newK, canvas, CvType.CV_16SC2, map1, map2)
            camK.release(); newK.release(); dist.release(); eye.release()
            val wideAffine = Mat(2, 3, CvType.CV_64F).apply { put(0, 0, *sp.wideAffine) }

            val ones = Mat(uH, uW, CvType.CV_8UC1, Scalar(255.0))
            val valid = Mat()
            Imgproc.remap(ones, valid, map1, map2, Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar(0.0))
            ones.release()
            val onesW = Mat(wH, wW, CvType.CV_8UC1, Scalar(255.0))
            val validW = Mat()
            Imgproc.warpAffine(onesW, validW, wideAffine, canvas, Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar(0.0))
            Core.bitwise_and(valid, validW, valid)
            onesW.release(); validW.release()
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.erode(valid, valid, kernel); kernel.release()
            val crop0 = StereoSbs.validCropRect(valid, sp.width, sp.height) ?: error("Shared area too small")
            // Encoders want sizes in multiples of 16.
            val crop = Rect(crop0.x, crop0.y, crop0.width / 16 * 16, crop0.height / 16 * 16)

            // One LUT per B, G, R channel for each view, from the sampled frames (fast per frame).
            val uHist = Array(3) { DoubleArray(256) }; val wHist = Array(3) { DoubleArray(256) }
            val warped = Mat(); val wideWarped = Mat()
            for (j in wides.indices) {
                Imgproc.remap(ultras[j], warped, map1, map2, Imgproc.INTER_LINEAR)
                Imgproc.warpAffine(wides[j], wideWarped, wideAffine, canvas, Imgproc.INTER_LINEAR)
                val uc = ArrayList<Mat>(); val wc = ArrayList<Mat>()
                Core.split(warped, uc); Core.split(wideWarped, wc)
                for (c in 0 until 3) {
                    val hu = StereoSbs.histogram(uc[c], valid); val hw = StereoSbs.histogram(wc[c], valid)
                    for (v in 0 until 256) { uHist[c][v] += hu[v]; wHist[c][v] += hw[v] }
                }
                uc.forEach { it.release() }; wc.forEach { it.release() }
            }
            warped.release(); wideWarped.release(); valid.release()
            val lutsU: Array<IntArray>; val lutsW: Array<IntArray>
            if (colourAverage) {
                val pairs = Array(3) { StereoAlignMath.averageTargetLuts(wHist[it], uHist[it]) }
                lutsW = Array(3) { pairs[it].first }; lutsU = Array(3) { pairs[it].second }
            } else {
                lutsU = Array(3) { StereoAlignMath.matchLinearLut(uHist[it], wHist[it]) }
                lutsW = Array(3) { IntArray(256) { v -> v } }
            }
            fun lutMat(l: Array<IntArray>) = Mat(1, 256, CvType.CV_8UC3).apply {
                put(0, 0, ByteArray(256 * 3) { l[it % 3][it / 3].toByte() })
            }

            Log.i(TAG, "plan: frames=${wides.size} points=${rx.size} inliers=${fit.inlierCount} " +
                "scale=${"%.4f".format(md.scale)} rot=${"%.3f".format(md.rotationDeg)} k=${"%.4f".format(md.k)} " +
                "tx=${"%.1f".format(tx)} canvas=${sp.width}x${sp.height} crop=$crop colourAvg=$colourAverage")
            return Plan(map1, map2, wideAffine, canvas, crop, lutMat(lutsU), lutMat(lutsW), fit)
        } finally {
            wides.forEach { it.release() }; ultras.forEach { it.release() }
        }
    }

    // ------------------------------------------------------------------ render

    private fun render(plan: Plan, total: Int, t0: Long): Result {
        val eyeW = plan.crop.width; val eyeH = plan.crop.height
        var half = halfWidth
        if (!half && !encoderSupports(eyeW * 2, eyeH)) {
            Log.w(TAG, "Encoder can't do ${eyeW * 2}x$eyeH; using half-width SBS")
            half = true
        }
        val outW = if (half) eyeW else eyeW * 2
        require(encoderSupports(outW, eyeH)) { "No H.264 encoder for ${outW}x$eyeH" }

        val fps = sourceFps(wideUri)
        val bitrate = (outW.toLong() * eyeH * fps * 0.3).toLong().coerceIn(8_000_000L, 80_000_000L).toInt()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, eyeH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps.toInt().coerceAtLeast(1))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val pfd = requireNotNull(context.contentResolver.openFileDescriptor(outUri, "rw")) { "Cannot open output" }
        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val audio = AudioSource.open(context, wideUri)
        var videoTrack = -1; var audioTrack = -1; var muxerStarted = false
        val info = MediaCodec.BufferInfo()

        fun drain(endOfStream: Boolean) {
            while (true) {
                val idx = encoder.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        if (audio != null) audioTrack = muxer.addTrack(audio.format)
                        muxer.start(); muxerStarted = true
                    }
                    idx >= 0 -> {
                        val buf = requireNotNull(encoder.getOutputBuffer(idx))
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer.writeSampleData(videoTrack, buf, info)
                        }
                        encoder.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        var frames = 0
        val warped = Mat(); val wideWarped = Mat()
        val sbs = Mat(eyeH, eyeW * 2, CvType.CV_8UC3); val small = Mat(); val yuv = Mat()
        try {
            FrameDecoder(context, wideUri).use { dw ->
                FrameDecoder(context, ultraUri).use { du ->
                    while (true) {
                        if (isCancelled()) throw CancelledException()
                        val w = dw.next() ?: break
                        val u = du.next()
                        if (u == null) { w.release(); break }
                        val pts = dw.lastPtsUs
                        Imgproc.remap(u, warped, plan.map1, plan.map2, Imgproc.INTER_LINEAR)
                        Core.LUT(warped, plan.lutUltra, warped)
                        Imgproc.warpAffine(w, wideWarped, plan.wideAffine, plan.canvas, Imgproc.INTER_LINEAR)
                        Core.LUT(wideWarped, plan.lutWide, wideWarped)
                        val wc = wideWarped.submat(plan.crop); val uc = warped.submat(plan.crop)
                        val left = if (wideIsLeft) wc else uc
                        val right = if (wideIsLeft) uc else wc
                        (if (crossEyed) right else left).copyTo(sbs.submat(0, eyeH, 0, eyeW))
                        (if (crossEyed) left else right).copyTo(sbs.submat(0, eyeH, eyeW, eyeW * 2))
                        val outMat = if (half) {
                            Imgproc.resize(sbs, small, Size(outW.toDouble(), eyeH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA); small
                        } else sbs
                        Imgproc.cvtColor(outMat, yuv, Imgproc.COLOR_BGR2YUV_I420)
                        wc.release(); uc.release(); w.release(); u.release()

                        var inIdx: Int
                        do {
                            inIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                            if (inIdx < 0) drain(false)
                        } while (inIdx < 0)
                        val img = requireNotNull(encoder.getInputImage(inIdx))
                        writeI420(yuv, outW, eyeH, img)
                        encoder.queueInputBuffer(inIdx, 0, outW * eyeH * 3 / 2, pts, 0)
                        drain(false)
                        frames++
                        onProgress("Rendering", 0.35f + 0.6f * frames / total)
                    }
                }
            }
            var inIdx: Int
            do { inIdx = encoder.dequeueInputBuffer(TIMEOUT_US); if (inIdx < 0) drain(false) } while (inIdx < 0)
            encoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            check(muxerStarted) { "Encoder produced no output" }
            onProgress("Adding sound", 0.97f)
            audio?.copyTo(muxer, audioTrack)
        } finally {
            warped.release(); wideWarped.release(); sbs.release(); small.release(); yuv.release()
            try { encoder.stop() } catch (_: Exception) {}
            encoder.release()
            try { if (muxerStarted) muxer.stop() } catch (e: Exception) { Log.w(TAG, "muxer.stop: ${e.message}") }
            muxer.release()
            audio?.close()
            pfd.close()
        }
        val m = plan.fit.model
        return Result(frames, outW, eyeH, half, m.scale, m.rotationDeg, m.k, plan.fit.inlierCount,
            System.currentTimeMillis() - t0)
    }

    private fun encoderSupports(w: Int, h: Int): Boolean {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(fmt) ?: return false
        return try {
            val codec = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first { it.name == name }
            codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities.isSizeSupported(w, h)
        } catch (_: Exception) { false }
    }

    private fun countVideoSamples(uri: Uri): Int {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val t = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            ex.selectTrack(t)
            var n = 0
            while (ex.sampleTime >= 0) { n++; ex.advance() }
            return n
        } finally { ex.release() }
    }

    private fun sourceFps(uri: Uri): Float {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val t = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            val f = ex.getTrackFormat(t)
            if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) return f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
            ex.selectTrack(t)
            var n = 0; var last = 0L
            while (ex.sampleTime >= 0 && n < 120) { last = ex.sampleTime; n++; ex.advance() }
            return if (n > 1 && last > 0) (n - 1) * 1_000_000f / last else 30f
        } finally { ex.release() }
    }

    /** Copies contiguous I420 (Y, then U, then V) into an encoder input image, honouring strides. */
    private fun writeI420(yuv: Mat, w: Int, h: Int, img: Image) {
        val data = ByteArray(w * h * 3 / 2)
        yuv.get(0, 0, data)
        val planes = img.planes
        var off = 0
        for (p in 0 until 3) {
            val pw = if (p == 0) w else w / 2
            val ph = if (p == 0) h else h / 2
            val plane = planes[p]
            val buf = plane.buffer
            val rs = plane.rowStride; val ps = plane.pixelStride
            for (row in 0 until ph) {
                val base = row * rs
                if (ps == 1) {
                    buf.position(base); buf.put(data, off, pw)
                } else {
                    for (col in 0 until pw) buf.put(base + col * ps, data[off + col])
                }
                off += pw
            }
        }
    }

    // ------------------------------------------------------------------ decoding

    /** Sequential video frame decoder producing BGR Mats (rotated upright if the track says so). */
    private class FrameDecoder(context: Context, uri: Uri) : Closeable {
        private val extractor = MediaExtractor()
        private val codec: MediaCodec
        private val rotation: Int
        private var inputDone = false
        private var outputDone = false
        private val info = MediaCodec.BufferInfo()
        var lastPtsUs = 0L; private set

        init {
            extractor.setDataSource(context, uri, null)
            val t = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")
            }
            extractor.selectTrack(t)
            val fmt = extractor.getTrackFormat(t)
            rotation = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else 0
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
        }

        fun next(): Mat? {
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = requireNotNull(codec.getInputBuffer(i))
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (info.size > 0) {
                        val img = codec.getOutputImage(o)
                        val mat = img?.let { imageToBgr(it) }
                        img?.close()
                        codec.releaseOutputBuffer(o, false)
                        if (mat != null) { lastPtsUs = info.presentationTimeUs; return rotate(mat) }
                    } else {
                        codec.releaseOutputBuffer(o, false)
                    }
                }
            }
            return null
        }

        private fun rotate(m: Mat): Mat = YuvMat.rotate(m, rotation)

        private fun imageToBgr(img: Image): Mat = YuvMat.toBgr(img)

        override fun close() {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            extractor.release()
        }
    }

    /** Audio passthrough from the wide clip. */
    private class AudioSource(private val extractor: MediaExtractor, val format: MediaFormat) : Closeable {
        companion object {
            fun open(context: Context, uri: Uri): AudioSource? {
                val ex = MediaExtractor()
                return try {
                    ex.setDataSource(context, uri, null)
                    val t = (0 until ex.trackCount).firstOrNull {
                        ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/")
                    } ?: run { ex.release(); return null }
                    ex.selectTrack(t)
                    AudioSource(ex, ex.getTrackFormat(t))
                } catch (e: Exception) { ex.release(); null }
            }
        }

        fun copyTo(muxer: MediaMuxer, track: Int) {
            if (track < 0) return
            val buf = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val n = extractor.readSampleData(buf, 0)
                if (n < 0) break
                info.offset = 0; info.size = n; info.presentationTimeUs = extractor.sampleTime
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(track, buf, info)
                extractor.advance()
            }
        }

        override fun close() = extractor.release()
    }
}
