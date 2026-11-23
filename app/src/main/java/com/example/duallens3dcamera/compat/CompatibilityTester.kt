package com.example.duallens3dcamera.compat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import com.example.duallens3dcamera.camera.LensDiscovery
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Automated check of whether this phone can do dual-lens stereo with this app:
 * camera IDs (including hidden ones), the rear logical pair, a simultaneous photo pair, stereo
 * video into real encoders (with the hidden-camera workaround if needed). Blocking; run off the main thread. [log] receives human-readable lines.
 */
class CompatibilityTester(private val context: Context, private val log: (String) -> Unit) {

    enum class Support { YES, YES_WITH_WORKAROUND, NO, NOT_TESTED }

    data class Verdict(
        val photos: Support = Support.NOT_TESTED,
        val video: Support = Support.NOT_TESTED,
        val livePreview: Support = Support.NOT_TESTED,
        val notes: List<String> = emptyList()
    )

    private val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("CompatTest").apply { start() }
    private val handler = Handler(thread.looper)

    fun run(): Verdict {
        val notes = ArrayList<String>()
        try {
            log("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.HARDWARE}")
            log("")

            // 1) Cameras
            log("[1] Cameras")
            val listed = cm.cameraIdList.toList()
            log("  Listed IDs: ${listed.joinToString()}")
            val hidden = (0..15).map { it.toString() }.filter { it !in listed && charsOrNull(it) != null }
            log("  Hidden IDs that answer when asked by number: ${if (hidden.isEmpty()) "none" else hidden.joinToString()}")
            for (id in listed + hidden) {
                val c = charsOrNull(id) ?: continue
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "back"; CameraCharacteristics.LENS_FACING_FRONT -> "front"; else -> "external"
                }
                val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val phys = c.physicalCameraIds
                val logical = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
                log("  ID $id${if (id in hidden) " (hidden)" else ""}: $facing, ${focal?.let { "%.2f mm".format(it) } ?: "?"}, " +
                    "sensor ${sensor?.let { "%.2fx%.2f mm".format(it.width, it.height) } ?: "?"}" +
                    if (logical) ", paired camera of ${phys.joinToString("+")}" else "")
            }
            try {
                val sets = cm.concurrentCameraIds
                if (sets.isNotEmpty()) log("  Concurrent camera sets: ${sets.joinToString { it.sorted().joinToString("+", "{", "}") }}")
            } catch (_: Exception) {}
            log("")

            // 2) Stereo pair
            log("[2] Rear stereo pair")
            val rig = LensDiscovery.discoverStereoRig(cm)
            if (rig == null) {
                log("  ✗ No rear paired (logical multi-) camera with two lenses was found.")
                notes += "This phone does not expose two rear lenses together to apps. A mirror adapter on the main camera is the alternative."
                return Verdict(Support.NO, Support.NO, Support.NOT_TESTED, notes)
            }
            val logicalChars = cm.getCameraCharacteristics(rig.logicalRearId)
            val wideChars = cm.getCameraCharacteristics(rig.wide1xId)
            val ultraChars = cm.getCameraCharacteristics(rig.ultraId)
            val sync = when (logicalChars.get(CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE)) {
                CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_CALIBRATED -> "calibrated (hardware)"
                CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_APPROXIMATE -> "approximate"
                else -> "unknown"
            }
            log("  ✓ Paired camera ${rig.logicalRearId}${if (rig.logicalIsHidden) " (hidden from the list)" else ""}: " +
                "main = ${rig.wide1xId}, ultrawide = ${rig.ultraId}, sensor sync $sync")
            if (rig.logicalIsHidden) notes += "Uses a hidden paired camera (found by probing IDs)."
            log("")

            val camera = open(rig.logicalRearId) ?: run {
                log("  ✗ Could not open camera ${rig.logicalRearId}.")
                return Verdict(Support.NO, Support.NO, Support.NOT_TESTED, notes)
            }
            try {
                // 3) Photo pair
                log("[3] Stereo photo (both lenses in one capture)")
                val photos = testPhoto(camera, rig, wideChars, ultraChars)
                log("")

                // 4) Video
                log("[4] Stereo video (two encoders, ~1.5 s)")
                var video = testVideo(camera, rig, wideChars, ultraChars, wideViaLogical = false)
                if (video == Support.NO) {
                    log("  Retrying with the main-lens video taken from the paired camera's own stream...")
                    video = if (testVideo(camera, rig, wideChars, ultraChars, wideViaLogical = true) == Support.YES) {
                        notes += "Video needs the workaround (main lens from the paired stream); the app applies it automatically for hidden paired cameras."
                        Support.YES_WITH_WORKAROUND
                    } else Support.NO
                }
                log("")
                return Verdict(photos, video, Support.NOT_TESTED, notes)
            } finally {
                camera.close()
            }
        } catch (e: Exception) {
            log("  ✗ Test stopped: ${e.javaClass.simpleName}: ${e.message}")
            return Verdict(notes = notes + "Test stopped early: ${e.message}")
        } finally {
            thread.quitSafely()
        }
    }

    // ------------------------------------------------------------------ steps

    private fun testPhoto(camera: CameraDevice, rig: LensDiscovery.StereoRig,
                          wideChars: CameraCharacteristics, ultraChars: CameraCharacteristics): Support {
        val wSize = pickSize(wideChars, ImageFormat.YUV_420_888, 1600)
        val uSize = pickSize(ultraChars, ImageFormat.YUV_420_888, 1600)
        if (wSize == null || uSize == null) { log("  ✗ No YUV sizes on the lenses."); return Support.NO }
        val wr = ImageReader.newInstance(wSize.width, wSize.height, ImageFormat.YUV_420_888, 2)
        val ur = ImageReader.newInstance(uSize.width, uSize.height, ImageFormat.YUV_420_888, 2)
        val latch = CountDownLatch(2)
        val stats = arrayOfNulls<Pair<Long, Double>>(2)
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)  // only count the still, not warm-up frames
        fun listen(i: Int) = ImageReader.OnImageAvailableListener { r ->
            r.acquireLatestImage()?.use { img ->
                if (armed.get() && stats[i] == null) { stats[i] = img.timestamp to lumaStd(img); latch.countDown() }
            }
        }
        wr.setOnImageAvailableListener(listen(0), handler)
        ur.setOnImageAvailableListener(listen(1), handler)
        try {
            val session = configure(camera, listOf(
                OutputConfiguration(wr.surface).apply { setPhysicalCameraId(rig.wide1xId) },
                OutputConfiguration(ur.surface).apply { setPhysicalCameraId(rig.ultraId) }
            )) ?: run { log("  ✗ The phone refused a session with both lenses."); return Support.NO }
            try {
                // Let exposure settle with a short preview of both lenses, wait for in-flight frames
                // to land, then take one still that targets both.
                val prev = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(wr.surface); addTarget(ur.surface)
                }
                session.setRepeatingRequest(prev.build(), null, handler)
                Thread.sleep(800)
                session.stopRepeating()
                session.abortCaptures()
                Thread.sleep(400)
                val still = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(wr.surface); addTarget(ur.surface)
                }
                handler.post { armed.set(true) }  // after any already-queued listener calls
                Thread.sleep(50)
                session.capture(still.build(), null, handler)
                if (!latch.await(4, TimeUnit.SECONDS) || stats[0] == null || stats[1] == null) {
                    log("  ✗ Only ${listOfNotNull(stats[0], stats[1]).size} of 2 lens images arrived.")
                    return Support.NO
                }
                val (tw, sw) = stats[0]!!; val (tu, su) = stats[1]!!
                val skewMs = abs(tw - tu) / 1e6
                log("  Main ${wSize.width}x${wSize.height} detail %.1f, ultrawide ${uSize.width}x${uSize.height} detail %.1f, time gap %.2f ms".format(sw, su, skewMs))
                if (sw < 2.0 || su < 2.0) { log("  ✗ One image is blank."); return Support.NO }
                log("  ✓ Both lenses captured together${if (skewMs > 5) " (gap is large; fast motion may not line up)" else ""}.")
                return Support.YES
            } finally {
                session.close()
            }
        } finally {
            wr.close(); ur.close()
        }
    }

    private fun testVideo(camera: CameraDevice, rig: LensDiscovery.StereoRig, wideChars: CameraCharacteristics,
                          ultraChars: CameraCharacteristics, wideViaLogical: Boolean): Support {
        val size = commonPrivateSize(wideChars, ultraChars) ?: run { log("  ✗ No common video size."); return Support.NO }
        val encW = Encoder(size); val encU = Encoder(size)
        // Same layout as the app's recording session: a preview stream from the main lens plus
        // one encoder per lens. (Without the preview, some phones pass here but fail in the app.)
        val previewTex = SurfaceTexture(false).apply { setDefaultBufferSize(640, 480) }
        val previewSurface = Surface(previewTex)
        try {
            val session = configure(camera, listOf(
                OutputConfiguration(previewSurface).apply { setPhysicalCameraId(rig.wide1xId) },
                OutputConfiguration(encW.surface).apply { if (!wideViaLogical) setPhysicalCameraId(rig.wide1xId) },
                OutputConfiguration(encU.surface).apply { setPhysicalCameraId(rig.ultraId) }
            )) ?: run { log("  ✗ The phone refused a video session with both lenses."); return Support.NO }
            try {
                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(previewSurface); addTarget(encW.surface); addTarget(encU.surface)
                }
                session.setRepeatingRequest(req.build(), null, handler)
                Thread.sleep(1500)
                session.stopRepeating()
                Thread.sleep(150)
            } finally {
                session.close()
            }
        } finally {
            encW.finish(); encU.finish()
            previewSurface.release(); previewTex.release()
        }
        val wAvg = encW.avgBytes(); val uAvg = encU.avgBytes()
        log("  ${size.width}x${size.height}: main ${encW.frames} frames (~${wAvg} bytes/frame), ultrawide ${encU.frames} frames (~${uAvg} bytes/frame)")
        // Blank frames compress to almost nothing; real 720p frames are tens of kB.
        val wOk = encW.frames >= 8 && wAvg > 2500
        val uOk = encU.frames >= 8 && uAvg > 2500
        return when {
            wOk && uOk -> { log("  ✓ Both lenses recorded real video."); Support.YES }
            !wOk && uOk -> { log("  ✗ The main lens recorded blank or no frames."); Support.NO }
            wOk && !uOk -> { log("  ✗ The ultrawide recorded blank or no frames."); Support.NO }
            else -> { log("  ✗ Neither lens recorded video."); Support.NO }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun charsOrNull(id: String): CameraCharacteristics? =
        try { cm.getCameraCharacteristics(id) } catch (_: Exception) { null }

    @SuppressLint("MissingPermission") // The activity checks CAMERA permission before running.
    private fun open(id: String): CameraDevice? {
        val latch = CountDownLatch(1)
        var device: CameraDevice? = null
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) { device = camera; latch.countDown() }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); latch.countDown() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close(); latch.countDown() }
        }, handler)
        latch.await(5, TimeUnit.SECONDS)
        return device
    }

    private fun configure(camera: CameraDevice, outputs: List<OutputConfiguration>): CameraCaptureSession? {
        val latch = CountDownLatch(1)
        var session: CameraCaptureSession? = null
        try {
            camera.createCaptureSessionByOutputConfigurations(outputs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; latch.countDown() }
                override fun onConfigureFailed(s: CameraCaptureSession) { latch.countDown() }
            }, handler)
        } catch (e: Exception) {
            log("  (session error: ${e.message})")
            return null
        }
        latch.await(5, TimeUnit.SECONDS)
        return session
    }

    private fun CameraCharacteristics.yuvSizes(): Array<Size>? =
        get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)

    /** Largest 4:3-ish size with width <= maxWidth. */
    private fun pickSize(c: CameraCharacteristics, format: Int, maxWidth: Int): Size? {
        val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(format) ?: return null
        return sizes.filter { it.width <= maxWidth && abs(it.width.toDouble() / it.height - 4.0 / 3.0) < 0.05 }
            .maxByOrNull { it.width * it.height } ?: sizes.minByOrNull { abs(it.width - maxWidth) }
    }

    private fun commonPrivateSize(a: CameraCharacteristics, b: CameraCharacteristics): Size? {
        fun sizes(c: CameraCharacteristics) =
            c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java)?.toSet() ?: emptySet()
        val common = sizes(a).intersect(sizes(b))
        return common.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: common.filter { it.width <= 1280 }.maxByOrNull { it.width * it.height }
    }

    /** Standard deviation of a 64x64 sample grid of the luma plane (0 for a blank frame). */
    private fun lumaStd(img: Image): Double {
        val p = img.planes[0]; val buf = p.buffer
        var sum = 0.0; var sq = 0.0; var n = 0
        for (gy in 0 until 64) for (gx in 0 until 64) {
            val x = gx * img.width / 64; val y = gy * img.height / 64
            val idx = y * p.rowStride + x * p.pixelStride
            if (idx >= buf.limit()) continue
            val v = (buf.get(idx).toInt() and 0xFF).toDouble()
            sum += v; sq += v * v; n++
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sqrt((sq / n - mean * mean).coerceAtLeast(0.0))
    }

    /** Surface-input H.264 encoder that counts output frames and bytes. */
    private class Encoder(size: Size) {
        private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val surface: Surface
        @Volatile var frames = 0; private set
        @Volatile private var bytes = 0L
        @Volatile private var running = true
        private val drainer: Thread

        init {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            drainer = Thread {
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val idx = try { codec.dequeueOutputBuffer(info, 10_000) } catch (_: Exception) { break }
                    if (idx >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            frames++; bytes += info.size
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    } else if (!running && idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // keep waiting for EOS
                    }
                }
            }.apply { name = "CompatEncoderDrain"; start() }
        }

        fun avgBytes(): Long = if (frames == 0) 0 else bytes / frames

        fun finish() {
            running = false
            try { codec.signalEndOfInputStream() } catch (_: Exception) {}
            drainer.join(2000)
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            surface.release()
        }
    }
}
