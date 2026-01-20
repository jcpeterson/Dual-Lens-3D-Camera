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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import com.example.duallens3dcamera.encoding.AudioEncoder
import com.example.duallens3dcamera.encoding.Mp4Muxer
import com.example.duallens3dcamera.encoding.VideoEncoder
import com.example.duallens3dcamera.media.MediaStoreUtils
import com.example.duallens3dcamera.util.SizeSelector
import com.example.duallens3dcamera.logging.StereoRecordingLogger
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
        private const val WIDE_PHYSICAL_ID = "2"
        private const val ULTRA_PHYSICAL_ID = "3"

        private val PREFERRED_RECORD_SIZE = Size(1440, 1080)
        private const val TARGET_FPS = 30

        // Video knobs (easy to tweak in one place)
        private const val VIDEO_BITRATE_BPS = 100_000_000 // 100 Mbps per stream
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

        // disable ALL logging (set false to disable)
        private const val ENABLE_STEREO_RECORDING_LOG = true

        // NEW: minimal logging (frameNumber and SENSOR_TIMESTAMP only)
        // (no exposure, no muxer info, etc.)
        private const val STEREO_LOG_FRAMES_ONLY = false

    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    // threads so photos can output during shutter spamming
//    private val ioExecutor = Executors.newSingleThreadExecutor()
    // two threads instead for jpg/dng output
    private val ioExecutor = Executors.newFixedThreadPool(2)
    private val encodingExecutor = Executors.newSingleThreadExecutor()

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    private var surfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null

    private var logicalChars: CameraCharacteristics? = null
    private var wideChars: CameraCharacteristics? = null
    private var ultraChars: CameraCharacteristics? = null

    private var wideMap: StreamConfigurationMap? = null
    private var ultraMap: StreamConfigurationMap? = null

    private var sensorOrientation: Int = 90
    private var displayRotation: Int = Surface.ROTATION_0

    private var recordSize: Size = PREFERRED_RECORD_SIZE
