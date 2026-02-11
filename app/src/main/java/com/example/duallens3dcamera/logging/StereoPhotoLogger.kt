package com.example.duallens3dcamera.logging

import android.content.Context
import android.net.Uri
import android.graphics.Rect
import android.hardware.camera2.CaptureResult
import android.os.Build
import android.util.JsonWriter
import java.io.OutputStreamWriter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object StereoPhotoLogger {

    private const val SCHEMA_VERSION = 3

    data class PhotoSyncMetrics(
        val wideSensorTimestampNs: Long,
        val ultraSensorTimestampNs: Long,
        val wideExposureTimeNs: Long,
        val ultraExposureTimeNs: Long,
        val deltaStartNs: Long, // wide - ultra (signed)
        val deltaStartAbsNs: Long,
        val overlapNs: Long?, // duration where both lenses were integrating light at the same time
        val nonOverlapNs: Long?, // duration where exactly one lens was integrating (wide+ultra - 2*overlap)
        val idleGapNs: Long?, // if exposures are disjoint, time when neither lens was integrating (else 0)
        val unionNs: Long?, // total span from earliest start to latest end (overlap + nonOverlap + idleGap)
        val overlapPctOfShorter: Double?, // overlap / min(exposure) * 100
        val overlapPctOfLonger: Double? // overlap / max(exposure) * 100
    ) {
        fun wideSensorTimestampMs(): Double = wideSensorTimestampNs / 1_000_000.0
        fun ultraSensorTimestampMs(): Double = ultraSensorTimestampNs / 1_000_000.0
        fun wideExposureTimeMs(): Double = wideExposureTimeNs / 1_000_000.0
        fun ultraExposureTimeMs(): Double = ultraExposureTimeNs / 1_000_000.0
        fun deltaStartMs(): Double = deltaStartNs / 1_000_000.0
        fun deltaStartAbsMs(): Double = deltaStartAbsNs / 1_000_000.0
        fun overlapMs(): Double? = overlapNs?.let { it / 1_000_000.0 }
        fun nonOverlapMs(): Double? = nonOverlapNs?.let { it / 1_000_000.0 }
        fun idleGapMs(): Double? = idleGapNs?.let { it / 1_000_000.0 }
        fun unionMs(): Double? = unionNs?.let { it / 1_000_000.0 }
    }

    fun computeMetrics(
        wideSensorTimestampNs: Long,
        ultraSensorTimestampNs: Long,
        wideExposureTimeNs: Long,
        ultraExposureTimeNs: Long
    ): PhotoSyncMetrics {
        val delta = wideSensorTimestampNs - ultraSensorTimestampNs
        val deltaAbs = abs(delta)

        val haveExposure = wideExposureTimeNs > 0 && ultraExposureTimeNs > 0 &&
                wideSensorTimestampNs > 0 && ultraSensorTimestampNs > 0

        val overlap: Long?
        val nonOverlap: Long?
        val idleGap: Long?
        val union: Long?
        val overlapPctShorter: Double?
        val overlapPctLonger: Double?

        if (haveExposure) {
            val wStart = wideSensorTimestampNs
            val wEnd = wStart + wideExposureTimeNs
            val uStart = ultraSensorTimestampNs
            val uEnd = uStart + ultraExposureTimeNs

            val startMax = max(wStart, uStart)
            val endMin = min(wEnd, uEnd)

            val overlapLocal = max(0L, endMin - startMax)
            overlap = overlapLocal

            // Total span from earliest start to latest end.
            val startMin = min(wStart, uStart)
            val endMax = max(wEnd, uEnd)
            val unionLocal = max(0L, endMax - startMin)
            union = unionLocal

            // Time where exactly one lens is integrating.
            nonOverlap = max(0L, (wideExposureTimeNs + ultraExposureTimeNs) - 2L * overlapLocal)

            // If disjoint, time where neither lens is integrating between the two windows.
            idleGap = max(0L, unionLocal - (overlapLocal + (nonOverlap ?: 0L)))

            val shorter = min(wideExposureTimeNs, ultraExposureTimeNs)
            val longer = max(wideExposureTimeNs, ultraExposureTimeNs)
            overlapPctShorter = if (shorter > 0) overlapLocal * 100.0 / shorter.toDouble() else null
            overlapPctLonger = if (longer > 0) overlapLocal * 100.0 / longer.toDouble() else null
        } else {
            overlap = null
            nonOverlap = null
            idleGap = null
            union = null
            overlapPctShorter = null
            overlapPctLonger = null
        }

        return PhotoSyncMetrics(
            wideSensorTimestampNs = wideSensorTimestampNs,
            ultraSensorTimestampNs = ultraSensorTimestampNs,
            wideExposureTimeNs = wideExposureTimeNs,
            ultraExposureTimeNs = ultraExposureTimeNs,
            deltaStartNs = delta,
            deltaStartAbsNs = deltaAbs,
            overlapNs = overlap,
            nonOverlapNs = nonOverlap,
            idleGapNs = idleGap,
            unionNs = union,
            overlapPctOfShorter = overlapPctShorter,
            overlapPctOfLonger = overlapPctLonger
        )
    }

    fun formatToast(metrics: PhotoSyncMetrics): String {
        // Show signed delta (wide - ultra), plus overlap if known.
        val deltaMs = metrics.deltaStartMs()
        val deltaAbsMs = metrics.deltaStartAbsMs()
        val overlapMs = metrics.overlapMs()

        val wExpMs = if (metrics.wideExposureTimeNs > 0) metrics.wideExposureTimeMs() else null
        val uExpMs = if (metrics.ultraExposureTimeNs > 0) metrics.ultraExposureTimeMs() else null

        return if (overlapMs != null && wExpMs != null && uExpMs != null) {
            val pctLong = metrics.overlapPctOfLonger
            val pctShort = metrics.overlapPctOfShorter
            val pctStr = if (pctShort != null && pctLong != null) {
                " (${"%.0f".format(Locale.US, pctShort)}% of shorter, ${"%.0f".format(Locale.US, pctLong)}% of longer)"
            } else {
                ""
            }

//            "Photo sync: Δstart=${"%.3f".format(Locale.US, deltaMs)}ms (|Δ|=${"%.3f".format(Locale.US, deltaAbsMs)}ms), " +
            "Δstart=${"%.3f".format(Locale.US, deltaMs)}ms, " +
//                    "overlap=${"%.3f".format(Locale.US, overlapMs)}ms$pctStr (exp w=${"%.2f".format(Locale.US, wExpMs)}ms u=${"%.2f".format(Locale.US, uExpMs)}ms)"
                    "overlap=${"%.3f".format(Locale.US, overlapMs)}ms" //$pctStr"

        } else {
//            "Photo sync: Δstart=${"%.3f".format(Locale.US, deltaMs)}ms (|Δ|=${"%.3f".format(Locale.US, deltaAbsMs)}ms)"
            "Photo sync: Δstart=${"%.3f".format(Locale.US, deltaMs)}ms"
        }
    }

    fun writeJson(
        context: Context,
        uri: Uri,
        captureId: String,
        wallTimeMs: Long,
        isRaw: Boolean,
        zoom2xEnabled: Boolean,
        widePhysicalId: String,
        ultraPhysicalId: String,
        wideResult: CaptureResult?,
        ultraResult: CaptureResult?,
        metrics: PhotoSyncMetrics
    ) {
        val resolver = context.contentResolver
        resolver.openOutputStream(uri)?.use { os ->
            JsonWriter(OutputStreamWriter(os, Charsets.UTF_8)).use { w ->
                w.setIndent("  ")
                w.beginObject()

                w.name("schemaVersion").value(SCHEMA_VERSION.toLong())
                w.name("captureId").value(captureId)
                w.name("wallTimeMs").value(wallTimeMs)
                w.name("isRaw").value(isRaw)
                w.name("zoom2xEnabled").value(zoom2xEnabled)

                w.name("device").beginObject()
                w.name("manufacturer").value(Build.MANUFACTURER)
                w.name("model").value(Build.MODEL)
                w.name("sdkInt").value(Build.VERSION.SDK_INT.toLong())
                w.endObject()

                w.name("camera").beginObject()
                w.name("widePhysicalId").value(widePhysicalId)
                w.name("ultraPhysicalId").value(ultraPhysicalId)
                w.endObject()

                w.name("wide").beginObject()
                w.name("sensorTimestampNs").value(metrics.wideSensorTimestampNs)
                w.name("sensorTimestampMs").value(metrics.wideSensorTimestampMs())
                w.name("exposureTimeNs").value(metrics.wideExposureTimeNs)
                w.name("exposureTimeMs").value(metrics.wideExposureTimeMs())
                writeLensDiagnostics(w, wideResult)
                w.endObject()

                w.name("ultra").beginObject()
                w.name("sensorTimestampNs").value(metrics.ultraSensorTimestampNs)
                w.name("sensorTimestampMs").value(metrics.ultraSensorTimestampMs())
                w.name("exposureTimeNs").value(metrics.ultraExposureTimeNs)
                w.name("exposureTimeMs").value(metrics.ultraExposureTimeMs())
                writeLensDiagnostics(w, ultraResult)
                w.endObject()

                w.name("sync").beginObject()
                w.name("deltaStartNs").value(metrics.deltaStartNs)
                w.name("deltaStartMs").value(metrics.deltaStartMs())
                w.name("deltaStartAbsNs").value(metrics.deltaStartAbsNs)
                w.name("deltaStartAbsMs").value(metrics.deltaStartAbsMs())
                if (metrics.overlapNs != null) {
                    w.name("overlapNs").value(metrics.overlapNs)
                    w.name("overlapMs").value(metrics.overlapMs() ?: 0.0)
                }
                if (metrics.nonOverlapNs != null) {
                    w.name("nonOverlapNs").value(metrics.nonOverlapNs)
                    w.name("nonOverlapMs").value(metrics.nonOverlapMs() ?: 0.0)
                }
                if (metrics.idleGapNs != null) {
                    w.name("idleGapNs").value(metrics.idleGapNs)
                    w.name("idleGapMs").value(metrics.idleGapMs() ?: 0.0)
                }
                if (metrics.unionNs != null) {
                    w.name("unionNs").value(metrics.unionNs)
                    w.name("unionMs").value(metrics.unionMs() ?: 0.0)
                }
                if (metrics.overlapPctOfShorter != null) {
                    w.name("overlapPctOfShorter").value(metrics.overlapPctOfShorter)
                }
                if (metrics.overlapPctOfLonger != null) {
                    w.name("overlapPctOfLonger").value(metrics.overlapPctOfLonger)
                }
                w.endObject()

                w.endObject()
            }
        } ?: throw IllegalStateException("openOutputStream failed for $uri")
    }

    private fun writeLensDiagnostics(w: JsonWriter, result: CaptureResult?) {
        if (result == null) return

        fun <T> get(key: CaptureResult.Key<T>): T? = try { result.get(key) } catch (_: Exception) { null }

        // Focus
        val focusDiopters = get(CaptureResult.LENS_FOCUS_DISTANCE)
        if (focusDiopters != null) {
            w.name("focusDistanceDiopters").value(focusDiopters.toDouble())
            val meters = if (focusDiopters > 1e-4f) (1.0 / focusDiopters.toDouble()) else null
            if (meters != null) w.name("focusDistanceMeters").value(meters)
        }

        // Lens
        get(CaptureResult.LENS_STATE)?.let { w.name("lensState").value(lensStateToString(it)) }
        get(CaptureResult.LENS_FOCAL_LENGTH)?.let { w.name("focalLengthMm").value(it.toDouble()) }
        get(CaptureResult.LENS_APERTURE)?.let { w.name("apertureFNumber").value(it.toDouble()) }
        get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)?.let { w.name("oisMode").value(oisModeToString(it)) }

        // Sensor / exposure
        get(CaptureResult.SENSOR_SENSITIVITY)?.let { w.name("iso").value(it.toLong()) }
        get(CaptureResult.SENSOR_FRAME_DURATION)?.let { w.name("frameDurationNs").value(it) }
        get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)?.let { w.name("rollingShutterSkewNs").value(it) }

        // AF / AE / AWB
        get(CaptureResult.CONTROL_AF_MODE)?.let { w.name("afMode").value(afModeToString(it)) }
        get(CaptureResult.CONTROL_AF_STATE)?.let { w.name("afState").value(afStateToString(it)) }

        get(CaptureResult.CONTROL_AE_MODE)?.let { w.name("aeMode").value(aeModeToString(it)) }
        get(CaptureResult.CONTROL_AE_STATE)?.let { w.name("aeState").value(aeStateToString(it)) }
        get(CaptureResult.CONTROL_AE_LOCK)?.let { w.name("aeLock").value(it) }
        get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)?.let { w.name("exposureCompensation").value(it.toLong()) }

        get(CaptureResult.CONTROL_AWB_MODE)?.let { w.name("awbMode").value(awbModeToString(it)) }
        get(CaptureResult.CONTROL_AWB_STATE)?.let { w.name("awbState").value(awbStateToString(it)) }
        get(CaptureResult.CONTROL_AWB_LOCK)?.let { w.name("awbLock").value(it) }
