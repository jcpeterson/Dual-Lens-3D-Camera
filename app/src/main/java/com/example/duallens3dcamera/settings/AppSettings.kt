// (FULL FILE CONTENT)
// ✅ Paste exactly as-is

package com.example.duallens3dcamera.settings

import android.content.Context
import android.content.SharedPreferences
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Size
//import android.view.SurfaceTexture
import android.graphics.SurfaceTexture
import com.example.duallens3dcamera.camera.LensDiscovery
import com.example.duallens3dcamera.util.SizeSelector
import kotlin.math.abs

object AppSettings {

    const val PREFS_NAME = "stereo_capture_prefs"

    // Existing keys (keep these so you don't break existing prefs)
    const val KEY_EIS_ENABLED = "eisEnabled"
    const val KEY_RAW_MODE = "rawMode"
    const val KEY_ZOOM_2X = "zoom2xEnabled"

    // New keys
    const val KEY_VIDEO_RESOLUTION = "video_resolution"       // "WxH"
    const val KEY_VIDEO_FPS = "video_fps"                     // "30"
    const val KEY_VIDEO_BITRATE_MBPS = "video_bitrate_mbps"   // "50"

    // Video processing (applied to recording request only; preview is forced OFF)
    const val KEY_VIDEO_NOISE_REDUCTION = "video_noise_reduction" // "off" | "fast" | "hq"
    const val KEY_VIDEO_DISTORTION_CORRECTION = "video_distortion_correction" // "off" | "fast" | "hq"
    const val KEY_VIDEO_EDGE_MODE = "video_edge_mode" // "off" | "fast" | "hq"

    const val KEY_PREVIEW_RESOLUTION = "preview_resolution"
    const val KEY_PREVIEW_FPS = "preview_fps"

    const val KEY_PHOTO_NOISE_REDUCTION = "photo_noise_reduction"
    const val KEY_PHOTO_DISTORTION_CORRECTION = "photo_distortion_correction"
    const val KEY_PHOTO_EDGE_MODE = "photo_edge_mode"
    // Whether or not to save the two images separately on top of the sbs output
    // Note: Applies in JPG mode only (RAW mode always provides two dng files and not sbs)
    const val KEY_PHOTO_SAVE_INDIVIDUAL_IMAGES = "photo_save_individual_images"

    // Debugging (Advanced)
    const val KEY_DEBUG_VIDEO_LOG_ENABLED = "debug_video_log_enabled"
    const val KEY_DEBUG_VIDEO_LOG_FRAMES_ONLY = "debug_video_log_frames_only"
    const val KEY_DEBUG_PHOTO_JSON_LOG_ENABLED = "debug_photo_json_log"
    const val KEY_DEBUG_PHOTO_SYNC_TOAST = "debug_photo_sync_toast"
    const val KEY_DEBUG_DUMP_CAMERA_INFO = "debug_dump_camera_info"


    val VIDEO_BITRATE_OPTIONS_MBPS = listOf(1, 5, 10, 20, 30, 40, 50, 75, 100, 150, 200, 250, 300)

    // Keep this list small/manageable. Add 120 later if you want high speed sessions.
    val FPS_CANDIDATES = listOf(15, 24, 30, 60)

    private val FALLBACK_VIDEO_SIZE = Size(1440, 1080)
    private const val FALLBACK_VIDEO_FPS = 30
    private const val FALLBACK_VIDEO_BITRATE_MBPS = 50

    private val FALLBACK_PREVIEW_SIZE = Size(800, 600)
    private const val FALLBACK_PREVIEW_FPS = 30

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun sizeToString(size: Size): String = "${size.width}x${size.height}"

    fun parseSize(value: String?): Size? {
        if (value.isNullOrBlank()) return null
        val parts = value.lowercase().split("x")
        if (parts.size != 2) return null
        val w = parts[0].toIntOrNull() ?: return null
        val h = parts[1].toIntOrNull() ?: return null
        if (w <= 0 || h <= 0) return null
        return Size(w, h)
    }

    fun aspectLabel(size: Size): String {
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        if (h == 0f) return ""
        val r = w / h

        fun closeTo(a: Float, b: Float) = abs(a - b) < 0.015f

        return when {
            closeTo(r, 4f / 3f) -> "4:3"
            closeTo(r, 16f / 9f) -> "16:9"
            closeTo(r, 3f / 2f) -> "3:2"
            closeTo(r, 1f) -> "1:1"
            closeTo(r, 2f) -> "18:9"
            else -> {
                val g = gcd(size.width, size.height)
                "${size.width / g}:${size.height / g}"
            }
        }
    }

    private fun gcd(a0: Int, b0: Int): Int {
        var a = abs(a0)
        var b = abs(b0)
        while (b != 0) {
            val t = a % b
            a = b
            b = t
        }
        return if (a == 0) 1 else a
    }

