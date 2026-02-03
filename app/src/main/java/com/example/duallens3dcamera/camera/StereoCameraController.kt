package com.example.duallens3dcamera.camera

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.media.ExifInterface as PlatformExif
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import com.example.duallens3dcamera.util.StereoSbs
import com.example.duallens3dcamera.encoding.AudioEncoder
import com.example.duallens3dcamera.encoding.Mp4Muxer
import com.example.duallens3dcamera.encoding.VideoEncoder
import com.example.duallens3dcamera.media.MediaStoreUtils
import com.example.duallens3dcamera.util.SizeSelector
import com.example.duallens3dcamera.logging.StereoRecordingLogger
import com.example.duallens3dcamera.logging.StereoPhotoLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult



class StereoCameraController(
    private val context: Context,
    private val callback: Callback
) {
    interface Callback {
        fun onStatus(msg: String)
        fun onError(msg: String)
        fun onFallbackSizeUsed(msg: String)
        fun onRecordingStarted()
        fun onRecordingStopped()
        fun onPhotoCaptureDone()
    }

    data class PreviewConfig(
        val previewSize: Size,
        val sensorOrientation: Int,
        val recordSize: Size
    )

    companion object {
        private const val TAG = "StereoCameraController"

        // Pixel-based guidance (hardcoded)
        private const val LOGICAL_REAR_ID = "0"
        private const val WIDE_1X_PHYSICAL_ID = "2"
        // Google exposes the 2x crop of the main wide lens as another physical camera.
        // Non‑Pro Pixels: usually "4". Pro Pixels: usually "5".
        private const val WIDE_2X_PHYSICAL_ID = "4"
        private const val WIDE_2X_PHYSICAL_ID_PRO = "5"
        private const val ULTRA_PHYSICAL_ID = "3"


        private val PREFERRED_RECORD_SIZE = Size(1440, 1080)
        private const val TARGET_FPS = 30

        private const val IFRAME_INTERVAL_SEC = 1

        // Audio knobs
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_CHANNELS = 1
        private const val AUDIO_BITRATE_BPS = 128_000

        // maxes for spamming the shutter button
        private const val JPEG_MAX_IMAGES = 8
        private const val RAW_MAX_IMAGES = 3

        // Restrict ultrawide AE/AF/AWB decisions to the central 70% of its FoV (trim 30% total).
        // make it a default instead and overwrite based on lenses found
        private const val ULTRA_3A_FRACTION_DEFAULT = 0.70f
        // Computed per device from logical CONTROL_ZOOM_RATIO_RANGE if available; else default.
        private var ultra3aFraction: Float = ULTRA_3A_FRACTION_DEFAULT

        // Debugging defaults (actual toggles live in Settings)
        private const val DEFAULT_ENABLE_STEREO_RECORDING_LOG = false // disables all video logs
        private const val DEFAULT_STEREO_LOG_FRAMES_ONLY = false // frameNumber and SENSOR_TIMESTAMP only for video logs
        private const val DEFAULT_ENABLE_PHOTO_JSON_LOG = false
        private const val DEFAULT_ENABLE_PHOTO_SYNC_TOAST = false

    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    // threads so photos can output during shutter spamming
    // two threads instead for jpg/dng output
    private val ioExecutor = Executors.newFixedThreadPool(2)
    private val encodingExecutor = Executors.newSingleThreadExecutor()
    // Separate thread for SBS alignment/encoding so it never blocks photo IO.
    private val sbsExecutor = Executors.newSingleThreadExecutor()

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    private var surfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null

    private var logicalChars: CameraCharacteristics? = null
    private var wideChars: CameraCharacteristics? = null
    private var ultraChars: CameraCharacteristics? = null

    // Resolved per-device lens rig (Pixel/Samsung etc) from LensDiscovery.
    private var logicalRearId: String = LOGICAL_REAR_ID
    private var wide1xPhysicalId: String = WIDE_1X_PHYSICAL_ID
    private var wide2xPhysicalId: String? = null
    private var ultraPhysicalId: String = ULTRA_PHYSICAL_ID

    // Is the phone a Pixel or not (assumed Samsung if not, for now)
    private var phoneIsAPixel: Boolean = true

    // Active wide physical ID for the current zoom mode.
    private var widePhysicalId: String = WIDE_1X_PHYSICAL_ID


    @Volatile private var zoom2xEnabled: Boolean = false
    private var hasWide2xPhysical: Boolean = false


    private var wideMap: StreamConfigurationMap? = null
    private var ultraMap: StreamConfigurationMap? = null

    private var sensorOrientation: Int = 90
    private var displayRotation: Int = Surface.ROTATION_0

    private var recordSize: Size = PREFERRED_RECORD_SIZE
    //    private var previewSize: Size = Size(1280, 960)
    // Default fallback (overridden by settings)
    private var previewSize: Size = Size(800, 600)

    private var jpegWideSize: Size = PREFERRED_RECORD_SIZE
    private var jpegUltraSize: Size = PREFERRED_RECORD_SIZE

    private var rawWideSize: Size? = null
    private var rawUltraSize: Size? = null

    private var isRawMode: Boolean = false
    private var torchOn: Boolean = false
    private var isRecording: Boolean = false

    // mutable video config (set from UI, persisted in Activity)
    private var requestedRecordSize: Size = Size(1440, 1080)  // default corresponds to upright 1080x1440
    private var requestedRecordFps: Int = TARGET_FPS
    private var videoBitrateBps: Int = 50_000_000             // default 50 Mbps
    private var eisEnabled: Boolean = false                   // default OFF

    // preview config (settings)
    private var requestedPreviewSize: Size = Size(800, 600)
    private var previewTargetFps: Int = TARGET_FPS

    // photo tuning (settings)
    private var photoNoiseReductionMode: Int = CaptureRequest.NOISE_REDUCTION_MODE_OFF
    private var photoDistortionCorrectionMode: Int =
        CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
    private var photoEdgeMode: Int = CaptureRequest.EDGE_MODE_OFF

    // video processing (settings) -- applied to RECORDING only; preview is forced OFF.
    private var videoNoiseReductionMode: Int = CaptureRequest.NOISE_REDUCTION_MODE_OFF
    private var videoDistortionCorrectionMode: Int = CaptureRequest.DISTORTION_CORRECTION_MODE_FAST
    private var videoEdgeMode: Int = CaptureRequest.EDGE_MODE_OFF

    // Debugging (Advanced)
    private var enableStereoRecordingLog: Boolean = DEFAULT_ENABLE_STEREO_RECORDING_LOG
    private var stereoLogFramesOnly: Boolean = DEFAULT_STEREO_LOG_FRAMES_ONLY
    private var enablePhotoJsonLog: Boolean = DEFAULT_ENABLE_PHOTO_JSON_LOG
    private var enablePhotoSyncToast: Boolean = DEFAULT_ENABLE_PHOTO_SYNC_TOAST


    // Preview repeating builder for current mode.
    private var repeatingBuilder: CaptureRequest.Builder? = null

    // Still outputs for current photo mode (JPEG or RAW)
    private var wideReader: ImageReader? = null
    private var ultraReader: ImageReader? = null

    // Recording pipeline
    private var wideMuxer: Mp4Muxer? = null
    private var ultraMuxer: Mp4Muxer? = null
    private var wideEncoder: VideoEncoder? = null
    private var ultraEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null

    private var wideVideoUri: Uri? = null
    private var ultraVideoUri: Uri? = null

    // Photo capture coordination
    private data class PhotoCapture(
        val isRaw: Boolean,
        val captureId: String,
        val wallTimeMs: Long,
        val wideUri: Uri,
        val ultraUri: Uri,
        val sbsUri: Uri? = null,
        val logUri: Uri? = null,
        val exifDateTimeOriginal: String,
        @Volatile var wideImage: Image? = null,
        @Volatile var ultraImage: Image? = null,
        @Volatile var totalResult: TotalCaptureResult? = null,
        @Volatile var finished: Boolean = false
    )

    @Volatile private var currentPhoto: PhotoCapture? = null

    // for logging:
    private var recordingLogger: StereoRecordingLogger? = null
    private var recordingLogUri: Uri? = null
    // Keep the recording capture callback so updateTorchOnRepeating() can reuse it while recording.
    private var recordCaptureCallback: CameraCaptureSession.CaptureCallback? = null
    // end logging vars

    fun start(
        surfaceTexture: SurfaceTexture,
        displayRotation: Int,
        initialRawMode: Boolean,
        initialTorchOn: Boolean,
        initialRequestedRecordSize: Size,
        initialRequestedRecordFps: Int,
        initialVideoBitrateBps: Int,
        initialEisEnabled: Boolean,
        initialZoom2x: Boolean,
        initialPreviewSize: Size,
        initialPreviewTargetFps: Int,
        initialPhotoNoiseReductionMode: Int,
        initialPhotoDistortionCorrectionMode: Int,
        initialPhotoEdgeMode: Int
    ): PreviewConfig {
        this.surfaceTexture = surfaceTexture
        this.displayRotation = displayRotation
        this.isRawMode = initialRawMode
        this.torchOn = initialTorchOn

        // video config
        this.requestedRecordSize = initialRequestedRecordSize
        this.requestedRecordFps = initialRequestedRecordFps
        this.videoBitrateBps = initialVideoBitrateBps
        this.eisEnabled = initialEisEnabled

        // preview config
        this.requestedPreviewSize = initialPreviewSize
        this.previewTargetFps = initialPreviewTargetFps

        // photo tuning
        this.photoNoiseReductionMode = initialPhotoNoiseReductionMode
        this.photoDistortionCorrectionMode = initialPhotoDistortionCorrectionMode
        this.photoEdgeMode = initialPhotoEdgeMode

        this.zoom2xEnabled = initialZoom2x
        // We'll pick the correct wide physical ID in selectCharacteristicsAndSizes() after LensDiscovery runs.
        this.widePhysicalId = WIDE_1X_PHYSICAL_ID

        startThreads()
        selectCharacteristicsAndSizes()

        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface = Surface(surfaceTexture)

        openCamera()

        return PreviewConfig(previewSize = previewSize, sensorOrientation = sensorOrientation, recordSize = recordSize)
    }

    fun setVideoConfig(
        requestedRecordSize: Size,
        requestedRecordFps: Int,
        videoBitrateBps: Int,
        eisEnabled: Boolean
    ) {
        cameraHandler?.post {
            this.requestedRecordSize = requestedRecordSize
            this.requestedRecordFps = requestedRecordFps
            this.videoBitrateBps = videoBitrateBps
            this.eisEnabled = eisEnabled
//            callback.onStatus("Video config set: ${requestedRecordSize.width}x${requestedRecordSize.height} @${requestedRecordFps}fps, ${videoBitrateBps/1_000_000}Mbps, EIS=${eisEnabled}")
            // TODO: instead of showing the user, just log it
        }
    }

    fun setVideoProcessingModes(
        noiseReductionMode: Int,
        distortionCorrectionMode: Int,
        edgeMode: Int
    ) {
        this.videoNoiseReductionMode = noiseReductionMode
        this.videoDistortionCorrectionMode = distortionCorrectionMode
        this.videoEdgeMode = edgeMode
    }

    fun setDebugConfig(
        enableStereoRecordingLog: Boolean,
        stereoLogFramesOnly: Boolean,
        enablePhotoJsonLog: Boolean,
        enablePhotoSyncToast: Boolean
    ) {
        this.enableStereoRecordingLog = enableStereoRecordingLog
        this.stereoLogFramesOnly = stereoLogFramesOnly
        this.enablePhotoJsonLog = enablePhotoJsonLog
        this.enablePhotoSyncToast = enablePhotoSyncToast
    }

    private fun recomputeRecordSizeLocked() {
        val wMap = wideMap ?: return
        val uMap = ultraMap ?: return

        val (chosenRecord, fallbackMsg) = SizeSelector.chooseCommonPrivateSizeAtFps(
            wideMap = wMap,
            ultraMap = uMap,
            preferred = requestedRecordSize,
            maxW = requestedRecordSize.width,
            maxH = requestedRecordSize.height,
            targetFps = requestedRecordFps
        )
        recordSize = chosenRecord
        fallbackMsg?.let { callback.onFallbackSizeUsed(it) }

        Log.i(
            TAG,
            "VideoConfig requested=${requestedRecordSize.width}x${requestedRecordSize.height}@${requestedRecordFps} chosen=${recordSize.width}x${recordSize.height} bitrate=$videoBitrateBps eis=$eisEnabled"
        )
    }

    private fun recomputePreviewSizeLocked() {
        val wMap = wideMap ?: return

        val sizes = (wMap.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()).toList()
        if (sizes.isEmpty()) {
            previewSize = requestedPreviewSize
            return
        }

        val exact = sizes.firstOrNull {
            it.width == requestedPreviewSize.width && it.height == requestedPreviewSize.height
        }
        if (exact != null) {
            previewSize = exact
            return
        }

        val preferred = requestedPreviewSize
        val preferredIs43 = SizeSelector.isFourByThree(preferred)

        val candidates = if (preferredIs43) {
            val fourByThree = sizes.filter { SizeSelector.isFourByThree(it) }
            if (fourByThree.isNotEmpty()) fourByThree else sizes
        } else {
            sizes
        }

        val prefArea = preferred.width.toLong() * preferred.height.toLong()
        val chosen = candidates.minByOrNull { s ->
            val area = s.width.toLong() * s.height.toLong()
            kotlin.math.abs(area - prefArea)
        } ?: candidates.first()

        previewSize = chosen
        callback.onFallbackSizeUsed(
            "Preview size ${preferred.width}x${preferred.height} not available; using ${chosen.width}x${chosen.height}."
        )
    }

    fun stop() {
        val handler = cameraHandler ?: return

        val latch = CountDownLatch(1)
        handler.post {
            try {
                safeStopRecordingInternal(deleteOnFailure = false)
            } catch (_: Exception) {
            }

            try { session?.close() } catch (_: Exception) {}
            session = null

            try { cameraDevice?.close() } catch (_: Exception) {}
            cameraDevice = null

            closeReaders()

            try { previewSurface?.release() } catch (_: Exception) {}
            previewSurface = null
            surfaceTexture = null

            latch.countDown()
        }
        latch.await()

        stopThreads()
    }

    fun setTorch(enabled: Boolean) {
        torchOn = enabled
        val handler = cameraHandler ?: return
        handler.post { updateTorchOnRepeating() }
    }

    fun setPhotoOutputRaw(raw: Boolean) {
        val handler = cameraHandler ?: return
        handler.post {
            // don't change photo settings while recording video
            if (isRecording) return@post
            // RAW currently fails on Samsung so
            // don't allow it on them for now (force jpg instead)
            if (!phoneIsAPixel && raw) {
                if (isRawMode) isRawMode = false
                callback.onStatus("RAW/DNG is Pixel-only for now (Samsung uses JPG only).")
                return@post
            }
            // otherwise, toggle RAW mode
            isRawMode = raw
            if (cameraDevice == null || previewSurface == null) return@post
            createPreviewSession()
        }
    }

    /**
     * Toggle between:
     *  - 1x: wide physical ID "2" + ultrawide physical ID "3"
     *  - 2x: wide physical ID "4" (remosaic crop of wide) + ultrawide ID "3" with a 50% centered crop.
     */
    fun setZoom2x(enabled: Boolean) {
        val handler = cameraHandler ?: run {
            callback.onError("Camera thread not running.")
            return
        }

        handler.post {
            if (isRecording) {
                callback.onStatus("Can't change zoom while recording.")
                return@post
            }

            if (zoom2xEnabled == enabled) return@post

            val logical = logicalChars
            if (logical == null) {
                callback.onError("Camera not ready.")
                return@post
            }

            // Validate 2x availability on this device.
            val physicalIds = logical.physicalCameraIds
            // Prefer the discovered wide2x ID (works for Samsung too); fall back to Pixel ID heuristic.
            val wide2xId = wide2xPhysicalId ?: resolveWide2xPhysicalId(physicalIds)

            if (enabled && wide2xId == null) {
                zoom2xEnabled = false
                widePhysicalId = wide1xPhysicalId
                callback.onStatus("2x mode not available (no wide 2x physical camera under logical $logicalRearId).")
                return@post
            }

            zoom2xEnabled = enabled
            widePhysicalId = if (enabled) wide2xId!! else wide1xPhysicalId

            // prevents the 1x affine from being reused right after a zoom-mode flip
            StereoSbs.resetLastGoodAffine()

            // Refresh characteristics/maps for the new wide physical camera.
            try {
                wideChars = cameraManager.getCameraCharacteristics(widePhysicalId)
                // Ultrawide stays fixed, but keep this non-null.
                if (ultraChars == null) {
                    ultraChars = cameraManager.getCameraCharacteristics(ultraPhysicalId)
                }
            } catch (e: Exception) {
                callback.onError("Failed switching zoom: ${e.message}")
                zoom2xEnabled = false
                widePhysicalId = wide1xPhysicalId
                wideChars = cameraManager.getCameraCharacteristics(widePhysicalId)
            }

            val wide = wideChars
            val ultra = ultraChars
            if (wide == null || ultra == null) {
                callback.onError("Camera characteristics missing after zoom switch.")
                return@post
            }

            sensorOrientation = wide.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: sensorOrientation

            wideMap = wide.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ultraMap = ultra.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            val wMap = wideMap
            val uMap = ultraMap

            if (wMap != null && uMap != null) {
                recomputeRecordSizeLocked()
                recomputePreviewSizeLocked()
                jpegWideSize = SizeSelector.chooseLargestJpegFourByThree(wMap)
                jpegUltraSize = SizeSelector.chooseLargestJpegFourByThree(uMap)
                rawWideSize = SizeSelector.chooseLargestRaw(wMap)
                rawUltraSize = SizeSelector.chooseLargestRaw(uMap)
            }

            if (cameraDevice != null && previewSurface != null) {
                createPreviewSession()
            }
        }
    }

    fun captureStereoPhoto(timestampForFilename: String, raw: Boolean) {
        val handler = cameraHandler ?: run {
            callback.onError("Camera thread not running.")
            callback.onPhotoCaptureDone()
            return
        }
        handler.post {
            if (isRecording) {
                callback.onError("Cannot take photos while recording.")
                callback.onPhotoCaptureDone()
                return@post
            }
            if (cameraDevice == null || session == null) {
                callback.onError("Camera not ready.")
                callback.onPhotoCaptureDone()
                return@post
            }

            // don't allow RAW on Samsung
            val effectiveRaw = raw && phoneIsAPixel
            if (raw && !phoneIsAPixel) {
                callback.onStatus("RAW/DNG is Pixel-only for now (Samsung uses JPG).")
            }
            if (effectiveRaw != isRawMode) {
                isRawMode = effectiveRaw
                createPreviewSession()
            }

            val nowMs = System.currentTimeMillis()
            val exifDt = exifDateTimeOriginal(nowMs)

            try {
                val wideName = if (effectiveRaw) "${timestampForFilename}_wide.dng" else "${timestampForFilename}_wide.jpg"
                val ultraName = if (effectiveRaw) "${timestampForFilename}_ultrawide.dng" else "${timestampForFilename}_ultrawide.jpg"
                val wideMime = if (effectiveRaw) "image/x-adobe-dng" else "image/jpeg"
                val ultraMime = if (effectiveRaw) "image/x-adobe-dng" else "image/jpeg"

                val wideUri = MediaStoreUtils.createPendingImage(context, wideName, wideMime, nowMs)
                val ultraUri = MediaStoreUtils.createPendingImage(context, ultraName, ultraMime, nowMs)

                val sbsUri = if (!effectiveRaw) {
                    val sbsName = "${timestampForFilename}_sbs.jpg"
                    MediaStoreUtils.createPendingImage(context, sbsName, "image/jpeg", nowMs)
                } else {
                    null
                }

                val logUri = if (enablePhotoJsonLog) {
                    val logName = "${timestampForFilename}_photo.json"
                    MediaStoreUtils.createPendingJson(context, logName, nowMs)
                } else {
                    null
                }

                currentPhoto = PhotoCapture(
                    isRaw = effectiveRaw,
                    captureId = timestampForFilename,
                    wallTimeMs = nowMs,
                    wideUri = wideUri,
                    ultraUri = ultraUri,
                    sbsUri = sbsUri,
                    logUri = logUri,
                    exifDateTimeOriginal = exifDt
                )

                doStillCapture(effectiveRaw)
            } catch (e: Exception) {
                callback.onError("Photo capture setup failed: ${e.message}")
                callback.onPhotoCaptureDone()
            }

        }
    }

    fun startRecording(timestampForFilename: String) {
        val handler = cameraHandler ?: run {
            callback.onError("Camera thread not running.")
            return
        }

        handler.post {
            if (isRecording) {
                callback.onError("Already recording.")
                return@post
            }
            if (cameraDevice == null || previewSurface == null) {
                callback.onError("Camera not ready.")
                return@post
            }

            isRecording = true
            closeReaders() // reduce load during recording

            val nowMs = System.currentTimeMillis()
            val wideName = "${timestampForFilename}_wide.mp4"
            val ultraName = "${timestampForFilename}_ultrawide.mp4"

            try {
                wideVideoUri = MediaStoreUtils.createPendingVideo(context, wideName, nowMs)
                ultraVideoUri = MediaStoreUtils.createPendingVideo(context, ultraName, nowMs)

                val widePfd =
                    context.contentResolver.openFileDescriptor(requireNotNull(wideVideoUri), "rw")
                        ?: throw IllegalStateException("Failed to open wide video fd")
                val ultraPfd =
                    context.contentResolver.openFileDescriptor(requireNotNull(ultraVideoUri), "rw")
                        ?: throw IllegalStateException("Failed to open ultra video fd")

                val orient = computeOrientationHint()

                // Create muxers (one per output file)
                wideMuxer = Mp4Muxer("WideMuxer", widePfd, orient)
                ultraMuxer = Mp4Muxer("UltraMuxer", ultraPfd, orient)

                // Start audio codec first (adds audio track via INFO_OUTPUT_FORMAT_CHANGED).
                audioEncoder = AudioEncoder(
                    tag = "AudioEncoder",
                    muxers = listOf(requireNotNull(wideMuxer), requireNotNull(ultraMuxer)),
                    sampleRate = AUDIO_SAMPLE_RATE,
                    channelCount = AUDIO_CHANNELS,
                    bitrateBps = AUDIO_BITRATE_BPS
                ).also { it.start() }

                // Start two independent video encoders (two independent H.264 bitstreams)
                wideEncoder = VideoEncoder(
                    tag = "WideVideoEncoder",
                    width = recordSize.width,
                    height = recordSize.height,
                    fps = requestedRecordFps,
                    bitrateBps = videoBitrateBps,
                    iFrameIntervalSec = IFRAME_INTERVAL_SEC,
                    muxer = requireNotNull(wideMuxer)
                ).also { it.start() }

                ultraEncoder = VideoEncoder(
                    tag = "UltraVideoEncoder",
                    width = recordSize.width,
                    height = recordSize.height,
                    fps = requestedRecordFps,
                    bitrateBps = videoBitrateBps,
                    iFrameIntervalSec = IFRAME_INTERVAL_SEC,
                    muxer = requireNotNull(ultraMuxer)
                ).also { it.start() }

                if (enableStereoRecordingLog) {
                    // for logging, use exact same timestamp used in filenames
                    // (match wide/ultra/video/log easily)
                    val logName = "${timestampForFilename}_record.json"
                    recordingLogUri = MediaStoreUtils.createPendingJson(
                        context,
                        logName,
                        nowMs
                    )
                }

                // Create logger only if logging enabled
                if (enableStereoRecordingLog) {
                    val wideVideoUriLocal = requireNotNull(wideVideoUri)
                    val ultraVideoUriLocal = requireNotNull(ultraVideoUri)

                    val sensorTimestampSource =
                        logicalChars?.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
                    val sensorSyncType =
                        logicalChars?.get(CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE)

                    recordingLogger = StereoRecordingLogger(
                        recordingId = timestampForFilename,
                        logicalCameraId = logicalRearId,
                        widePhysicalId = widePhysicalId,
                        ultraPhysicalId = ultraPhysicalId,
                        recordSize = recordSize,
                        targetFps = requestedRecordFps,
                        videoBitrateBps = videoBitrateBps,
                        eisEnabled = eisEnabled,
                        orientationHintDegrees = orient,
                        sensorTimestampSource = sensorTimestampSource,
                        sensorSyncType = sensorSyncType,
                        wideVideoUri = wideVideoUriLocal,
                        ultraVideoUri = ultraVideoUriLocal,
                        framesOnly = stereoLogFramesOnly
                    )
                }

                // Connect encoders to logger
                if (enableStereoRecordingLog) {
                    val loggerLocal = recordingLogger
                    val wEncLocal = wideEncoder
                    val uEncLocal = ultraEncoder
                    val aEncLocal = audioEncoder

                    if (loggerLocal != null && wEncLocal != null && uEncLocal != null && aEncLocal != null) {
                        // Only run encoder callback hookups in full logging mode.
                        // Frames-only mode ignores muxer/sample events entirely.
                        if (!stereoLogFramesOnly) {
                            wEncLocal.onEncodedSample = { codecPtsUs, muxerPtsUs, sizeBytes, flags, writeDurationNs ->
                                loggerLocal.onWideVideoSample(codecPtsUs, muxerPtsUs, sizeBytes, flags, writeDurationNs)
                            }
                            uEncLocal.onEncodedSample = { codecPtsUs, muxerPtsUs, sizeBytes, flags, writeDurationNs ->
                                loggerLocal.onUltraVideoSample(codecPtsUs, muxerPtsUs, sizeBytes, flags, writeDurationNs)
                            }
                            aEncLocal.onEncodedSample = { codecPtsUs, muxerPtsUs, sizeBytes, flags, writeDurationNs ->
                                loggerLocal.onAudioSample(codecPtsUs, muxerPtsUs, sizeBytes, flags, writeDurationNs)
                            }
                        } else {
                            // frames-only: no-op assignments just to be explicit
                            wEncLocal.onEncodedSample = null
                            uEncLocal.onEncodedSample = null
                            aEncLocal.onEncodedSample = null
                        }
                    }
                } else {
                    // no logging; clear callbacks
                    wideEncoder?.onEncodedSample = null
                    ultraEncoder?.onEncodedSample = null
                    audioEncoder?.onEncodedSample = null
                }

                createRecordingSession()
            } catch (e: Exception) {
                callback.onError("Start recording failed: ${e.message}")
                safeStopRecordingInternal(deleteOnFailure = true)
                isRecording = false
            }
        }
    }

    fun stopRecording() {

        val handler = cameraHandler ?: run {
            callback.onError("Camera thread not running.")
            return
        }

        handler.post {
            if (!isRecording) {
                callback.onRecordingStopped()
                return@post
            }

            // Stop camera outputs first.
            try { session?.stopRepeating() } catch (_: Exception) {}
            try { session?.abortCaptures() } catch (_: Exception) {}
            try { session?.close() } catch (_: Exception) {}

            session = null
            repeatingBuilder = null
            recordCaptureCallback = null

            val resolver = context.contentResolver

            // Move refs locally for the async stop/release path.
            val wideUriLocal = wideVideoUri
            val ultraUriLocal = ultraVideoUri

            val wEnc = wideEncoder
            val uEnc = ultraEncoder
            val aEnc = audioEncoder

            val wMux = wideMuxer
            val uMux = ultraMuxer

            wideVideoUri = null
            ultraVideoUri = null
            wideEncoder = null
            ultraEncoder = null
            audioEncoder = null
            wideMuxer = null
            ultraMuxer = null

            isRecording = false

            // stop logging and write/finalize
            recordingLogger?.markStopped()

            val logUriLocal = recordingLogUri
            val loggerLocal = recordingLogger

            encodingExecutor.execute {
                try { wEnc?.signalEndOfInputStream() } catch (_: Exception) {}
                try { uEnc?.signalEndOfInputStream() } catch (_: Exception) {}

                // Stop audio (queues EOS + drains)
                try { aEnc?.stopAndRelease() } catch (_: Exception) {}

                // Stop video encoders (drain + release)
                try { wEnc?.stopAndRelease() } catch (_: Exception) {}
                try { uEnc?.stopAndRelease() } catch (_: Exception) {}

                // Stop muxers last
                try { wMux?.stopAndRelease() } catch (_: Exception) {}
                try { uMux?.stopAndRelease() } catch (_: Exception) {}

                // Make files visible in MediaStore
                if (wideUriLocal != null) MediaStoreUtils.finalizePending(resolver, wideUriLocal)
                if (ultraUriLocal != null) MediaStoreUtils.finalizePending(resolver, ultraUriLocal)

                // Write + finalize the stereo JSON log
                if (loggerLocal != null && logUriLocal != null) {
                    try {
                        loggerLocal.writeToUri(context, logUriLocal)
                        MediaStoreUtils.finalizePending(resolver, logUriLocal)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed writing/finalizing JSON log: ${e.message}", e)
                        MediaStoreUtils.delete(resolver, logUriLocal)
                    }
                }
                // Clear logger refs after we are fully done
                recordingLogger = null
                recordingLogUri = null

                cameraHandler?.post {
                    if (cameraDevice != null && previewSurface != null) {
                        createPreviewSession()
                    }
                    callback.onRecordingStopped()
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Camera open / session management
    // ---------------------------------------------------------------------------------------------

    private fun startThreads() {
        if (cameraThread != null) return
        cameraThread = HandlerThread("CameraThread").also { it.start() }
        cameraHandler = Handler(requireNotNull(cameraThread).looper)
    }

    private fun stopThreads() {
        cameraThread?.quitSafely()
        try { cameraThread?.join() } catch (_: Exception) {}
        cameraThread = null
        cameraHandler = null
    }

    private fun selectCharacteristicsAndSizes() {

        // Discover the rear logical camera + physical IDs (Pixel + Samsung).
        val rig = LensDiscovery.discoverStereoRig(cameraManager)

        if (rig != null) {
            logicalRearId = rig.logicalRearId
            wide1xPhysicalId = rig.wide1xId
            ultraPhysicalId = rig.ultraId
            wide2xPhysicalId = rig.wide2xId
            phoneIsAPixel = rig.phoneIsAPixel

            // Override rotation behavior using the simple heuristic:
            // Pixel => portrait (ROTATION_0), Samsung => landscape (ROTATION_90)
            displayRotation = rig.assumedDisplayRotation
        } else {
            // Fallback to the prior Pixel hardcodes if discovery fails.
            logicalRearId = LOGICAL_REAR_ID
            wide1xPhysicalId = WIDE_1X_PHYSICAL_ID
            ultraPhysicalId = ULTRA_PHYSICAL_ID
            wide2xPhysicalId = null
            phoneIsAPixel = true
            displayRotation = Surface.ROTATION_0
        }

        logicalChars = cameraManager.getCameraCharacteristics(logicalRearId)

        val logical = requireNotNull(logicalChars)

        // Try to derive "wide inside ultrawide" scale from logical zoom-out capability.
        // On multi-camera devices, logical zoom range often includes < 1.0 for ultrawide.
        val zoomRange = logical.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        ultra3aFraction = if (zoomRange != null && zoomRange.lower < 1.0f) {
            // Slightly conservative so the region stays inside the true overlap.
            // Clamp to avoid weird vendor ranges.
            // (zoomRange.lower * 0.95f).coerceIn(0.45f, 0.90f)
            // less conservative
            zoomRange.lower.coerceIn(0.45f, 0.90f)
        } else {
            ULTRA_3A_FRACTION_DEFAULT
        }
        Log.i(TAG, "Ultrawide 3A fraction = $ultra3aFraction (logical zoomRange=$zoomRange)")

        val caps = logical.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
        if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
            callback.onError("Rear camera is not LOGICAL_MULTI_CAMERA. This app requires a logical multi-camera (wide+ultrawide).")
            return
        }

        val physicalIds = logical.physicalCameraIds
        if (!physicalIds.contains(wide1xPhysicalId) || !physicalIds.contains(ultraPhysicalId)) {
            callback.onError(
                "Expected physical IDs $wide1xPhysicalId (wide) and $ultraPhysicalId (ultrawide) not found under logical $logicalRearId. Found: $physicalIds"
            )
            return
        }

        // Decide which physical ID (if any) represents wide 2x crop.
        val wide2xId = wide2xPhysicalId ?: resolveWide2xPhysicalId(physicalIds)
        wide2xPhysicalId = wide2xId // cache it for setZoom2x()
        hasWide2xPhysical = wide2xId != null

        if (zoom2xEnabled && wide2xId == null) {
            zoom2xEnabled = false
            callback.onStatus("2x mode not available on this device; falling back to 1x.")
        }

        // Pick active wide physical camera for current zoom mode.
        widePhysicalId = if (zoom2xEnabled && wide2xId != null) wide2xId else wide1xPhysicalId

        wideChars = cameraManager.getCameraCharacteristics(widePhysicalId)
        ultraChars = cameraManager.getCameraCharacteristics(ultraPhysicalId)

        val wide = requireNotNull(wideChars)
        val ultra = requireNotNull(ultraChars)

        sensorOrientation = wide.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        wideMap = wide.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ultraMap = ultra.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val wMap = requireNotNull(wideMap)
        val uMap = requireNotNull(ultraMap)

        recomputeRecordSizeLocked()
        recomputePreviewSizeLocked()

        jpegWideSize = SizeSelector.chooseLargestJpegFourByThree(wMap)
        jpegUltraSize = SizeSelector.chooseLargestJpegFourByThree(uMap)

        Log.i(TAG, "JPEG wide=${jpegWideSize.width}x${jpegWideSize.height}, ultra=${jpegUltraSize.width}x${jpegUltraSize.height}")

        rawWideSize = SizeSelector.chooseLargestRaw(wMap)
        rawUltraSize = SizeSelector.chooseLargestRaw(uMap)

    }

    private fun openCamera() {
        val handler = requireNotNull(cameraHandler)
        try {
            cameraManager.openCamera(logicalRearId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createPreviewSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    try { camera.close() } catch (_: Exception) {}
                    cameraDevice = null
                    callback.onError("Camera disconnected.")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    try { camera.close() } catch (_: Exception) {}
                    cameraDevice = null
                    callback.onError("Camera error: $error")
                }
            }, handler)
        } catch (se: SecurityException) {
            callback.onError("Missing CAMERA permission.")
        } catch (e: Exception) {
            callback.onError("openCamera failed: ${e.message}")
        }
    }

    private fun createPreviewSession() {

        // make sure preview aspect doesn't get messed up
        surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)

        val camera = cameraDevice ?: return
        val handler = requireNotNull(cameraHandler)
        val preview = previewSurface ?: return

        try { session?.close() } catch (_: Exception) {}
        session = null
        repeatingBuilder = null
        recordCaptureCallback = null

        closeReaders()

//        val raw = isRawMode

        // Samsung hack version
        // raw doesn't work with samsung yet, so disable
        val raw = if (!phoneIsAPixel && isRawMode) {
            // Samsung: RAW/DNG stereo is unstable on some devices (can hard-crash/reboot).
            isRawMode = false
            callback.onStatus("RAW/DNG disabled on Samsung for now (JPG only).")
            false
        } else {
            isRawMode
        }

        val (format, wideSize, ultraSize) = if (raw) {
            val w = rawWideSize
            val u = rawUltraSize
            if (w == null || u == null) {
                callback.onStatus("RAW not supported; using JPG.")
                isRawMode = false
                Triple(ImageFormat.JPEG, jpegWideSize, jpegUltraSize)
            } else {
                Triple(ImageFormat.RAW_SENSOR, w, u)
            }
        } else {
            Triple(ImageFormat.JPEG, jpegWideSize, jpegUltraSize)
        }

        val maxImages = if (format == ImageFormat.RAW_SENSOR) RAW_MAX_IMAGES else JPEG_MAX_IMAGES

        wideReader = ImageReader.newInstance(wideSize.width, wideSize.height, format, maxImages).apply {
            setOnImageAvailableListener({ reader -> onStillImageAvailable(isWide = true, reader = reader) }, handler)
        }
        ultraReader = ImageReader.newInstance(ultraSize.width, ultraSize.height, format, maxImages).apply {
            setOnImageAvailableListener({ reader -> onStillImageAvailable(isWide = false, reader = reader) }, handler)
        }

        val outputs = ArrayList<OutputConfiguration>(3).apply {
            add(OutputConfiguration(preview).apply { setPhysicalCameraId(widePhysicalId) })
            add(OutputConfiguration(requireNotNull(wideReader).surface).apply { setPhysicalCameraId(widePhysicalId) })
            add(OutputConfiguration(requireNotNull(ultraReader).surface).apply { setPhysicalCameraId(ultraPhysicalId) })
        }

        camera.createCaptureSessionByOutputConfigurations(
            outputs,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(sess: CameraCaptureSession) {
                    session = sess
                    try {
                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
//                        val builder = createRequest(camera, CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(preview)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

                            applyCommonNoCropNoStab(this, isVideo = false, targetFps = previewTargetFps)

                            // Preview post-processing forced OFF (performance + reduce ISP variability).
                            try { set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF) } catch (_: Exception) {}
                            try { set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF) } catch (_: Exception) {}
                            try { set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF) } catch (_: Exception) {}

                            // (Physical keys are optional; set wide as best-effort.)
                            setPhysical(this, widePhysicalId, CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                            setPhysical(this, widePhysicalId, CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
                            setPhysical(this, widePhysicalId, CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)

                            applyTorch(this)
                        }
                        repeatingBuilder = builder
                        sess.setRepeatingRequest(builder.build(), null, handler)
                    } catch (e: Exception) {
                        callback.onError("Preview repeating request failed: ${e.message}")
                        Log.e(TAG, "Preview repeating request failed: ${e.message}", e)
                    }
                }

                override fun onConfigureFailed(sess: CameraCaptureSession) {
                    callback.onError("Preview session configure failed.")
                }
            },
            handler
        )
    }

    private fun createRecordingSession() {

        // make sure preview aspect doesn't get messed up
        surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)

        val camera = cameraDevice ?: return
        val handler = requireNotNull(cameraHandler)
        val preview = previewSurface ?: return
        val wideEnc = wideEncoder ?: return
        val ultraEnc = ultraEncoder ?: return

        try { session?.close() } catch (_: Exception) {}
        session = null
        repeatingBuilder = null

        val outputs = ArrayList<OutputConfiguration>(3).apply {
            add(OutputConfiguration(preview).apply { setPhysicalCameraId(widePhysicalId) })
            add(OutputConfiguration(wideEnc.inputSurface).apply { setPhysicalCameraId(widePhysicalId) })
            add(OutputConfiguration(ultraEnc.inputSurface).apply { setPhysicalCameraId(ultraPhysicalId) })
        }

        camera.createCaptureSessionByOutputConfigurations(
            outputs,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(sess: CameraCaptureSession) {
                    session = sess
                    try {
                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(preview)
                            addTarget(wideEnc.inputSurface)
                            addTarget(ultraEnc.inputSurface)

//                            applyCommonNoCropNoStab(this, isVideo = true)
                            applyCommonNoCropNoStab(this, isVideo = true, targetFps = requestedRecordFps)
//                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(TARGET_FPS, TARGET_FPS))
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

                            // Video post-processing (settings; default: NR OFF, Edge OFF, Distortion FAST)
                            try { set(CaptureRequest.NOISE_REDUCTION_MODE, videoNoiseReductionMode) } catch (_: Exception) {}
                            try { set(CaptureRequest.DISTORTION_CORRECTION_MODE, videoDistortionCorrectionMode) } catch (_: Exception) {}
                            try { set(CaptureRequest.EDGE_MODE, videoEdgeMode) } catch (_: Exception) {}

                            setPhysical(this, widePhysicalId, CaptureRequest.NOISE_REDUCTION_MODE, videoNoiseReductionMode)
                            setPhysical(this, widePhysicalId, CaptureRequest.DISTORTION_CORRECTION_MODE, videoDistortionCorrectionMode)
                            setPhysical(this, widePhysicalId, CaptureRequest.EDGE_MODE, videoEdgeMode)

                            setPhysical(this, ultraPhysicalId, CaptureRequest.NOISE_REDUCTION_MODE, videoNoiseReductionMode)
                            setPhysical(this, ultraPhysicalId, CaptureRequest.DISTORTION_CORRECTION_MODE, videoDistortionCorrectionMode)
                            setPhysical(this, ultraPhysicalId, CaptureRequest.EDGE_MODE, videoEdgeMode)

                            applyTorch(this)
                        }

                        // NEW: for logging
                        repeatingBuilder = builder

                        val captureCallback: CameraCaptureSession.CaptureCallback? =
                            if (enableStereoRecordingLog) {

                                val wideEncSurface = wideEnc.inputSurface
                                val ultraEncSurface = ultraEnc.inputSurface
                                val previewSurfaceLocal = preview

                                object : CameraCaptureSession.CaptureCallback() {

                                    override fun onCaptureCompleted(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        result: TotalCaptureResult
                                    ) {
                                        val logger = recordingLogger ?: return
                                        val frameNo = result.frameNumber

                                        val phys = result.physicalCameraResults
                                        if (phys.isNotEmpty()) {
                                            phys[widePhysicalId]?.let {
                                                logger.onPhysicalCaptureResult(widePhysicalId, frameNo, it)
                                            }
                                            phys[ultraPhysicalId]?.let {
                                                logger.onPhysicalCaptureResult(ultraPhysicalId, frameNo, it)
                                            }
                                        } else {
                                            // Fallback: at least log the logical result under "wide"
                                            logger.onPhysicalCaptureResult(widePhysicalId, frameNo, result)
                                        }
                                    }

                                    override fun onCaptureBufferLost(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        target: Surface,
                                        frameNumber: Long
                                    ) {
                                        val logger = recordingLogger ?: return
                                        when {
                                            target === wideEncSurface ->
                                                logger.onBufferLost(widePhysicalId, "wideEncoder", frameNumber)

                                            target === ultraEncSurface ->
                                                logger.onBufferLost(ultraPhysicalId, "ultraEncoder", frameNumber)

                                            target === previewSurfaceLocal ->
                                                logger.onBufferLost(widePhysicalId, "preview", frameNumber)

                                            else ->
                                                logger.onBufferLost("unknown", "unknown", frameNumber)
                                        }
                                    }
                                }
                            } else {
                                null
                            }

                        // Store it so torch toggles can keep using it while recording.
                        recordCaptureCallback = captureCallback

                        sess.setRepeatingRequest(builder.build(), captureCallback, handler)
                        // end NEW for logging

                        // Start mic capture as close to the start of video frames as possible.
                        try {
                            audioEncoder?.startCapturing()
                        } catch (e: Exception) {
                            callback.onError("Audio start failed: ${e.message}")
                        }

                        callback.onRecordingStarted()
                    } catch (e: Exception) {
                        callback.onError("Record repeating request failed: ${e.message}")
                        safeStopRecordingInternal(deleteOnFailure = true)
                        isRecording = false
                    }
                }

                override fun onConfigureFailed(sess: CameraCaptureSession) {
                    callback.onError("Recording session configure failed.")
                    safeStopRecordingInternal(deleteOnFailure = true)
                    isRecording = false
                }
            },
            handler
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Still capture
    // ---------------------------------------------------------------------------------------------

    private fun doStillCapture(raw: Boolean) {
        val camera = cameraDevice ?: return
        val sess = session ?: return
        val handler = requireNotNull(cameraHandler)

        val wideR = wideReader ?: run {
            callback.onError("Wide ImageReader not ready.")
            callback.onPhotoCaptureDone()
            return
        }
        val ultraR = ultraReader ?: run {
            callback.onError("Ultra ImageReader not ready.")
            callback.onPhotoCaptureDone()
            return
        }

        val jpegOrientation = computeJpegOrientation()
        val samsungMode = !phoneIsAPixel
        val preview = previewSurface

        // For watchdog to avoid nuking a later capture
        val capAtStart = currentPhoto

        try {
            try { sess.stopRepeating() } catch (_: Exception) {}

            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {

                // Critical: Samsung workaround - add the preview as an extra target if present
                // for some unknown, reason, this makes photos work (removes crash) for samsung
                if (samsungMode && preview != null) {
                    // force a 3-target request.
                    addTarget(preview)
                }

                addTarget(wideR.surface)
                addTarget(ultraR.surface)

                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

//                set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
//
//                // attempt to lessen noise reduction
//                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                try { set(CaptureRequest.DISTORTION_CORRECTION_MODE, photoDistortionCorrectionMode) } catch (_: Exception) {}

                // attempt to lessen noise reduction
                try { set(CaptureRequest.NOISE_REDUCTION_MODE, photoNoiseReductionMode) } catch (_: Exception) {}
                // NOTE: NOISE_REDUCTION_MODE_OFF looks GREAT (sharper) in good light, and even
                //   adds a nice non-smeary texture in somewhat lower light.
                // NOTE: NOISE_REDUCTION_MODE_MINIMAL causes crash.
                //   - Don't allow it as an option in UI settings when implemented.
                // NOISE_REDUCTION_MODE_HIGH_QUALITY, NOISE_REDUCTION_MODE_FAST,
                // NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG, and
                // NOISE_REDUCTION_MODE_OFF all seem to work fine on Pixels.

                try { set(CaptureRequest.EDGE_MODE, photoEdgeMode) } catch (_: Exception) {}

                // START: relevant to RAW only
                // RAW quality: request lens shading map so DngCreator can embed it (if supported).
                if (raw) {
                    try {
                        set(
                            CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                            CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
                        )
                    } catch (_: Exception) {
                    }
                    setPhysical(
                        this,
                        widePhysicalId,
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
                    )
                    setPhysical(
                        this,
                        ultraPhysicalId,
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
                    )
                }
                // END: relevant to RAW only

                // Best-effort: ZSL/HDR off
                try { set(CaptureRequest.CONTROL_ENABLE_ZSL, false) } catch (_: Exception) {}
                set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

                //applyCommonNoCropNoStab(this, isVideo = false)
                applyCommonNoCropNoStab(this, isVideo = false, targetFps = previewTargetFps)
                applyTorch(this)

                if (!raw) {
                    set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                    set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                }
            }

            sess.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        val cap = currentPhoto
                        if (cap != null) {
                            cap.totalResult = result
                            tryFinalizePhotoIfReady()
                        }
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        callback.onError("Still capture failed: $failure")
                        failAndCleanupCurrentPhoto()
                    }

                    override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                        try {
                            val rb = repeatingBuilder
                            if (rb != null) {
                                session.setRepeatingRequest(rb.build(), null, handler)
                            }
                        } catch (_: Exception) {}
                    }
                },
                handler
            )

            // Watchdog: never leave UI stuck if Samsung fails to deliver one stream.
            if (samsungMode) {
                handler.postDelayed({
                    val capNow = currentPhoto
                    if (capNow != null && capNow === capAtStart && !capNow.finished) {
                        Log.e(
                            TAG,
                            "Samsung still timeout: wideReady=${capNow.wideImage != null}, ultraReady=${capNow.ultraImage != null}"
                        )
                        callback.onError("Still capture timed out on this device.")
                        failAndCleanupCurrentPhoto()

                        // Best-effort resume repeating
                        try {
                            val rb = repeatingBuilder
                            if (rb != null) sess.setRepeatingRequest(rb.build(), null, handler)
                        } catch (_: Exception) {}
                    }
                }, 2500L)
            }

        } catch (e: Exception) {
            callback.onError("Still capture exception: ${e.message}")
            failAndCleanupCurrentPhoto()
        }
    }


    private fun onStillImageAvailable(isWide: Boolean, reader: ImageReader) {
        val image = try { reader.acquireNextImage() } catch (_: Exception) { null } ?: return

        val cap = currentPhoto
        if (cap == null || cap.finished) {
            image.close()
            return
        }

        if (isWide) {
            cap.wideImage?.close()
            cap.wideImage = image
        } else {
            cap.ultraImage?.close()
            cap.ultraImage = image
        }

        tryFinalizePhotoIfReady()
    }

    private fun tryFinalizePhotoIfReady() {
        val cap = currentPhoto ?: return
        if (cap.finished) return

        val wideImg = cap.wideImage
        val ultraImg = cap.ultraImage
        val result = cap.totalResult

        // For RAW, we always need the TotalCaptureResult to build DNG.
        // For JPEG, we only need the TotalCaptureResult if we want exposure-time-based sync metrics
        // (photo JSON log / photo sync toast).
        val needResult = cap.isRaw || cap.logUri != null || enablePhotoSyncToast
        val ready = if (needResult) {
            (wideImg != null && ultraImg != null && result != null)
        } else {
            (wideImg != null && ultraImg != null)
        }

        if (!ready) return

        // Detach this capture so UI/camera can proceed immediately.
        cap.finished = true
        currentPhoto = null

        val wideUri = cap.wideUri
        val ultraUri = cap.ultraUri
        val sbsUri = cap.sbsUri
        val photoLogUri = cap.logUri
        val captureId = cap.captureId
        val wallTimeMs = cap.wallTimeMs
        val exifDt = cap.exifDateTimeOriginal
        val isRaw = cap.isRaw

        // Photo sync metrics (for optional toast + optional per-photo JSON log)
        val wideImageTimestampNs = wideImg?.timestamp
        val ultraImageTimestampNs = ultraImg?.timestamp

        val physicalTotalsForMetrics: Map<String, TotalCaptureResult> = try {
            result?.physicalCameraTotalResults ?: emptyMap()
        } catch (_: Throwable) {
            emptyMap()
        }

        val wideResultForMetrics: CaptureResult? = physicalTotalsForMetrics[widePhysicalId] ?: result
        val ultraResultForMetrics: CaptureResult? = physicalTotalsForMetrics[ultraPhysicalId] ?: result

        val wideSensorTimestampNs = wideResultForMetrics?.get(CaptureResult.SENSOR_TIMESTAMP)
            ?: (wideImageTimestampNs ?: 0L)
        val ultraSensorTimestampNs = ultraResultForMetrics?.get(CaptureResult.SENSOR_TIMESTAMP)
            ?: (ultraImageTimestampNs ?: 0L)

        val wideExposureTimeNs = wideResultForMetrics?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val ultraExposureTimeNs = ultraResultForMetrics?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L

        val syncMetrics = StereoPhotoLogger.computeMetrics(
            wideSensorTimestampNs = wideSensorTimestampNs,
            ultraSensorTimestampNs = ultraSensorTimestampNs,
            wideExposureTimeNs = wideExposureTimeNs,
            ultraExposureTimeNs = ultraExposureTimeNs
        )

        if (enablePhotoSyncToast) {
            callback.onStatus(StereoPhotoLogger.formatToast(syncMetrics))
        }

        // IMPORTANT: free UI immediately (saving continues in background)
        callback.onPhotoCaptureDone()

        val resolver = context.contentResolver

        if (!isRaw) {
            // --- JPEG path: copy bytes now, close Images NOW (frees buffers) ---
            val wideBytes = try {
                imageToBytes(requireNotNull(wideImg))
            } finally {
                try { wideImg?.close() } catch (_: Exception) {}
            }

            val ultraBytes = try {
                imageToBytes(requireNotNull(ultraImg))
            } finally {
                try { ultraImg?.close() } catch (_: Exception) {}
            }

            ioExecutor.execute {
                try {
                    saveJpegBytes(wideUri, wideBytes, exifDt)
                    saveJpegBytes(ultraUri, ultraBytes, exifDt)

                    MediaStoreUtils.finalizePending(resolver, wideUri)
                    MediaStoreUtils.finalizePending(resolver, ultraUri)

                    // Optional: per-photo JSON log (one per still capture)
                    if (photoLogUri != null) {
                        try {
                            StereoPhotoLogger.writeJson(
                                context = context,
                                uri = photoLogUri,
                                captureId = captureId,
                                wallTimeMs = wallTimeMs,
                                isRaw = false,
                                widePhysicalId = widePhysicalId,
                                ultraPhysicalId = ultraPhysicalId,
                                wideImageUri = wideUri,
                                ultraImageUri = ultraUri,
                                sbsUri = sbsUri,
                                wideImageTimestampNs = wideImageTimestampNs,
                                ultraImageTimestampNs = ultraImageTimestampNs,
                                metrics = syncMetrics
                            )
                            MediaStoreUtils.finalizePending(resolver, photoLogUri)
                        } catch (e: Exception) {
                            Log.e(TAG, "Photo JSON log write failed: ${e.message}", e)
                            MediaStoreUtils.delete(resolver, photoLogUri)
                        }
                    }

                    // --- SBS output (extra) ---
                    // Run alignment + SBS JPEG encode on its own thread so it never blocks photo IO.
                    if (sbsUri != null) {
                        sbsExecutor.execute {
                            try {
                                val zoom2xForThisSbs = zoom2xEnabled  // snapshot current mode for this capture

                                val sbsBytes = StereoSbs.alignAndCreateSbsJpegBytes(
                                    wideRightJpeg = wideBytes,
                                    ultraLeftJpeg = ultraBytes,
                                    zoom2xEnabled = zoom2xForThisSbs,
                                    fallbackUltraOverlapFraction = ultra3aFraction
                                )

                                if (sbsBytes == null) {
                                    MediaStoreUtils.delete(resolver, sbsUri)
                                    return@execute
                                }

                                // Writes SBS bytes, then writes DateTime EXIF (same helper used for the individual JPEGs)
                                saveJpegBytes(sbsUri, sbsBytes, exifDt, orientation = PlatformExif.ORIENTATION_NORMAL)

                                MediaStoreUtils.finalizePending(resolver, sbsUri)
                            } catch (e: Exception) {
                                Log.e(TAG, "SBS save failed: ${e.message}", e)
                                MediaStoreUtils.delete(resolver, sbsUri)

                                // Important: don't call onError here (it resets UI state and can conflict with later actions)
                                callback.onStatus("SBS save failed: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "JPEG save failed: ${e.message}", e)
                    MediaStoreUtils.delete(resolver, wideUri)
                    MediaStoreUtils.delete(resolver, ultraUri)
                    sbsUri?.let { MediaStoreUtils.delete(resolver, it) }
                    photoLogUri?.let { MediaStoreUtils.delete(resolver, it) }
                    callback.onError("JPEG save failed: ${e.message}")
                }
            }
        } else {
            // --- RAW path: cannot copy; keep Images open for background DNG write ---
            val wideImage = requireNotNull(wideImg)
            val ultraImage = requireNotNull(ultraImg)
            val total = requireNotNull(result)

            ioExecutor.execute {
                try {
                    val wideCharacteristics = requireNotNull(wideChars)
                    val ultraCharacteristics = requireNotNull(ultraChars)

                    // START: new DNG improvements
                    // Use per-physical TotalCaptureResult for correct color matrices, shading maps, etc.
                    // This ensures each DNG has good colors and reduces WB mismatches between L-R
                    val physicalTotals: Map<String, TotalCaptureResult> = try {
                        total.physicalCameraTotalResults
                    } catch (_: Throwable) {
                        emptyMap()
                    }
                    val wideTotal = physicalTotals[widePhysicalId] ?: total
                    val ultraTotal = physicalTotals[ultraPhysicalId] ?: total

                    // Write wide DNG, then close wide Image immediately.
                    try {
                        saveDng(wideUri, wideCharacteristics, wideTotal, wideImage)
                    } finally {
                        try { wideImage.close() } catch (_: Exception) {}
                    }

                    // Write ultra DNG, then close ultra Image immediately.
                    try {
                        saveDng(ultraUri, ultraCharacteristics, ultraTotal, ultraImage)
                    } finally {
                        try { ultraImage.close() } catch (_: Exception) {}
                    }
                    // END: new DNG improvements

                    MediaStoreUtils.finalizePending(resolver, wideUri)
                    MediaStoreUtils.finalizePending(resolver, ultraUri)

                    // Optional: per-photo JSON log (one per still capture)
                    if (photoLogUri != null) {
                        try {
                            StereoPhotoLogger.writeJson(
                                context = context,
                                uri = photoLogUri,
                                captureId = captureId,
                                wallTimeMs = wallTimeMs,
                                isRaw = true,
                                widePhysicalId = widePhysicalId,
                                ultraPhysicalId = ultraPhysicalId,
                                wideImageUri = wideUri,
                                ultraImageUri = ultraUri,
                                sbsUri = null,
                                wideImageTimestampNs = wideImageTimestampNs,
                                ultraImageTimestampNs = ultraImageTimestampNs,
                                metrics = syncMetrics
                            )
                            MediaStoreUtils.finalizePending(resolver, photoLogUri)
                        } catch (e: Exception) {
                            Log.e(TAG, "Photo JSON log write failed: ${e.message}", e)
                            MediaStoreUtils.delete(resolver, photoLogUri)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "DNG save failed: ${e.message}", e)
                    // Ensure images aren't leaked if an exception happens before the finally blocks above.
                    try { wideImage.close() } catch (_: Exception) {}
                    try { ultraImage.close() } catch (_: Exception) {}

                    MediaStoreUtils.delete(resolver, wideUri)
                    MediaStoreUtils.delete(resolver, ultraUri)
                    photoLogUri?.let { MediaStoreUtils.delete(resolver, it) }
                    callback.onError("DNG save failed: ${e.message}")
                }
            }
        }
    }

    private fun saveJpegBytes(
        uri: Uri,
        bytes: ByteArray,
        exifDateTimeOriginal: String,
        orientation: Int? = null
    ) {
        context.contentResolver.openOutputStream(uri)?.use { os ->
            os.write(bytes)
            os.flush()
        } ?: throw IllegalStateException("openOutputStream failed for $uri")

        // DateTimeOriginal (+ optionally orientation)
        setExifDateTimeOriginal(uri, exifDateTimeOriginal, orientation)
    }

    private fun saveDng(
        uri: Uri,
        characteristics: CameraCharacteristics,
        result: TotalCaptureResult,
        image: Image
    ) {
        val dngCreator = android.hardware.camera2.DngCreator(characteristics, result)

        // Keep orientation via DngCreator (no ExifInterface post-write: avoids full DNG rewrite).
        val orientationConst = exifOrientationFromDegrees(computeJpegOrientation())
        try {
            dngCreator.setOrientation(orientationConst)
        } catch (e: Exception) {
            // Extremely unlikely with a valid orientationConst; log and continue.
            Log.w(TAG, "DngCreator.setOrientation failed: ${e.message}")
        }

        context.contentResolver.openOutputStream(uri)?.use { os ->
            dngCreator.writeImage(os, image)
            os.flush()
        } ?: throw IllegalStateException("openOutputStream failed for $uri")

        // Intentionally no ExifInterface edits for DNG:
        // ExifInterface can rewrite the whole file (big + slow), and provides no benefit we can't live without.
    }

    private fun setExifDateTimeOriginal(uri: Uri, exifDateTimeOriginal: String, orientation: Int? = null) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)

                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDateTimeOriginal)
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exifDateTimeOriginal)
                exif.setAttribute(ExifInterface.TAG_DATETIME, exifDateTimeOriginal)

                // For DNG: ensure viewers rotate correctly
                if (orientation != null && orientation != PlatformExif.ORIENTATION_UNDEFINED) {
                    exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                }

                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set EXIF attributes: ${e.message}")
        }
    }

    private fun imageToBytes(image: Image): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    private fun failAndCleanupCurrentPhoto() {
        val cap = currentPhoto ?: return
        currentPhoto = null
        cap.finished = true
        try { cap.wideImage?.close() } catch (_: Exception) {}
        try { cap.ultraImage?.close() } catch (_: Exception) {}

        val resolver = context.contentResolver
        MediaStoreUtils.delete(resolver, cap.wideUri)
        MediaStoreUtils.delete(resolver, cap.ultraUri)

        // so we don’t leave a dangling pending _sbs.jpg entry
        cap.sbsUri?.let { MediaStoreUtils.delete(resolver, it) }

        // so we don’t leave a dangling pending _photo.json entry
        cap.logUri?.let { MediaStoreUtils.delete(resolver, it) }

        callback.onPhotoCaptureDone()
    }

    // ---------------------------------------------------------------------------------------------
    // Request settings (no stabilization/crop, torch, etc.)
    // ---------------------------------------------------------------------------------------------

    private fun applyTorch(builder: CaptureRequest.Builder) {
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )
    }

    private fun updateTorchOnRepeating() {
        val sess = session ?: return
        val handler = cameraHandler ?: return
        val rb = repeatingBuilder ?: return
        applyTorch(rb)
        try {
            //sess.setRepeatingRequest(rb.build(), null, handler)
            val cb = if (isRecording) recordCaptureCallback else null
            sess.setRepeatingRequest(rb.build(), cb, handler)
        } catch (e: Exception) {
            Log.w(TAG, "updateTorchOnRepeating failed: ${e.message}")
        }
    }

    private fun centeredRect(active: Rect, fraction: Float): Rect {

        // Creates a new Rect that is centered within the active rectangle and
        // scaled by the given fraction.

        val f = fraction.coerceIn(0.05f, 1.0f)
        val w = active.width()
        val h = active.height()

        val newW = (w * f).toInt().coerceAtLeast(1)
        val newH = (h * f).toInt().coerceAtLeast(1)

        val left = active.left + (w - newW) / 2
        val top = active.top + (h - newH) / 2

        val r = Rect(left, top, left + newW, top + newH)
        r.intersect(active) // safety clamp
        return r
    }

    private fun applyUltrawide3ARegions(builder: CaptureRequest.Builder) {

        // constrain ultrawide 3A to overlap-ish center region

        val ultra = ultraChars ?: return

        // Only set regions if supported for that physical camera.
        val maxAf = ultra.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val maxAe = ultra.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        val maxAwb = ultra.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB) ?: 0

        if (maxAf <= 0 && maxAe <= 0 && maxAwb <= 0) return

        val active = ultra.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        val rect = centeredRect(active, ultra3aFraction)

        // In 2x mode, the aligner will software-crop the ultrawide to the centered inner 50%.
        // Keep ultrawide 3A (AE/AF/AWB metering) inside that same effective region.
        if (zoom2xEnabled) {
            rect.intersect(centeredRect(active, 0.50f))
        }

        val region = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)
        val regions = arrayOf(region)

        // set the AF/AE/AWB regions if supported
        // (number of regions > 0 since we want to use 1 region)
        if (maxAf > 0) {
            setPhysical(builder, ultraPhysicalId, CaptureRequest.CONTROL_AF_REGIONS, regions)
        }
        if (maxAe > 0) {
            setPhysical(builder, ultraPhysicalId, CaptureRequest.CONTROL_AE_REGIONS, regions)
        }
        if (maxAwb > 0) {
            setPhysical(builder, ultraPhysicalId, CaptureRequest.CONTROL_AWB_REGIONS, regions)
        }
    }

    private fun chooseAeFpsRange(chars: CameraCharacteristics?, desiredFps: Int): Range<Int>? {
        val ranges = chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null

        // Prefer a locked range if the device exposes it.
        ranges.firstOrNull { it.lower == desiredFps && it.upper == desiredFps }?.let { return it }

        val candidates = ranges.filter { it.lower <= desiredFps && it.upper >= desiredFps }
        if (candidates.isEmpty()) {
            // Fallback: pick the highest available range.
            return ranges.maxByOrNull { it.upper }
        }

        // Choose the tightest range that still contains desiredFps.
        return candidates.minWithOrNull(
            compareBy<Range<Int>> { it.upper - it.lower }.thenBy { it.lower }
        ) ?: candidates.first()
    }

    private fun applyCommonNoCropNoStab(builder: CaptureRequest.Builder, isVideo: Boolean, targetFps: Int) {

        // EIS toggle (video only)
        if (isVideo && eisEnabled) {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )
            builder.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            )
        } else {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
            builder.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        }

        // Target FPS (logical + per-physical best effort)
        val logicalRange =
            chooseAeFpsRange(logicalChars, targetFps)
                ?: chooseAeFpsRange(wideChars, targetFps)
                ?: chooseAeFpsRange(ultraChars, targetFps)

        if (logicalRange != null) {
            try {
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, logicalRange)
            } catch (_: Exception) {
            }
        }

        val wideRange = chooseAeFpsRange(wideChars, targetFps) ?: logicalRange
        if (wideRange != null) {
            setPhysical(builder, widePhysicalId, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, wideRange)
        }

        val ultraRange = chooseAeFpsRange(ultraChars, targetFps) ?: logicalRange
        if (ultraRange != null) {
            setPhysical(builder, ultraPhysicalId, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, ultraRange)
        }

        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

        // constrain ultrawide 3A to overlap-ish center region
        applyUltrawide3ARegions(builder)
    }

    private fun <T> setPhysical(builder: CaptureRequest.Builder, physicalId: String, key: CaptureRequest.Key<T>, value: T) {
        try {
            // Correct order: (key, value, physicalCameraId)
            builder.setPhysicalCameraKey(key, value, physicalId)
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun resolveWide2xPhysicalId(physicalIds: Set<String>): String? {
        // Prefer Pro mapping first (because Pro devices also *contain* "4", but it's tele there).
        return when {
            physicalIds.contains(WIDE_2X_PHYSICAL_ID_PRO) -> WIDE_2X_PHYSICAL_ID_PRO
            physicalIds.contains(WIDE_2X_PHYSICAL_ID) -> WIDE_2X_PHYSICAL_ID
            else -> null
        }
    }

     private fun closeReaders() {
        try { wideReader?.close() } catch (_: Exception) {}
        try { ultraReader?.close() } catch (_: Exception) {}
        wideReader = null
        ultraReader = null
    }

    private fun computeOrientationHint(): Int {
        val rotationDegrees = when (displayRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        // Back camera: (sensor - device + 360) % 360
        return (sensorOrientation - rotationDegrees + 360) % 360
    }

    private fun computeJpegOrientation(): Int = computeOrientationHint()

    private fun exifOrientationFromDegrees(deg: Int): Int = when (deg) {
        0 -> PlatformExif.ORIENTATION_NORMAL
        90 -> PlatformExif.ORIENTATION_ROTATE_90
        180 -> PlatformExif.ORIENTATION_ROTATE_180
        270 -> PlatformExif.ORIENTATION_ROTATE_270
        else -> PlatformExif.ORIENTATION_UNDEFINED
    }


    private fun exifDateTimeOriginal(timestampMillis: Long): String {
        val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        return sdf.format(Date(timestampMillis))
    }

    private fun safeStopRecordingInternal(deleteOnFailure: Boolean) {
        // Stop session output
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.abortCaptures() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}

        session = null
        repeatingBuilder = null
        recordCaptureCallback = null

        val resolver = context.contentResolver

        // Stop encoders
        val wEnc = wideEncoder
        val uEnc = ultraEncoder
        val aEnc = audioEncoder
        val wMux = wideMuxer
        val uMux = ultraMuxer

        wideEncoder = null
        ultraEncoder = null
        audioEncoder = null
        wideMuxer = null
        ultraMuxer = null

        try { wEnc?.signalEndOfInputStream() } catch (_: Exception) {}
        try { uEnc?.signalEndOfInputStream() } catch (_: Exception) {}

        try { aEnc?.stopAndRelease() } catch (_: Exception) {}
        try { wEnc?.stopAndRelease() } catch (_: Exception) {}
        try { uEnc?.stopAndRelease() } catch (_: Exception) {}

        try { wMux?.stopAndRelease() } catch (_: Exception) {}
        try { uMux?.stopAndRelease() } catch (_: Exception) {}

        val wUri = wideVideoUri
        val uUri = ultraVideoUri
        wideVideoUri = null
        ultraVideoUri = null

        if (wUri != null && uUri != null) {
            if (deleteOnFailure) {
                MediaStoreUtils.delete(resolver, wUri)
                MediaStoreUtils.delete(resolver, uUri)
            } else {
                MediaStoreUtils.finalizePending(resolver, wUri)
                MediaStoreUtils.finalizePending(resolver, uUri)
            }
        }

        // NEW logging stuff
        val logUri = recordingLogUri
        recordingLogUri = null

        // If we created a log entry but failed, delete it so it doesn't remain pending.
        if (logUri != null) {
            if (deleteOnFailure) {
                MediaStoreUtils.delete(resolver, logUri)
            } else {
                // Best-effort: write whatever we have and finalize
                try {
                    recordingLogger?.markStopped()
                    recordingLogger?.writeToUri(context, logUri)
                    MediaStoreUtils.finalizePending(resolver, logUri)
                } catch (_: Exception) {
                    MediaStoreUtils.delete(resolver, logUri)
                }
            }
        }
        recordingLogger = null
        // end NEW logging stuff
    }
}