//    private var previewSize: Size = Size(1280, 960)
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
    private var videoBitrateBps: Int = 50_000_000             // default 50 Mbps
    private var eisEnabled: Boolean = false                   // default OFF

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
        val wideUri: Uri,
        val ultraUri: Uri,
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
        initialVideoBitrateBps: Int,
        initialEisEnabled: Boolean
    ): PreviewConfig {
        this.surfaceTexture = surfaceTexture
        this.displayRotation = displayRotation
        this.isRawMode = initialRawMode
        this.torchOn = initialTorchOn

        // NEW:
        this.requestedRecordSize = initialRequestedRecordSize
        this.videoBitrateBps = initialVideoBitrateBps
        this.eisEnabled = initialEisEnabled

        startThreads()
        selectCharacteristicsAndSizes()

        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface = Surface(surfaceTexture)

        openCamera()

        return PreviewConfig(previewSize = previewSize, sensorOrientation = sensorOrientation, recordSize = recordSize)
    }

    fun setVideoConfig(requestedRecordSize: Size, videoBitrateBps: Int, eisEnabled: Boolean) {
        this.requestedRecordSize = requestedRecordSize
        this.videoBitrateBps = videoBitrateBps
        this.eisEnabled = eisEnabled

        // If camera is running and we're idle, validate the requested size now and toast fallback if needed.
        val handler = cameraHandler
        if (handler != null) {
            handler.post {
                if (!isRecording && wideMap != null && ultraMap != null) {
                    recomputeRecordSizeLocked()
                }
            }
        }
    }

    private fun recomputeRecordSizeLocked() {
        val wMap = wideMap ?: return
        val uMap = ultraMap ?: return

        val (chosenRecord, fallbackMsg) = SizeSelector.chooseCommonFourByThreeSizeAt30FpsPrivate(
            wideMap = wMap,
            ultraMap = uMap,
            preferred = requestedRecordSize,
            maxW = requestedRecordSize.width,
            maxH = requestedRecordSize.height,
            targetFps = TARGET_FPS
        )
        recordSize = chosenRecord
        fallbackMsg?.let { callback.onFallbackSizeUsed(it) }

        Log.i(TAG, "VideoConfig requested=${requestedRecordSize.width}x${requestedRecordSize.height} chosen=${recordSize.width}x${recordSize.height} bitrate=$videoBitrateBps eis=$eisEnabled")
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
        isRawMode = raw
        val handler = cameraHandler ?: return
        handler.post {
            if (isRecording) return@post
            if (cameraDevice == null || previewSurface == null) return@post
            createPreviewSession()
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

            if (raw != isRawMode) {
                isRawMode = raw
                createPreviewSession()
            }

            val nowMs = System.currentTimeMillis()
            val exifDt = exifDateTimeOriginal(nowMs)

            try {
                val wideName = if (raw) "${timestampForFilename}_wide.dng" else "${timestampForFilename}_wide.jpg"
                val ultraName = if (raw) "${timestampForFilename}_ultrawide.dng" else "${timestampForFilename}_ultrawide.jpg"
                val wideMime = if (raw) "image/x-adobe-dng" else "image/jpeg"
                val ultraMime = if (raw) "image/x-adobe-dng" else "image/jpeg"

                val wideUri = MediaStoreUtils.createPendingImage(context, wideName, wideMime, nowMs)
                val ultraUri = MediaStoreUtils.createPendingImage(context, ultraName, ultraMime, nowMs)

                currentPhoto = PhotoCapture(
                    isRaw = raw,
                    wideUri = wideUri,
                    ultraUri = ultraUri,
                    exifDateTimeOriginal = exifDt
                )

                doStillCapture(raw)
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
                    fps = TARGET_FPS,
                    bitrateBps = videoBitrateBps,
                    iFrameIntervalSec = IFRAME_INTERVAL_SEC,
                    muxer = requireNotNull(wideMuxer)
                ).also { it.start() }

                ultraEncoder = VideoEncoder(
                    tag = "UltraVideoEncoder",
                    width = recordSize.width,
                    height = recordSize.height,
                    fps = TARGET_FPS,
                    bitrateBps = videoBitrateBps,
                    iFrameIntervalSec = IFRAME_INTERVAL_SEC,
                    muxer = requireNotNull(ultraMuxer)
                ).also { it.start() }

                if (ENABLE_STEREO_RECORDING_LOG) {
                    // for logging (create one JSON per recording)
                    recordingLogUri = MediaStoreUtils.createPendingJson(
                        context = context,
                        displayName = "${timestampForFilename}_stereo.json",
                        timestampMs = nowMs
                    )
                }

                // Read logical camera metadata for timestamp source + sensor sync type
                val logicalForLog = cameraManager.getCameraCharacteristics(LOGICAL_REAR_ID)

                // Must pass non-null URIs to the logger
                val wideUriForLog = requireNotNull(wideVideoUri)
                val ultraUriForLog = requireNotNull(ultraVideoUri)

                if (ENABLE_STEREO_RECORDING_LOG) {

                    val tsSource = logicalForLog.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
                    val syncType =
                        logicalForLog.get(CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE)
                    // Build logger (keep it alive until stopRecording writes + finalizes the JSON)
                    val logger = StereoRecordingLogger(
                        recordingId = timestampForFilename,
                        logicalCameraId = LOGICAL_REAR_ID,
                        widePhysicalId = WIDE_PHYSICAL_ID,
                        ultraPhysicalId = ULTRA_PHYSICAL_ID,
                        recordSize = recordSize,
                        targetFps = TARGET_FPS,
                        videoBitrateBps = videoBitrateBps,
                        eisEnabled = eisEnabled,
                        orientationHintDegrees = orient,
                        sensorTimestampSource = tsSource,
                        sensorSyncType = syncType,
                        wideVideoUri = wideUriForLog,
                        ultraVideoUri = ultraUriForLog,
                        framesOnly = STEREO_LOG_FRAMES_ONLY , // minimal logging
                    )
                    recordingLogger = logger

                    // only run encoder callback hookups in full logging mode
                    if (!STEREO_LOG_FRAMES_ONLY ) {

                        // Attach encoder callbacks using NON-NULL local references
                        val wEncLocal = requireNotNull(wideEncoder)
                        val uEncLocal = requireNotNull(ultraEncoder)
                        val aEncLocal = requireNotNull(audioEncoder)

                        wEncLocal.onEncodedSample =
                            { codecPtsUs, muxerPtsUs, sizeBytes, flags, writeNs ->
                                logger.onWideVideoSample(
                                    codecPtsUs,
                                    muxerPtsUs,
                                    sizeBytes,
                                    flags,
                                    writeNs
                                )
                            }
                        uEncLocal.onEncodedSample =
                            { codecPtsUs, muxerPtsUs, sizeBytes, flags, writeNs ->
                                logger.onUltraVideoSample(
                                    codecPtsUs,
                                    muxerPtsUs,
                                    sizeBytes,
                                    flags,
                                    writeNs
                                )
                            }
                        aEncLocal.onEncodedSample =
                            { codecPtsUs, muxerPtsUs, sizeBytes, flags, writeNs ->
                                logger.onAudioSample(
                                    codecPtsUs,
                                    muxerPtsUs,
                                    sizeBytes,
                                    flags,
                                    writeNs
                                )
                            }
                    } else {
                        // Make extra-sure sample logging stays off.
                        wideEncoder?.onEncodedSample = null
                        ultraEncoder?.onEncodedSample = null
                        audioEncoder?.onEncodedSample = null
                    }
                    // end logging setup
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
        logicalChars = cameraManager.getCameraCharacteristics(LOGICAL_REAR_ID)
        wideChars = cameraManager.getCameraCharacteristics(WIDE_PHYSICAL_ID)
        ultraChars = cameraManager.getCameraCharacteristics(ULTRA_PHYSICAL_ID)

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


        val wide = requireNotNull(wideChars)
        val ultra = requireNotNull(ultraChars)

        val caps = logical.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
        if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
            callback.onError("Rear camera is not LOGICAL_MULTI_CAMERA. This app targets Pixel 7 (logical 0).")
            return
        }

        val physicalIds = logical.physicalCameraIds
        if (!physicalIds.contains(WIDE_PHYSICAL_ID) || !physicalIds.contains(ULTRA_PHYSICAL_ID)) {
            callback.onError("Expected physical IDs 2 and 3 not found under logical 0. Found: $physicalIds")
            return
        }

        sensorOrientation = wide.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        wideMap = wide.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ultraMap = ultra.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val wMap = requireNotNull(wideMap)
        val uMap = requireNotNull(ultraMap)

        recomputeRecordSizeLocked()

        jpegWideSize = SizeSelector.chooseLargestJpegFourByThree(wMap)
        jpegUltraSize = SizeSelector.chooseLargestJpegFourByThree(uMap)

        Log.i(TAG, "JPEG wide=${jpegWideSize.width}x${jpegWideSize.height}, ultra=${jpegUltraSize.width}x${jpegUltraSize.height}")

        rawWideSize = SizeSelector.chooseLargestRaw(wMap)
        rawUltraSize = SizeSelector.chooseLargestRaw(uMap)

    }

    private fun openCamera() {
        val handler = requireNotNull(cameraHandler)
        try {
            cameraManager.openCamera(LOGICAL_REAR_ID, object : CameraDevice.StateCallback() {
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

        val raw = isRawMode
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
            add(OutputConfiguration(preview).apply { setPhysicalCameraId(WIDE_PHYSICAL_ID) })
            add(OutputConfiguration(requireNotNull(wideReader).surface).apply { setPhysicalCameraId(WIDE_PHYSICAL_ID) })
            add(OutputConfiguration(requireNotNull(ultraReader).surface).apply { setPhysicalCameraId(ULTRA_PHYSICAL_ID) })
        }

        camera.createCaptureSessionByOutputConfigurations(
            outputs,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(sess: CameraCaptureSession) {
                    session = sess
                    try {
                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(preview)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            applyCommonNoCropNoStab(this, isVideo = false)
                            applyTorch(this)
                        }
                        repeatingBuilder = builder
                        sess.setRepeatingRequest(builder.build(), null, handler)
                    } catch (e: Exception) {
                        callback.onError("Preview repeating request failed: ${e.message}")
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
            add(OutputConfiguration(preview).apply { setPhysicalCameraId(WIDE_PHYSICAL_ID) })
            add(OutputConfiguration(wideEnc.inputSurface).apply { setPhysicalCameraId(WIDE_PHYSICAL_ID) })
            add(OutputConfiguration(ultraEnc.inputSurface).apply { setPhysicalCameraId(ULTRA_PHYSICAL_ID) })
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

                            applyCommonNoCropNoStab(this, isVideo = true)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(TARGET_FPS, TARGET_FPS))
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            applyTorch(this)
                        }

                        // NEW: for logging
                        repeatingBuilder = builder

                        val captureCallback: CameraCaptureSession.CaptureCallback? =
                            if (ENABLE_STEREO_RECORDING_LOG) {

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
                                            phys[WIDE_PHYSICAL_ID]?.let {
                                                logger.onPhysicalCaptureResult(WIDE_PHYSICAL_ID, frameNo, it)
                                            }
                                            phys[ULTRA_PHYSICAL_ID]?.let {
                                                logger.onPhysicalCaptureResult(ULTRA_PHYSICAL_ID, frameNo, it)
                                            }
                                        } else {
                                            // Fallback: at least log the logical result under "wide"
                                            logger.onPhysicalCaptureResult(WIDE_PHYSICAL_ID, frameNo, result)
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
                                                logger.onBufferLost(WIDE_PHYSICAL_ID, "wideEncoder", frameNumber)

                                            target === ultraEncSurface ->
                                                logger.onBufferLost(ULTRA_PHYSICAL_ID, "ultraEncoder", frameNumber)

                                            target === previewSurfaceLocal ->
                                                logger.onBufferLost(WIDE_PHYSICAL_ID, "preview", frameNumber)

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

        try {
            try { sess.stopRepeating() } catch (_: Exception) {}

            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(wideR.surface)
                addTarget(ultraR.surface)

                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

                set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)

                // Best-effort: ZSL/HDR off
                try { set(CaptureRequest.CONTROL_ENABLE_ZSL, false) } catch (_: Exception) {}
                set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

                applyCommonNoCropNoStab(this, isVideo = false)
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

                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
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

        val ready = if (cap.isRaw) {
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
        val exifDt = cap.exifDateTimeOriginal
        val isRaw = cap.isRaw

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
                } catch (e: Exception) {
                    Log.e(TAG, "JPEG save failed: ${e.message}", e)
                    MediaStoreUtils.delete(resolver, wideUri)
                    MediaStoreUtils.delete(resolver, ultraUri)
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

                    saveDng(wideUri, wideCharacteristics, total, wideImage, exifDt)
                    saveDng(ultraUri, ultraCharacteristics, total, ultraImage, exifDt)

                    MediaStoreUtils.finalizePending(resolver, wideUri)
                    MediaStoreUtils.finalizePending(resolver, ultraUri)
                } catch (e: Exception) {
                    Log.e(TAG, "DNG save failed: ${e.message}", e)
                    MediaStoreUtils.delete(resolver, wideUri)
                    MediaStoreUtils.delete(resolver, ultraUri)
                    callback.onError("DNG save failed: ${e.message}")
                } finally {
                    // Close RAW Images only after DngCreator has consumed them.
                    try { wideImage.close() } catch (_: Exception) {}
                    try { ultraImage.close() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun saveJpegBytes(uri: Uri, bytes: ByteArray, exifDateTimeOriginal: String) {
        context.contentResolver.openOutputStream(uri)?.use { os ->
            os.write(bytes)
            os.flush()
        } ?: throw IllegalStateException("openOutputStream failed for $uri")

        // Only required metadata: DateTimeOriginal (and friends)
        setExifDateTimeOriginal(uri, exifDateTimeOriginal)
    }

    private fun saveDng(
        uri: Uri,
        characteristics: CameraCharacteristics,
        result: TotalCaptureResult,
        image: Image,
        exifDateTimeOriginal: String
    ) {
        val dngCreator = android.hardware.camera2.DngCreator(characteristics, result)

        // Match the same "upright" logic as JPEG/video, but convert degrees -> EXIF orientation constant.
        val orientationConst = exifOrientationFromDegrees(computeJpegOrientation())
        try {
            dngCreator.setOrientation(orientationConst)
        } catch (_: Exception) {
            // If it fails for any reason, we'll still try to set TAG_ORIENTATION via ExifInterface below.
        }

        context.contentResolver.openOutputStream(uri)?.use { os ->
            dngCreator.writeImage(os, image)
            os.flush()
        } ?: throw IllegalStateException("openOutputStream failed for $uri")

        // Set DateTimeOriginal (and enforce orientation tag as well for DNG viewers)
        setExifDateTimeOriginal(uri, exifDateTimeOriginal, orientationConst)
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
//        val rect = centeredRect(active, ULTRA_3A_FRACTION)
        val rect = centeredRect(active, ultra3aFraction)
        val region = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)
        val regions = arrayOf(region)

        // set the AF/AE/AWB regions if supported
        // (number of regions > 0 since we want to use 1 region)
        if (maxAf > 0) {
            setPhysical(builder, ULTRA_PHYSICAL_ID, CaptureRequest.CONTROL_AF_REGIONS, regions)
        }
        if (maxAe > 0) {
            setPhysical(builder, ULTRA_PHYSICAL_ID, CaptureRequest.CONTROL_AE_REGIONS, regions)
        }
        if (maxAwb > 0) {
            setPhysical(builder, ULTRA_PHYSICAL_ID, CaptureRequest.CONTROL_AWB_REGIONS, regions)
        }
    }

    private fun applyCommonNoCropNoStab(builder: CaptureRequest.Builder, isVideo: Boolean) {

        // EIS toggle (video only)
        if (isVideo && eisEnabled) {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            // Might as well do OIS if EIS is on even though Pixel 7 ultrawide in particular can't do it.
            // Not sure OIS off requests are allowed anyway. Likely not.
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        } else {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        }

        // set target fps for both logical AND physical just in case it enforces it more strongly
        val fpsRange = Range(TARGET_FPS, TARGET_FPS)
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        // Also push the same FPS constraint to both physical cameras.
        setPhysical(builder, WIDE_PHYSICAL_ID, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        setPhysical(builder, ULTRA_PHYSICAL_ID, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1.0f)
            } catch (_: Exception) {}
        }

        // If EIS is enabled, don't force full crop region (EIS needs to crop).
        if (!(isVideo && eisEnabled)) {
            setFullCrop(builder)
        }

        try {
            builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
        } catch (_: Exception) {}

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

    private fun setFullCrop(builder: CaptureRequest.Builder) {
        val wide = wideChars ?: return
        val ultra = ultraChars ?: return
        val wideActive: Rect? = wide.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val ultraActive: Rect? = ultra.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

        // Logical (best-effort)
        if (wideActive != null) {
            try { builder.set(CaptureRequest.SCALER_CROP_REGION, wideActive) } catch (_: Exception) {}
        }

        // Per-physical
        if (wideActive != null) {
            setPhysical(builder, WIDE_PHYSICAL_ID, CaptureRequest.SCALER_CROP_REGION, wideActive)
        }
        if (ultraActive != null) {
            setPhysical(builder, ULTRA_PHYSICAL_ID, CaptureRequest.SCALER_CROP_REGION, ultraActive)
        }

        // Force OIS off per physical
        setPhysical(builder, WIDE_PHYSICAL_ID, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        setPhysical(builder, ULTRA_PHYSICAL_ID, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

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
