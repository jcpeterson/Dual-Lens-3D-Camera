package com.example.duallens3dcamera.logging

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.JsonWriter
import android.util.Log
import android.util.Size
import java.io.OutputStreamWriter

/**
 * Collects per-frame camera metadata (wide + ultrawide) and optionally per-sample encoder timestamps.
 *
 * Two modes:
 *  - framesOnly=true  => ONLY logs {frameNumber, SENSOR_TIMESTAMP} for wide + ultrawide frames.
 *                       No encoder sample logs, no bufferLost logs, no exposure/iso/3A state logs.
 *  - framesOnly=false => Full mode (existing behavior).
 *
 * This logger stores rows in memory (simple + fast while recording) and flushes at stop.
 */
class StereoRecordingLogger(
    private val recordingId: String,
    private val logicalCameraId: String,
    private val widePhysicalId: String,
    private val ultraPhysicalId: String,
    private val recordSize: Size,
    private val targetFps: Int,
    private val videoBitrateBps: Int,
    private val eisEnabled: Boolean,
    private val orientationHintDegrees: Int,
    private val sensorTimestampSource: Int?,   // CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE
    private val sensorSyncType: Int?,          // CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE
    private val wideVideoUri: Uri,
    private val ultraVideoUri: Uri,
    private val framesOnly: Boolean
) {
    companion object {
        private const val TAG = "StereoRecLog"
        private const val SCHEMA_VERSION = 2

        // Minimal mode columns
        private val CAMERA_FRAME_COLUMNS_MIN = listOf(
            "frameNumber",
            "sensorTimestampNs"
        )

        // Full mode columns
        private val CAMERA_FRAME_COLUMNS_FULL = listOf(
            "frameNumber",
            "sensorTimestampNs",
            "exposureTimeNs",
            "exposureStartNs",
            "exposureEndNs",
            "frameDurationNs",
            "rollingShutterSkewNs",
            "sensitivityIso",
            "afState",
            "aeState",
            "awbState"
        )

        private val VIDEO_SAMPLE_COLUMNS = listOf(
            "sampleIndex",
            "codecPresentationTimeUs",
            "muxerPresentationTimeUs",
            "sizeBytes",
            "flags",
            "writeDurationNs"
        )

        private val AUDIO_SAMPLE_COLUMNS = listOf(
            "sampleIndex",
            "codecPresentationTimeUs",
            "muxerPresentationTimeUs",
            "sizeBytes",
            "flags",
            "writeDurationNs"
        )

        private val BUFFER_LOST_COLUMNS = listOf(
            "frameNumber",
            "physicalCameraId",
            "target"
        )
    }

    private val lock = Any()

    private val startWallTimeMs: Long = System.currentTimeMillis()
    private val startElapsedNs: Long = SystemClock.elapsedRealtimeNanos()
    private var stopWallTimeMs: Long = 0L
    private var stopElapsedNs: Long = 0L

    /**
     * Minimal mode row: [frameNumber, sensorTimestampNs]
     * Full mode row stored internally: [frameNumber, sensorTs, exposure, frameDur, rollingSkew, iso, af, ae, awb]
     * Full mode writes exposureStart/exposureEnd at write time.
     */
    private val wideFrameRows = ArrayList<LongArray>(1024)
    private val ultraFrameRows = ArrayList<LongArray>(1024)

    /**
     * Full mode only:
     * [sampleIndex, codecPtsUs, muxerPtsUs, sizeBytes, flags, writeDurationNs]
     */
    private val wideVideoSampleRows = ArrayList<LongArray>(1024)
    private val ultraVideoSampleRows = ArrayList<LongArray>(1024)
    private val audioSampleRows = ArrayList<LongArray>(1024)

    /** Full mode only. */
    private val bufferLostRows = ArrayList<Array<Any>>(64)

    private var wideVideoSampleIndex = 0L
    private var ultraVideoSampleIndex = 0L
    private var audioSampleIndex = 0L

    fun markStopped() {
        stopWallTimeMs = System.currentTimeMillis()
        stopElapsedNs = SystemClock.elapsedRealtimeNanos()
    }

    fun onPhysicalCaptureResult(physicalId: String, frameNumber: Long, result: CaptureResult) {
        val sensorTs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: -1L

        val row = if (framesOnly) {
            longArrayOf(frameNumber, sensorTs)
        } else {
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: -1L
            val frameDur = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: -1L
            val skew = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: -1L
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)?.toLong() ?: -1L
            val af = result.get(CaptureResult.CONTROL_AF_STATE)?.toLong() ?: -1L
            val ae = result.get(CaptureResult.CONTROL_AE_STATE)?.toLong() ?: -1L
            val awb = result.get(CaptureResult.CONTROL_AWB_STATE)?.toLong() ?: -1L
            longArrayOf(frameNumber, sensorTs, exposure, frameDur, skew, iso, af, ae, awb)
        }

        synchronized(lock) {
            when (physicalId) {
                widePhysicalId -> wideFrameRows.add(row)
                ultraPhysicalId -> ultraFrameRows.add(row)
                else -> {
                    // Ignore unexpected physical IDs
                }
            }
        }
    }

    fun onBufferLost(physicalId: String, target: String, frameNumber: Long) {
        if (framesOnly) return
        synchronized(lock) {
            bufferLostRows.add(arrayOf(frameNumber, physicalId, target))
        }
    }

    fun onWideVideoSample(codecPtsUs: Long, muxerPtsUs: Long, sizeBytes: Int, flags: Int, writeDurationNs: Long) {
        if (framesOnly) return
        val row = longArrayOf(
            wideVideoSampleIndex++,
            codecPtsUs,
            muxerPtsUs,
            sizeBytes.toLong(),
            flags.toLong(),
            writeDurationNs
        )
        synchronized(lock) { wideVideoSampleRows.add(row) }
    }

    fun onUltraVideoSample(codecPtsUs: Long, muxerPtsUs: Long, sizeBytes: Int, flags: Int, writeDurationNs: Long) {
        if (framesOnly) return
        val row = longArrayOf(
            ultraVideoSampleIndex++,
            codecPtsUs,
            muxerPtsUs,
            sizeBytes.toLong(),
            flags.toLong(),
            writeDurationNs
        )
        synchronized(lock) { ultraVideoSampleRows.add(row) }
    }

    fun onAudioSample(codecPtsUs: Long, muxerPtsUs: Long, sizeBytes: Int, flags: Int, writeDurationNs: Long) {
        if (framesOnly) return
        val row = longArrayOf(
            audioSampleIndex++,
            codecPtsUs,
            muxerPtsUs,
            sizeBytes.toLong(),
            flags.toLong(),
            writeDurationNs
        )
        synchronized(lock) { audioSampleRows.add(row) }
    }

    fun writeToUri(context: Context, logUri: Uri) {
        // Snapshot under lock so writer doesn't race with late callbacks.
        val snapshot: Snapshot = synchronized(lock) {
            Snapshot(
                wideFrames = wideFrameRows.toList(),
                ultraFrames = ultraFrameRows.toList(),
                wideSamples = wideVideoSampleRows.toList(),
                ultraSamples = ultraVideoSampleRows.toList(),
                audioSamples = audioSampleRows.toList(),
                bufferLost = bufferLostRows.toList()
            )
        }

        try {
            val resolver = context.contentResolver
            resolver.openOutputStream(logUri)?.use { os ->
                JsonWriter(OutputStreamWriter(os, Charsets.UTF_8)).use { w ->
                    w.setIndent("  ")

                    w.beginObject()
                    w.name("schemaVersion").value(SCHEMA_VERSION.toLong())
                    w.name("recordingId").value(recordingId)
                    w.name("loggingMode").value(if (framesOnly) "framesOnly" else "full")

                    // Small, stable metadata (costs nothing at runtime).
                    w.name("device").beginObject()
                    w.name("manufacturer").value(Build.MANUFACTURER)
                    w.name("model").value(Build.MODEL)
                    w.name("device").value(Build.DEVICE)
                    w.name("sdkInt").value(Build.VERSION.SDK_INT.toLong())
                    w.name("release").value(Build.VERSION.RELEASE)
                    w.endObject()

                    w.name("camera").beginObject()
                    w.name("logicalCameraId").value(logicalCameraId)
                    w.name("widePhysicalId").value(widePhysicalId)
                    w.name("ultraPhysicalId").value(ultraPhysicalId)
                    w.name("recordSize").beginObject()
                    w.name("width").value(recordSize.width.toLong())
                    w.name("height").value(recordSize.height.toLong())
                    w.endObject()
                    w.name("targetFps").value(targetFps.toLong())
                    w.name("videoBitrateBps").value(videoBitrateBps.toLong())
                    w.name("eisEnabled").value(eisEnabled)
                    w.name("orientationHintDegrees").value(orientationHintDegrees.toLong())
                    w.name("sensorTimestampSource").value(timestampSourceToString(sensorTimestampSource))
                    w.name("logicalSensorSyncType").value(syncTypeToString(sensorSyncType))
                    w.endObject()

                    w.name("outputs").beginObject()
                    w.name("wideVideoUri").value(wideVideoUri.toString())
                    w.name("ultraVideoUri").value(ultraVideoUri.toString())
                    w.endObject()

                    w.name("time").beginObject()
                    w.name("startWallTimeMs").value(startWallTimeMs)
                    w.name("startElapsedRealtimeNs").value(startElapsedNs)
                    if (stopWallTimeMs != 0L) w.name("stopWallTimeMs").value(stopWallTimeMs)
                    if (stopElapsedNs != 0L) w.name("stopElapsedRealtimeNs").value(stopElapsedNs)
                    w.endObject()

                    // Summary (first timestamps + offsets)
                    val wideFirstTs = firstValidSensorTimestamp(snapshot.wideFrames)
                    val ultraFirstTs = firstValidSensorTimestamp(snapshot.ultraFrames)

                    w.name("summary").beginObject()
                    w.name("wideFrameCount").value(snapshot.wideFrames.size.toLong())
                    w.name("ultraFrameCount").value(snapshot.ultraFrames.size.toLong())
                    if (!framesOnly) {
                        w.name("wideVideoSampleCount").value(snapshot.wideSamples.size.toLong())
                        w.name("ultraVideoSampleCount").value(snapshot.ultraSamples.size.toLong())
                        w.name("audioSampleCount").value(snapshot.audioSamples.size.toLong())
                        w.name("bufferLostCount").value(snapshot.bufferLost.size.toLong())
                    }
                    if (wideFirstTs != null) w.name("wideFirstSensorTimestampNs").value(wideFirstTs)
                    if (ultraFirstTs != null) w.name("ultraFirstSensorTimestampNs").value(ultraFirstTs)
                    if (wideFirstTs != null && ultraFirstTs != null) {
                        w.name("ultraMinusWideFirstSensorTimestampNs").value(ultraFirstTs - wideFirstTs)
                    }
                    w.endObject()

                    // Field definitions (minimal vs full)
                    w.name("definitions").beginObject()
                    w.name("frameNumber").value("CaptureResult.getFrameNumber(): monotonically increasing frame index in the capture session.")
                    w.name("sensorTimestampNs").value(
                        "CaptureResult.SENSOR_TIMESTAMP (android.sensor.timestamp): start of exposure of first sensor row; nanoseconds; timebase depends on SENSOR_INFO_TIMESTAMP_SOURCE."
                    )
                    if (!framesOnly) {
                        w.name("exposureTimeNs").value(
                            "CaptureResult.SENSOR_EXPOSURE_TIME (android.sensor.exposureTime): exposure duration; nanoseconds."
                        )
                        w.name("exposureStartNs").value("Defined here as sensorTimestampNs.")
                        w.name("exposureEndNs").value("Defined here as exposureStartNs + exposureTimeNs.")
                        w.name("frameDurationNs").value(
                            "CaptureResult.SENSOR_FRAME_DURATION: duration from start of frame readout to start of next; nanoseconds."
                        )
                        w.name("rollingShutterSkewNs").value(
                            "CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW: time between first-row and last-row exposure start; nanoseconds."
                        )
                        w.name("codecPresentationTimeUs").value(
                            "MediaCodec.BufferInfo.presentationTimeUs produced by encoder; microseconds."
                        )
                        w.name("muxerPresentationTimeUs").value(
                            "PTS passed to MediaMuxer.writeSampleData; microseconds. This app normalizes each track to start at 0."
                        )
                        w.name("flags").value("MediaCodec.BufferInfo.flags bitmask (e.g., KEY_FRAME, END_OF_STREAM).")
                        w.name("writeDurationNs").value(
                            "Time spent around muxer.write*Sample calls (includes pending queue + MediaMuxer I/O); nanoseconds."
                        )
                        w.name("bufferLost").value("CameraCaptureSession.CaptureCallback.onCaptureBufferLost events.")
                    }
                    w.endObject()

                    // Data tables
                    w.name("wide").beginObject()
                    writeCameraFramesTable(w, snapshot.wideFrames)
                    if (!framesOnly) {
                        writeSamplesTable(w, "videoSamples", VIDEO_SAMPLE_COLUMNS, snapshot.wideSamples)
                    }
                    w.endObject()

                    w.name("ultrawide").beginObject()
                    writeCameraFramesTable(w, snapshot.ultraFrames)
                    if (!framesOnly) {
                        writeSamplesTable(w, "videoSamples", VIDEO_SAMPLE_COLUMNS, snapshot.ultraSamples)
                    }
                    w.endObject()

                    if (!framesOnly) {
                        w.name("audio").beginObject()
                        writeSamplesTable(w, "audioSamples", AUDIO_SAMPLE_COLUMNS, snapshot.audioSamples)
                        w.endObject()

                        w.name("bufferLost").beginObject()
                        w.name("columns").beginArray()
                        for (c in BUFFER_LOST_COLUMNS) w.value(c)
                        w.endArray()
                        w.name("rows").beginArray()
                        for (row in snapshot.bufferLost) {
                            w.beginArray()
                            w.value(row[0] as Long)
                            w.value(row[1] as String)
                            w.value(row[2] as String)
                            w.endArray()
                        }
                        w.endArray()
                        w.endObject()
                    }

                    w.endObject()
                }
            } ?: run {
                Log.e(TAG, "Failed to openOutputStream for $logUri")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed writing log JSON", t)
        }
    }

    private data class Snapshot(
        val wideFrames: List<LongArray>,
        val ultraFrames: List<LongArray>,
        val wideSamples: List<LongArray>,
        val ultraSamples: List<LongArray>,
        val audioSamples: List<LongArray>,
        val bufferLost: List<Array<Any>>
    )

    private fun writeCameraFramesTable(w: JsonWriter, rows: List<LongArray>) {
        w.name("cameraFrames").beginObject()

        val cols = if (framesOnly) CAMERA_FRAME_COLUMNS_MIN else CAMERA_FRAME_COLUMNS_FULL

        w.name("columns").beginArray()
        for (c in cols) w.value(c)
        w.endArray()

        w.name("rows").beginArray()
        if (framesOnly) {
            for (r in rows) {
                // [frameNumber, sensorTimestampNs]
                w.beginArray()
                w.value(r[0])
                w.value(r[1])
                w.endArray()
            }
        } else {
            for (r in rows) {
                // internal: [frameNumber, sensorTs, exposure, frameDur, skew, iso, af, ae, awb]
                val frameNumber = r[0]
                val sensorTs = r[1]
                val exposure = r[2]
                val frameDur = r[3]
                val skew = r[4]
                val iso = r[5]
                val af = r[6]
                val ae = r[7]
                val awb = r[8]

                val exposureStart = if (sensorTs > 0) sensorTs else -1L
                val exposureEnd = if (sensorTs > 0 && exposure > 0) sensorTs + exposure else -1L

                w.beginArray()
                w.value(frameNumber)
                w.value(sensorTs)
                w.value(exposure)
                w.value(exposureStart)
                w.value(exposureEnd)
                w.value(frameDur)
                w.value(skew)
                w.value(iso)
                w.value(af)
                w.value(ae)
                w.value(awb)
                w.endArray()
            }
        }
        w.endArray()
        w.endObject()
    }

    private fun writeSamplesTable(w: JsonWriter, name: String, columns: List<String>, rows: List<LongArray>) {
        w.name(name).beginObject()
        w.name("columns").beginArray()
        for (c in columns) w.value(c)
        w.endArray()

        w.name("rows").beginArray()
        for (r in rows) {
            w.beginArray()
            w.value(r[0]) // sampleIndex
            w.value(r[1]) // codecPtsUs
            w.value(r[2]) // muxerPtsUs
            w.value(r[3]) // sizeBytes
            w.value(r[4]) // flags
            w.value(r[5]) // writeDurationNs
            w.endArray()
        }
        w.endArray()
        w.endObject()
    }

    private fun firstValidSensorTimestamp(rows: List<LongArray>): Long? {
        for (r in rows) {
            // index 1 is sensorTimestampNs in both modes
            val ts = r[1]
            if (ts > 0L) return ts
        }
        return null
    }

    private fun timestampSourceToString(v: Int?): String = when (v) {
        null -> "UNKNOWN"
        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "UNKNOWN"
        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
        else -> "OTHER($v)"
    }

    private fun syncTypeToString(v: Int?): String = when (v) {
        null -> "UNKNOWN"
        CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_APPROXIMATE -> "APPROXIMATE"
        CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_CALIBRATED -> "CALIBRATED"
        else -> "OTHER($v)"
    }
}