    fun getVideoResolution(context: Context): Size =
        parseSize(prefs(context).getString(KEY_VIDEO_RESOLUTION, null)) ?: FALLBACK_VIDEO_SIZE

    fun getVideoFps(context: Context): Int =
        prefs(context).getString(KEY_VIDEO_FPS, null)?.toIntOrNull() ?: FALLBACK_VIDEO_FPS

    fun getVideoBitrateBps(context: Context): Int {
        val mbps = prefs(context).getString(KEY_VIDEO_BITRATE_MBPS, null)?.toIntOrNull()
            ?: FALLBACK_VIDEO_BITRATE_MBPS
        return mbps * 1_000_000
    }

    fun getPreviewResolution(context: Context): Size =
        parseSize(prefs(context).getString(KEY_PREVIEW_RESOLUTION, null)) ?: FALLBACK_PREVIEW_SIZE

    fun getPreviewFps(context: Context): Int =
        prefs(context).getString(KEY_PREVIEW_FPS, null)?.toIntOrNull() ?: FALLBACK_PREVIEW_FPS

    fun getPhotoNoiseReductionMode(context: Context): Int {
        return when (prefs(context).getString(KEY_PHOTO_NOISE_REDUCTION, "off")) {
            "fast" -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
            "hq" -> CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            else -> CaptureRequest.NOISE_REDUCTION_MODE_OFF
        }
    }

    fun getPhotoDistortionCorrectionMode(context: Context): Int {
        return when (prefs(context).getString(KEY_PHOTO_DISTORTION_CORRECTION, "hq")) {
            "fast" -> CaptureRequest.DISTORTION_CORRECTION_MODE_FAST
            "off" -> CaptureRequest.DISTORTION_CORRECTION_MODE_OFF
            else -> CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
        }
    }

    fun getPhotoEdgeMode(context: Context): Int {
        return when (prefs(context).getString(KEY_PHOTO_EDGE_MODE, "off")) {
            "fast" -> CaptureRequest.EDGE_MODE_FAST
            "hq" -> CaptureRequest.EDGE_MODE_HIGH_QUALITY
            else -> CaptureRequest.EDGE_MODE_OFF
        }
    }

    fun getPhotoSaveIndividualImages(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PHOTO_SAVE_INDIVIDUAL_IMAGES, false)

    fun getVideoNoiseReductionMode(context: Context): Int {
        return when (prefs(context).getString(KEY_VIDEO_NOISE_REDUCTION, "off")) {
            "fast" -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
            "hq" -> CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            else -> CaptureRequest.NOISE_REDUCTION_MODE_OFF
        }
    }

    fun getVideoDistortionCorrectionMode(context: Context): Int {
        // Default FAST per request.
        return when (prefs(context).getString(KEY_VIDEO_DISTORTION_CORRECTION, "fast")) {
            "hq" -> CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
            "off" -> CaptureRequest.DISTORTION_CORRECTION_MODE_OFF
            else -> CaptureRequest.DISTORTION_CORRECTION_MODE_FAST
        }
    }

    fun getVideoEdgeMode(context: Context): Int {
        return when (prefs(context).getString(KEY_VIDEO_EDGE_MODE, "off")) {
            "fast" -> CaptureRequest.EDGE_MODE_FAST
            "hq" -> CaptureRequest.EDGE_MODE_HIGH_QUALITY
            else -> CaptureRequest.EDGE_MODE_OFF
        }
    }

