package com.example.duallens3dcamera.logging

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.JsonWriter
import java.io.OutputStreamWriter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object StereoPhotoLogger {

    private const val SCHEMA_VERSION = 2

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
        widePhysicalId: String,
        ultraPhysicalId: String,
        wideImageUri: Uri,
        ultraImageUri: Uri,
        sbsUri: Uri?,
        wideImageTimestampNs: Long?,
        ultraImageTimestampNs: Long?,
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

                w.name("device").beginObject()
                w.name("manufacturer").value(Build.MANUFACTURER)
                w.name("model").value(Build.MODEL)
                w.name("sdkInt").value(Build.VERSION.SDK_INT.toLong())
                w.endObject()

                w.name("camera").beginObject()
                w.name("widePhysicalId").value(widePhysicalId)
                w.name("ultraPhysicalId").value(ultraPhysicalId)
                w.endObject()

                // useless
//                w.name("outputs").beginObject()
//                w.name("wideImageUri").value(wideImageUri.toString())
//                w.name("ultraImageUri").value(ultraImageUri.toString())
//                if (sbsUri != null) w.name("sbsUri").value(sbsUri.toString())
//                w.endObject()

                w.name("wide").beginObject()
                // image timestamp is meaningless because I think it's copied from wide
//                if (wideImageTimestampNs != null) {
//                    w.name("imageTimestampNs").value(wideImageTimestampNs)
//                    w.name("imageTimestampMs").value(wideImageTimestampNs / 1_000_000.0)
//                }
                w.name("sensorTimestampNs").value(metrics.wideSensorTimestampNs)
                w.name("sensorTimestampMs").value(metrics.wideSensorTimestampMs())
                w.name("exposureTimeNs").value(metrics.wideExposureTimeNs)
                w.name("exposureTimeMs").value(metrics.wideExposureTimeMs())
                w.endObject()

                w.name("ultra").beginObject()
                // image timestamp is meaningless because I think it's copied from wide
//                if (ultraImageTimestampNs != null) {
//                    w.name("imageTimestampNs").value(ultraImageTimestampNs)
//                    w.name("imageTimestampMs").value(ultraImageTimestampNs / 1_000_000.0)
//                }
                w.name("sensorTimestampNs").value(metrics.ultraSensorTimestampNs)
                w.name("sensorTimestampMs").value(metrics.ultraSensorTimestampMs())
                w.name("exposureTimeNs").value(metrics.ultraExposureTimeNs)
                w.name("exposureTimeMs").value(metrics.ultraExposureTimeMs())
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
}