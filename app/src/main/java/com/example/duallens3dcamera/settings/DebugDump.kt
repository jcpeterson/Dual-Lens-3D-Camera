package com.example.duallens3dcamera.settings

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.JsonWriter
import android.util.Range
import android.util.Size
import com.example.duallens3dcamera.camera.LensDiscovery
import com.example.duallens3dcamera.media.MediaStoreUtils
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugDump {

    private const val SCHEMA_VERSION = 1

    data class DumpResult(val uri: android.net.Uri, val displayName: String)

    fun writeOneTimeDump(context: Context): DumpResult {
        val nowMs = System.currentTimeMillis()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(nowMs))
        val displayName = "dump_${ts}_device_camera.json"

        val uri = MediaStoreUtils.createPendingJson(context, displayName, nowMs)

        val resolver = context.contentResolver
        try {
            resolver.openOutputStream(uri)?.use { os ->
                JsonWriter(OutputStreamWriter(os, Charsets.UTF_8)).use { w ->
                    w.setIndent("  ")
                    w.beginObject()

                    w.name("schemaVersion").value(SCHEMA_VERSION.toLong())
                    w.name("wallTimeMs").value(nowMs)
                    w.name("generatedAt").value(ts)

                    writeAppInfo(context, w)
                    writeDeviceInfo(w)
                    writeCameraInfo(context, w)

                    w.endObject()
                }
            } ?: throw IllegalStateException("openOutputStream failed for $uri")

            MediaStoreUtils.finalizePending(resolver, uri)
            return DumpResult(uri = uri, displayName = displayName)
        } catch (e: Exception) {
            // If anything fails, delete the pending entry so it doesn't linger.
            try { MediaStoreUtils.delete(resolver, uri) } catch (_: Exception) {}
            throw e
        }
    }

    private fun writeAppInfo(context: Context, w: JsonWriter) {
        w.name("app").beginObject()
        w.name("packageName").value(context.packageName)

        val pm = context.packageManager
        val pkg = context.packageName
        try {
            val pInfo = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }

            w.name("versionName").value(pInfo.versionName ?: "")
            val versionCode = if (Build.VERSION.SDK_INT >= 28) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            w.name("versionCode").value(versionCode)
        } catch (e: Exception) {
            w.name("versionName").value("")
            w.name("versionCode").value(0)
            w.name("versionError").value(e.message ?: e.toString())
        }

        w.endObject()
    }

    private fun writeDeviceInfo(w: JsonWriter) {
        w.name("device").beginObject()

        w.name("manufacturer").value(Build.MANUFACTURER)
        w.name("brand").value(Build.BRAND)
        w.name("model").value(Build.MODEL)
        w.name("device").value(Build.DEVICE)
        w.name("product").value(Build.PRODUCT)
        w.name("hardware").value(Build.HARDWARE)

        w.name("sdkInt").value(Build.VERSION.SDK_INT.toLong())
        w.name("release").value(Build.VERSION.RELEASE ?: "")
        w.name("incremental").value(Build.VERSION.INCREMENTAL ?: "")

        w.endObject()
    }

    private fun writeCameraInfo(context: Context, w: JsonWriter) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val rig = LensDiscovery.discoverStereoRig(cm)

        w.name("stereoRig").beginObject()
        if (rig == null) {
            w.name("found").value(false)
        } else {
            w.name("found").value(true)
            w.name("logicalId").value(rig.logicalRearId)
            w.name("wide1xId").value(rig.wide1xId)
            w.name("wide2xId").value(rig.wide2xId)
            w.name("ultraId").value(rig.ultraId)

            // Add a small “high value” summary: common PRIVATE output sizes between wide+ultra
            try {
                val wideMap = cm.getCameraCharacteristics(rig.wide1xId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val ultraMap = cm.getCameraCharacteristics(rig.ultraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

                val widePrivate = wideMap?.getOutputSizes(ImageFormat.PRIVATE)?.toSet().orEmpty()
                val ultraPrivate = ultraMap?.getOutputSizes(ImageFormat.PRIVATE)?.toSet().orEmpty()
                val commonPrivate = widePrivate.intersect(ultraPrivate).sortedWith(compareBy({ it.width }, { it.height }))

                w.name("commonPrivateSizes").beginArray()
                for (s in commonPrivate) w.value("${s.width}x${s.height}")
                w.endArray()

                // More readable summary: common PRIVATE sizes + approximate max FPS per lens (from min frame duration).
                w.name("commonPrivateSizeDetails").beginArray()
                for (s in commonPrivate) {
                    w.beginObject()
                    w.name("size").value("${s.width}x${s.height}")
                    w.name("aspect").value(aspectLabel(s))

                    val wDur = safeMinFrameDurationNs(wideMap, ImageFormat.PRIVATE, s)
                    val uDur = safeMinFrameDurationNs(ultraMap, ImageFormat.PRIVATE, s)

                    if (wDur > 0) {
                        w.name("wideMinFrameDurationNs").value(wDur)
                        w.name("wideApproxMaxFps").value(1_000_000_000.0 / wDur.toDouble())
                    }
                    if (uDur > 0) {
                        w.name("ultraMinFrameDurationNs").value(uDur)
                        w.name("ultraApproxMaxFps").value(1_000_000_000.0 / uDur.toDouble())
                    }
                    if (wDur > 0 && uDur > 0) {
                        val commonMax = minOf(
                            1_000_000_000.0 / wDur.toDouble(),
                            1_000_000_000.0 / uDur.toDouble()
                        )
                        w.name("commonApproxMaxFps").value(commonMax)
                    }

                    w.endObject()
                }
                w.endArray()

                val widePrev = wideMap?.getOutputSizes(SurfaceTexture::class.java)?.toSet().orEmpty()
                val ultraPrev = ultraMap?.getOutputSizes(SurfaceTexture::class.java)?.toSet().orEmpty()
                val commonPrev = widePrev.intersect(ultraPrev).sortedWith(compareBy({ it.width }, { it.height }))

                w.name("commonPreviewSizes").beginArray()
                for (s in commonPrev) w.value("${s.width}x${s.height}")
                w.endArray()

                w.name("commonPreviewSizeDetails").beginArray()
                for (s in commonPrev) {
                    w.beginObject()
                    w.name("size").value("${s.width}x${s.height}")
                    w.name("aspect").value(aspectLabel(s))

                    val wDur = safeMinFrameDurationNs(wideMap, SurfaceTexture::class.java, s)
                    val uDur = safeMinFrameDurationNs(ultraMap, SurfaceTexture::class.java, s)

                    if (wDur > 0) {
                        w.name("wideMinFrameDurationNs").value(wDur)
                        w.name("wideApproxMaxFps").value(1_000_000_000.0 / wDur.toDouble())
                    }
                    if (uDur > 0) {
                        w.name("ultraMinFrameDurationNs").value(uDur)
                        w.name("ultraApproxMaxFps").value(1_000_000_000.0 / uDur.toDouble())
                    }
                    if (wDur > 0 && uDur > 0) {
                        val commonMax = minOf(
                            1_000_000_000.0 / wDur.toDouble(),
                            1_000_000_000.0 / uDur.toDouble()
                        )
                        w.name("commonApproxMaxFps").value(commonMax)
                    }

                    w.endObject()
                }
                w.endArray()
            } catch (e: Exception) {
                w.name("commonSizeError").value(e.message ?: e.toString())
            }
        }
        w.endObject()

        val cameraIdList = cm.cameraIdList.toList().sorted()

        // Raw CameraManager list (often only logical cameras).
        w.name("cameraIdList").beginArray()
        cameraIdList.forEach { w.value(it) }
        w.endArray()

        // Superset: logical IDs + physical IDs + rig IDs (so we dump the lenses we actually use).
        val allIds = linkedSetOf<String>()
        allIds.addAll(cameraIdList)

        // Physical IDs referenced by logical cameras
        for (logicalId in cameraIdList) {
            try {
                val logicalChars = cm.getCameraCharacteristics(logicalId)
                allIds.addAll(logicalChars.physicalCameraIds?.toList().orEmpty())
            } catch (_: Exception) {}
        }

        // Rig IDs (wide/ultra/tele) if discovered
        if (rig != null) {
            allIds.add(rig.logicalRearId)
            allIds.add(rig.wide1xId)
            allIds.add(rig.ultraId)
            rig.wide2xId?.let { allIds.add(it) }
        }

        val allIdsSorted = allIds.toList().sorted()

        w.name("allCameraIdsDumped").beginArray()
        allIdsSorted.forEach { w.value(it) }
        w.endArray()

        w.name("cameras").beginArray()
        for (id in allIdsSorted) {
            val c = try {
                cm.getCameraCharacteristics(id)
            } catch (e: Exception) {
                w.beginObject()
                w.name("id").value(id)
                w.name("inCameraIdList").value(cameraIdList.contains(id))
                w.name("error").value(e.message ?: e.toString())
                w.endObject()
                continue
            }

            w.beginObject()
            w.name("id").value(id)
            w.name("inCameraIdList").value(cameraIdList.contains(id))
            w.name("lensFacing").value(lensFacingToString(c.get(CameraCharacteristics.LENS_FACING)))
            w.name("hardwareLevel").value(hardwareLevelToString(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)))
            w.name("sensorOrientation").value((c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: -1).toLong())
            w.name("timestampSource").value(timestampSourceToString(c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)))

            val focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            if (focalLengths != null) {
                w.name("availableFocalLengthsMm").beginArray()
                for (f in focalLengths) w.value(f.toDouble())
                w.endArray()
            }

            val sensorSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (sensorSize != null) {
                w.name("sensorPhysicalSizeMm").beginObject()
                w.name("widthMm").value(sensorSize.width.toDouble())
                w.name("heightMm").value(sensorSize.height.toDouble())
                w.endObject()
            }

            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
            w.name("capabilities").beginArray()
            for (cap in caps) w.value(capabilityToString(cap))
            w.endArray()

            val phys = c.physicalCameraIds?.toList().orEmpty()
            w.name("physicalCameraIds").beginArray()
            for (pid in phys) w.value(pid)
            w.endArray()

            val syncType = c.get(CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE)
            if (syncType != null) {
                w.name("logicalMultiCameraSyncType").value(syncType.toLong())
            }

            val fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
            w.name("aeTargetFpsRanges").beginArray()
            for (r in fpsRanges) {
                w.beginObject()
                w.name("lower").value(r.lower.toLong())
                w.name("upper").value(r.upper.toLong())
                w.endObject()
            }
            w.endArray()

            val zoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            if (zoom != null) w.name("maxDigitalZoom").value(zoom.toDouble())

            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map != null) {
                w.name("streams").beginObject()

                writeFormatSizes(w, map, "private", ImageFormat.PRIVATE)
                writeFormatSizes(w, map, "jpeg", ImageFormat.JPEG)
                writeFormatSizes(w, map, "yuv420", ImageFormat.YUV_420_888)
                writeFormatSizes(w, map, "rawSensor", ImageFormat.RAW_SENSOR)

                // Preview (SurfaceTexture) sizes are often the ones that matter for session setup.
                writeClassSizes(w, map, "surfaceTexture", SurfaceTexture::class.java)

                w.endObject()
            }

            // Mandatory stream combos (decoded to a readable structure).
            // NOTE: This is a baseline list (not an exhaustive list of all valid session combinations).
            writeMandatoryStreamCombinations(w, c)

            w.endObject()
        }
        w.endArray()
    }

    private fun writeFormatSizes(w: JsonWriter, map: StreamConfigurationMap, name: String, format: Int) {
        val sizes = map.getOutputSizes(format)?.toList().orEmpty()
        w.name(name).beginArray()
        for (s in sizes.sortedWith(compareBy({ it.width }, { it.height }))) {
            w.beginObject()
            w.name("w").value(s.width.toLong())
            w.name("h").value(s.height.toLong())

            val minDurNs = try { map.getOutputMinFrameDuration(format, s) } catch (_: Exception) { 0L }
            val stallNs = try { map.getOutputStallDuration(format, s) } catch (_: Exception) { 0L }

            if (minDurNs > 0) {
                val maxFps = (1_000_000_000.0 / minDurNs).coerceAtLeast(0.0)
                w.name("minFrameDurationNs").value(minDurNs)
                w.name("approxMaxFps").value(maxFps)
            }
            if (stallNs > 0) w.name("stallDurationNs").value(stallNs)

            w.endObject()
        }
        w.endArray()
    }

    private fun writeClassSizes(w: JsonWriter, map: StreamConfigurationMap, name: String, klass: Class<*>) {
        val sizes = map.getOutputSizes(klass)?.toList().orEmpty()
        w.name(name).beginArray()
        for (s in sizes.sortedWith(compareBy({ it.width }, { it.height }))) {
            w.beginObject()
            w.name("w").value(s.width.toLong())
            w.name("h").value(s.height.toLong())

            val minDurNs = try { map.getOutputMinFrameDuration(klass, s) } catch (_: Exception) { 0L }
            if (minDurNs > 0) {
                val maxFps = (1_000_000_000.0 / minDurNs).coerceAtLeast(0.0)
                w.name("minFrameDurationNs").value(minDurNs)
                w.name("approxMaxFps").value(maxFps)
            }

            w.endObject()
        }
        w.endArray()
    }

    // ---------- Helpers for stereo summary ----------

    private fun safeMinFrameDurationNs(map: StreamConfigurationMap?, format: Int, size: Size): Long {
        if (map == null) return 0L
        return try { map.getOutputMinFrameDuration(format, size) } catch (_: Exception) { 0L }
    }

    private fun safeMinFrameDurationNs(map: StreamConfigurationMap?, klass: Class<*>, size: Size): Long {
        if (map == null) return 0L
        return try { map.getOutputMinFrameDuration(klass, size) } catch (_: Exception) { 0L }
    }

    private fun aspectLabel(size: Size): String {
        // Simple reduced ratio string like "4:3", "16:9", etc.
        val w = size.width
        val h = size.height
        if (w <= 0 || h <= 0) return "unknown"
        val g = gcd(w, h)
        return "${w / g}:${h / g}"
    }

    private fun gcd(a0: Int, b0: Int): Int {
        var a = kotlin.math.abs(a0)
        var b = kotlin.math.abs(b0)
        while (b != 0) {
            val t = a % b
            a = b
            b = t
        }
        return if (a == 0) 1 else a
    }

    // ---------- Mandatory stream combination decoding ----------

    private data class MandatoryStreamInfo(
        val format: Int?,
        val size: Size?,
        val isInput: Boolean?,
        val streamUseCase: Long?
    )

    private fun writeMandatoryStreamCombinations(w: JsonWriter, c: CameraCharacteristics) {
        val mandatory = c.get(CameraCharacteristics.SCALER_MANDATORY_STREAM_COMBINATIONS)

        w.name("mandatoryStreamCombinations").beginArray()

        if (mandatory == null) {
            w.endArray()
            return
        }

        for ((idx, combo) in mandatory.withIndex()) {
            w.beginObject()
            w.name("index").value(idx.toLong())

            val streams = decodeMandatoryStreams(combo)
            if (streams.isEmpty()) {
                // Fallback if decoding fails on some devices/SDKs
                w.name("raw").value(combo.toString())
            } else {
                w.name("streams").beginArray()
                for (s in streams) {
                    w.beginObject()

                    if (s.format != null) {
                        w.name("format").value(formatToString(s.format))
                        w.name("formatCode").value(s.format.toLong())
                    }

                    if (s.size != null) {
                        w.name("w").value(s.size.width.toLong())
                        w.name("h").value(s.size.height.toLong())
                        w.name("size").value("${s.size.width}x${s.size.height}")
                        w.name("aspect").value(aspectLabel(s.size))
                    }

                    s.isInput?.let { w.name("isInput").value(it) }
                    s.streamUseCase?.let { w.name("streamUseCase").value(it) }

                    w.endObject()
                }
                w.endArray()
            }

            w.endObject()
        }

        w.endArray()
    }

    private fun decodeMandatoryStreams(combo: Any): List<MandatoryStreamInfo> {
        val streamsObj =
            callNoArg(combo, "getStreams")
                ?: callNoArg(combo, "getStreamsInformation")
                ?: callNoArg(combo, "getStreamInformation")
                ?: callNoArg(combo, "getStreamsInfo")

        val list: List<*>? = when (streamsObj) {
            is List<*> -> streamsObj
            is Array<*> -> streamsObj.toList()
            else -> null
        }

        if (list == null) return emptyList()

        return list.mapNotNull { si ->
            if (si == null) return@mapNotNull null

            val fmt = (callNoArg(si, "getFormat") as? Int) ?: getFieldInt(si, "mFormat")

            val size = (callNoArg(si, "getSize") as? Size) ?: run {
                val w = (callNoArg(si, "getWidth") as? Int) ?: getFieldInt(si, "mWidth")
                val h = (callNoArg(si, "getHeight") as? Int) ?: getFieldInt(si, "mHeight")
                if (w != null && h != null) Size(w, h) else null
            }

            val isInput =
                (callNoArg(si, "isInput") as? Boolean)
                    ?: (callNoArg(si, "getIsInput") as? Boolean)
                    ?: (callNoArg(si, "isInputStream") as? Boolean)
                    ?: getFieldBoolean(si, "mIsInput")

            val useCaseAny = callNoArg(si, "getStreamUseCase") ?: callNoArg(si, "getUseCase")
            val useCase = when (useCaseAny) {
                is Long -> useCaseAny
                is Int -> useCaseAny.toLong()
                else -> null
            }

            MandatoryStreamInfo(
                format = fmt,
                size = size,
                isInput = isInput,
                streamUseCase = useCase
            )
        }
    }

    private fun formatToString(format: Int): String = when (format) {
        ImageFormat.PRIVATE -> "PRIVATE"
        ImageFormat.JPEG -> "JPEG"
        ImageFormat.YUV_420_888 -> "YUV_420_888"
        ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
        else -> "fmt_$format"
    }

    private fun callNoArg(obj: Any, methodName: String): Any? {
        return try {
            val m = obj.javaClass.methods.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
            m?.invoke(obj)
        } catch (_: Exception) {
            null
        }
    }

    private fun getFieldInt(obj: Any, fieldName: String): Int? {
        return try {
            val f = obj.javaClass.getDeclaredField(fieldName)
            f.isAccessible = true
            f.getInt(obj)
        } catch (_: Exception) {
            null
        }
    }

    private fun getFieldBoolean(obj: Any, fieldName: String): Boolean? {
        return try {
            val f = obj.javaClass.getDeclaredField(fieldName)
            f.isAccessible = true
            f.getBoolean(obj)
        } catch (_: Exception) {
            null
        }
    }

    private fun lensFacingToString(v: Int?): String = when (v) {
        CameraCharacteristics.LENS_FACING_BACK -> "back"
        CameraCharacteristics.LENS_FACING_FRONT -> "front"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
        else -> "unknown"
    }

    private fun hardwareLevelToString(v: Int?): String = when (v) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "legacy"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "limited"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "full"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "level_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "external"
        else -> "unknown"
    }

    private fun timestampSourceToString(v: Int?): String = when (v) {
        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "realtime"
        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "unknown"
        else -> "unknown"
    }

    private fun capabilityToString(cap: Int): String = when (cap) {
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "backward_compatible"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "manual_sensor"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "manual_post_processing"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "raw"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "private_reprocessing"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "read_sensor_settings"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "burst_capture"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "yuv_reprocessing"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "depth_output"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "high_speed_video"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "motion_tracking"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "logical_multi_camera"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "monochrome"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "secure_image_data"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "system_camera"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "offline_processing"
        else -> "cap_$cap"
    }
}