//        get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { gains ->
//            w.name("colorGains").beginArray()
//            w.value(gains.red().toDouble())
//            w.value(gains.greenEven().toDouble())
//            w.value(gains.greenOdd().toDouble())
//            w.value(gains.blue().toDouble())
//            w.endArray()
//        }

        // Stabilization (EIS is typically video-only, but log if present)
        get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)?.let { w.name("eisMode").value(eisModeToString(it)) }

        // Processing modes (helpful for debugging still quality differences)
        get(CaptureResult.NOISE_REDUCTION_MODE)?.let { w.name("noiseReduction").value(nrModeToString(it)) }
        get(CaptureResult.EDGE_MODE)?.let { w.name("edgeMode").value(edgeModeToString(it)) }
        get(CaptureResult.DISTORTION_CORRECTION_MODE)?.let { w.name("distortionCorrection").value(dcModeToString(it)) }

        // Geometry / zoom
        get(CaptureResult.CONTROL_ZOOM_RATIO)?.let { w.name("zoomRatio").value(it.toDouble()) }
        get(CaptureResult.SCALER_CROP_REGION)?.let { rect -> writeCropRegion(w, rect) }
    }

    private fun writeCropRegion(w: JsonWriter, rect: Rect) {
        w.name("cropRegion").beginObject()
        w.name("left").value(rect.left.toLong())
        w.name("top").value(rect.top.toLong())
        w.name("width").value(rect.width().toLong())
        w.name("height").value(rect.height().toLong())
        w.endObject()
    }

    private fun lensStateToString(v: Int): String = when (v) {
        CaptureResult.LENS_STATE_STATIONARY -> "stationary"
        CaptureResult.LENS_STATE_MOVING -> "moving"
        else -> v.toString()
    }

    private fun oisModeToString(v: Int): String = when (v) {
        CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_OFF -> "off"
        CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON -> "on"
        else -> v.toString()
    }

    private fun eisModeToString(v: Int): String = when (v) {
        CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_OFF -> "off"
        CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "on"
        else -> v.toString()
    }

    private fun afModeToString(v: Int): String = when (v) {
        CaptureResult.CONTROL_AF_MODE_OFF -> "off"
        CaptureResult.CONTROL_AF_MODE_AUTO -> "auto"
        CaptureResult.CONTROL_AF_MODE_MACRO -> "macro"
        CaptureResult.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "continuous_video"
        CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "continuous_picture"
        CaptureResult.CONTROL_AF_MODE_EDOF -> "edof"
        else -> v.toString()
    }

    private fun afStateToString(v: Int): String = when (v) {
        CaptureResult.CONTROL_AF_STATE_INACTIVE -> "inactive"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> "passive_scan"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "passive_focused"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "passive_unfocused"
        CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> "active_scan"
        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "focused_locked"
        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "not_focused_locked"
        else -> v.toString()
    }

    private fun aeModeToString(v: Int): String = when (v) {
        CaptureResult.CONTROL_AE_MODE_OFF -> "off"
        CaptureResult.CONTROL_AE_MODE_ON -> "on"
        CaptureResult.CONTROL_AE_MODE_ON_AUTO_FLASH -> "on_auto_flash"
        CaptureResult.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> "on_always_flash"
        CaptureResult.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> "on_auto_flash_redeye"
        else -> v.toString()
    }

    private fun aeStateToString(v: Int): String = when (v) {
        CaptureResult.CONTROL_AE_STATE_INACTIVE -> "inactive"
        CaptureResult.CONTROL_AE_STATE_SEARCHING -> "searching"
        CaptureResult.CONTROL_AE_STATE_CONVERGED -> "converged"
        CaptureResult.CONTROL_AE_STATE_LOCKED -> "locked"
        CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> "flash_required"
        CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> "precapture"
        else -> v.toString()
    }

    private fun awbModeToString(v: Int): String = when (v) {
        CaptureResult.CONTROL_AWB_MODE_OFF -> "off"
        CaptureResult.CONTROL_AWB_MODE_AUTO -> "auto"
        CaptureResult.CONTROL_AWB_MODE_INCANDESCENT -> "incandescent"
        CaptureResult.CONTROL_AWB_MODE_FLUORESCENT -> "fluorescent"
        CaptureResult.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "warm_fluorescent"
        CaptureResult.CONTROL_AWB_MODE_DAYLIGHT -> "daylight"
        CaptureResult.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "cloudy_daylight"
        CaptureResult.CONTROL_AWB_MODE_TWILIGHT -> "twilight"
        CaptureResult.CONTROL_AWB_MODE_SHADE -> "shade"
        else -> v.toString()
    }

    private fun awbStateToString(v: Int): String = when (v) {
        CaptureResult.CONTROL_AWB_STATE_INACTIVE -> "inactive"
        CaptureResult.CONTROL_AWB_STATE_SEARCHING -> "searching"
        CaptureResult.CONTROL_AWB_STATE_CONVERGED -> "converged"
        CaptureResult.CONTROL_AWB_STATE_LOCKED -> "locked"
        else -> v.toString()
    }

    private fun nrModeToString(v: Int): String = when (v) {
        CaptureResult.NOISE_REDUCTION_MODE_OFF -> "off"
        CaptureResult.NOISE_REDUCTION_MODE_FAST -> "fast"
        CaptureResult.NOISE_REDUCTION_MODE_HIGH_QUALITY -> "high_quality"
        CaptureResult.NOISE_REDUCTION_MODE_MINIMAL -> "minimal"
        CaptureResult.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG -> "zero_shutter_lag"
        else -> v.toString()
    }

    private fun edgeModeToString(v: Int): String = when (v) {
        CaptureResult.EDGE_MODE_OFF -> "off"
        CaptureResult.EDGE_MODE_FAST -> "fast"
        CaptureResult.EDGE_MODE_HIGH_QUALITY -> "high_quality"
        CaptureResult.EDGE_MODE_ZERO_SHUTTER_LAG -> "zero_shutter_lag"
        else -> v.toString()
    }

    private fun dcModeToString(v: Int): String = when (v) {
        CaptureResult.DISTORTION_CORRECTION_MODE_OFF -> "off"
        CaptureResult.DISTORTION_CORRECTION_MODE_FAST -> "fast"
        CaptureResult.DISTORTION_CORRECTION_MODE_HIGH_QUALITY -> "high_quality"
        else -> v.toString()
    }
}