    fun getDebugVideoLogEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_DEBUG_VIDEO_LOG_ENABLED, false)
    }

    fun getDebugVideoLogFramesOnly(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_DEBUG_VIDEO_LOG_FRAMES_ONLY, false)
    }

    fun getDebugPhotoJsonLogEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_DEBUG_PHOTO_JSON_LOG_ENABLED, false)
    }

    fun getDebugPhotoSyncToastEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_DEBUG_PHOTO_SYNC_TOAST, false)
    }

    data class StereoCaps(
        val wide1xChars: CameraCharacteristics,
        val wide1xMap: StreamConfigurationMap,
        val ultraChars: CameraCharacteristics,
        val ultraMap: StreamConfigurationMap,
        val wide2xChars: CameraCharacteristics?,
        val wide2xMap: StreamConfigurationMap?
    )

    fun loadStereoCaps(context: Context): StereoCaps? {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val rig = LensDiscovery.discoverStereoRig(cm) ?: return null

            val w1c = cm.getCameraCharacteristics(rig.wide1xId)
            val uc = cm.getCameraCharacteristics(rig.ultraId)

            val w1m = w1c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            val um = uc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null

            val w2c = rig.wide2xId?.let { cm.getCameraCharacteristics(it) }
            val w2m = w2c?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            StereoCaps(w1c, w1m, uc, um, w2c, w2m)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolutions common across the two capture lenses (wide + ultrawide).
     */
    fun commonVideoSizes(caps: StereoCaps): List<Size> {
        val wideSizes = (caps.wide1xMap.getOutputSizes(ImageFormat.PRIVATE) ?: emptyArray()).toList()
        val ultraSizes = (caps.ultraMap.getOutputSizes(ImageFormat.PRIVATE) ?: emptyArray()).toList()

        val ultraSet = ultraSizes.map { it.width to it.height }.toSet()
        val common = wideSizes.filter { ultraSet.contains(it.width to it.height) }

        return common.distinctBy { it.width to it.height }
            .sortedByDescending { it.width.toLong() * it.height.toLong() }
    }

    /**
     * Preview sizes we know should work in both 1x and 2x modes (if 2x exists).
     * We keep only 4:3 sizes to match the viewfinder UI ratio.
     */
    fun commonPreviewSizes(caps: StereoCaps): List<Size> {
        val w1 = (caps.wide1xMap.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()).toList()

        var common = w1
        val wide2xMap = caps.wide2xMap
        if (wide2xMap != null) {
            val w2 = (wide2xMap.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray())
                .map { it.width to it.height }
                .toSet()
            common = common.filter { w2.contains(it.width to it.height) }
        }

        return common
            .filter { SizeSelector.isFourByThree(it) }
            .distinctBy { it.width to it.height }
            .sortedByDescending { it.width.toLong() * it.height.toLong() }
    }

    fun chooseDefaultVideoSize(commonVideoSizes: List<Size>): Size {
        val fourByThree = commonVideoSizes.filter { SizeSelector.isFourByThree(it) }
        return (fourByThree.ifEmpty { commonVideoSizes })
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: FALLBACK_VIDEO_SIZE
    }

    fun chooseDefaultPreviewSize(commonPreviewSizes: List<Size>): Size {
        val target = FALLBACK_PREVIEW_SIZE

        commonPreviewSizes.firstOrNull { it.width == target.width && it.height == target.height }?.let { return it }

        val targetArea = target.width.toLong() * target.height.toLong()
        return commonPreviewSizes.minByOrNull {
            val area = it.width.toLong() * it.height.toLong()
            abs(area - targetArea)
        } ?: target
    }

    fun supportedVideoFpsForSize(caps: StereoCaps, size: Size): List<Int> {
        val cams = listOf(
            caps.wide1xChars to caps.wide1xMap,
            caps.ultraChars to caps.ultraMap
        )
        return FPS_CANDIDATES.filter { fps ->
            cams.all { (chars, map) -> supportsFps(chars, map, ImageFormat.PRIVATE, size, fps) }
        }
    }

    fun supportedPreviewFpsForSize(caps: StereoCaps, size: Size): List<Int> {
        val cams = buildList {
            add(caps.wide1xChars to caps.wide1xMap)
            val w2c = caps.wide2xChars
            val w2m = caps.wide2xMap
            if (w2c != null && w2m != null) add(w2c to w2m)
        }

        return FPS_CANDIDATES.filter { fps ->
            cams.all { (chars, map) -> supportsFps(chars, map, SurfaceTexture::class.java, size, fps) }
        }
    }

    private fun supportsFps(
        chars: CameraCharacteristics,
        map: StreamConfigurationMap,
        output: Any, // Int format OR Class<*>
        size: Size,
        fps: Int
    ): Boolean {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val rangeOk = ranges?.any { it.lower <= fps && it.upper >= fps } ?: true

        val minFrameDurationNs = try {
            when (output) {
                is Int -> map.getOutputMinFrameDuration(output, size)
                is Class<*> -> map.getOutputMinFrameDuration(output, size)
                else -> 0L
            }
        } catch (_: Exception) {
            0L
        }

        val durationOk = if (minFrameDurationNs <= 0L) {
            true
        } else {
            val maxFps = (1_000_000_000.0 / minFrameDurationNs.toDouble())
            maxFps + 0.5 >= fps
        }

        return rangeOk && durationOk
    }

    /**
     * Called when MainActivity has permission. Fills in missing settings with sensible defaults,
     * including device-derived defaults for video resolution.
     */
    fun ensureCameraBasedDefaults(context: Context) {
        val sp = prefs(context)
        val editor = sp.edit()
        var changed = false

        // Migrate legacy quick-toggle prefs if present.
        if (!sp.contains(KEY_VIDEO_RESOLUTION) && sp.contains("videoResIndex")) {
            val legacy = listOf(1440, 1920, 2560, 4000)
            val idx = sp.getInt("videoResIndex", 0).coerceIn(0, legacy.size - 1)
            val vertical = legacy[idx]
            val horizontal = (vertical * 3) / 4
            editor.putString(KEY_VIDEO_RESOLUTION, "${vertical}x${horizontal}")
            changed = true
        }

        if (!sp.contains(KEY_VIDEO_BITRATE_MBPS) && sp.contains("bitrateIndex")) {
            val legacy = listOf(50, 100, 200)
            val idx = sp.getInt("bitrateIndex", 0).coerceIn(0, legacy.size - 1)
            editor.putString(KEY_VIDEO_BITRATE_MBPS, legacy[idx].toString())
            changed = true
        }

        val caps = loadStereoCaps(context)
        if (!sp.contains(KEY_VIDEO_RESOLUTION)) {
            val def = caps?.let { chooseDefaultVideoSize(commonVideoSizes(it)) } ?: FALLBACK_VIDEO_SIZE
            editor.putString(KEY_VIDEO_RESOLUTION, sizeToString(def))
            changed = true
        }

        if (!sp.contains(KEY_VIDEO_FPS)) {
            val chosenSize = parseSize(sp.getString(KEY_VIDEO_RESOLUTION, null)) ?: FALLBACK_VIDEO_SIZE
            val fps = caps?.let { supportedVideoFpsForSize(it, chosenSize) }?.let {
                when {
                    it.contains(30) -> 30
                    it.isNotEmpty() -> it.maxOrNull()
                    else -> null
                }
            } ?: FALLBACK_VIDEO_FPS
            editor.putString(KEY_VIDEO_FPS, fps.toString())
            changed = true
        }

        if (!sp.contains(KEY_VIDEO_BITRATE_MBPS)) {
            editor.putString(KEY_VIDEO_BITRATE_MBPS, FALLBACK_VIDEO_BITRATE_MBPS.toString())
            changed = true
        }

        if (!sp.contains(KEY_PREVIEW_RESOLUTION)) {
            val defPrev = caps?.let { chooseDefaultPreviewSize(commonPreviewSizes(it)) } ?: FALLBACK_PREVIEW_SIZE
            editor.putString(KEY_PREVIEW_RESOLUTION, sizeToString(defPrev))
            changed = true
        }

        if (!sp.contains(KEY_PREVIEW_FPS)) {
            editor.putString(KEY_PREVIEW_FPS, FALLBACK_PREVIEW_FPS.toString())
            changed = true
        }

        if (!sp.contains(KEY_PHOTO_NOISE_REDUCTION)) {
            editor.putString(KEY_PHOTO_NOISE_REDUCTION, "off"); changed = true
        }
        if (!sp.contains(KEY_PHOTO_DISTORTION_CORRECTION)) {
            editor.putString(KEY_PHOTO_DISTORTION_CORRECTION, "hq"); changed = true
        }
        if (!sp.contains(KEY_PHOTO_EDGE_MODE)) {
            editor.putString(KEY_PHOTO_EDGE_MODE, "off"); changed = true
        }
        // default: in JPG mode, output only SBS, no individual images
        if (!sp.contains(KEY_PHOTO_SAVE_INDIVIDUAL_IMAGES)) {
            editor.putBoolean(KEY_PHOTO_SAVE_INDIVIDUAL_IMAGES, false); changed = true
        }

        // Video processing defaults (recording only).
        if (!sp.contains(KEY_VIDEO_NOISE_REDUCTION)) {
            editor.putString(KEY_VIDEO_NOISE_REDUCTION, "off"); changed = true
        }
        if (!sp.contains(KEY_VIDEO_DISTORTION_CORRECTION)) {
            editor.putString(KEY_VIDEO_DISTORTION_CORRECTION, "fast"); changed = true
        }
        if (!sp.contains(KEY_VIDEO_EDGE_MODE)) {
            editor.putString(KEY_VIDEO_EDGE_MODE, "off"); changed = true
        }

        // Debugging defaults (all OFF).
        if (!sp.contains(KEY_DEBUG_VIDEO_LOG_ENABLED)) {
            editor.putBoolean(KEY_DEBUG_VIDEO_LOG_ENABLED, false); changed = true
        }
        if (!sp.contains(KEY_DEBUG_VIDEO_LOG_FRAMES_ONLY)) {
            editor.putBoolean(KEY_DEBUG_VIDEO_LOG_FRAMES_ONLY, false); changed = true
        }
        if (!sp.contains(KEY_DEBUG_PHOTO_JSON_LOG_ENABLED)) {
            editor.putBoolean(KEY_DEBUG_PHOTO_JSON_LOG_ENABLED, false); changed = true
        }
        if (!sp.contains(KEY_DEBUG_PHOTO_SYNC_TOAST)) {
            editor.putBoolean(KEY_DEBUG_PHOTO_SYNC_TOAST, false); changed = true
        }

        if (changed) editor.apply()
    }
}
