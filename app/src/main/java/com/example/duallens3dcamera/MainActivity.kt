package com.example.duallens3dcamera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.abs

// for cropping, SBS, and EXIF
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    private var sensorOrientation = 0

    companion object {
        private const val TAG = "DualLens3DCamera"
        private const val REQ_CAMERA = 1001
        private const val ALBUM_DIR = "DualLens3DCamera"
        private const val JPEG_CAPTURE_QUALITY = 100
        private const val MAX_LOG_LINES = 20
    }

    // "Keep this fraction of the ultrawide image (center crop), then scale back up to match wide."
    // Will be computed on start from camera characteristics; fallback constant if unavailable.
    @Volatile private var ultraKeepFraction = 0.55f

    // properties for datetime exif stuff
    private var lastCaptureWallTimeMs: Long = 0

    private lateinit var viewFinder: TextureView
    private lateinit var captureButton: Button
    private lateinit var flashButton: Button
    private lateinit var statusText: TextView

    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    private var logicalCameraId: String? = null
    private var widePhysicalId: String? = null
    private var ultraPhysicalId: String? = null

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var previewRequest: CaptureRequest? = null

    private var wideReader: ImageReader? = null
    private var ultraReader: ImageReader? = null
    private var stillSize: Size? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null

    private val sessionExecutor = Executors.newSingleThreadExecutor()

    // State variables
    private var isTorchOn = false
    private var isLogVisible = false
    private val logMessages = LinkedList<String>()

    private data class JpegResult(val bytes: ByteArray, val timestampNs: Long)
    private val pairLock = Any()
    private var captureArmed = false
    private var pendingWide: JpegResult? = null
    private var pendingUltra: JpegResult? = null

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { maybeStartCamera() }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        captureButton = findViewById(R.id.captureButton)
        flashButton = findViewById(R.id.flashButton)
        statusText = findViewById(R.id.statusText)

        // Set click listeners
        captureButton.setOnClickListener { takeStereo() }
        flashButton.setOnClickListener { toggleTorch() }
        viewFinder.setOnClickListener { toggleLogView() }

        // Initial setup
        captureButton.isEnabled = false
        statusText.visibility = View.GONE
        statusText.movementMethod = ScrollingMovementMethod.getInstance()
        updateLog(" -- BEGIN LOG -- ")
        updateLog("Initializing...")
    }

    override fun onResume() {
        super.onResume()
        startThreads()
        if (viewFinder.isAvailable) {
            maybeStartCamera()
        } else {
            viewFinder.surfaceTextureListener = textureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopThreads()
        super.onPause()
    }

    private fun startThreads() {
        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
        imageThread = HandlerThread("ImageThread").apply { start() }
        imageHandler = Handler(imageThread!!.looper)
    }

    private fun stopThreads() {
        cameraThread?.quitSafely()
        imageThread?.quitSafely()
        try {
            cameraThread?.join()
            imageThread?.join()
        } catch (_: InterruptedException) {}
        cameraThread = null
        cameraHandler = null
        imageThread = null
        imageHandler = null
    }

    private fun maybeStartCamera() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        startCamera()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                maybeStartCamera()
            } else {
                updateLog("Camera permission required.")
            }
        }
    }

    private data class SelectedCameras(val logicalId: String, val widePhysicalId: String, val ultraPhysicalId: String)
    private data class PhysicalInfo(val id: String, val minFocalMm: Float)

    private fun startCamera() {
        if (cameraDevice != null || !viewFinder.isAvailable) return

        try {
            val selected = selectRearLogicalWideUltra()
            logicalCameraId = selected.logicalId
            widePhysicalId = selected.widePhysicalId
            ultraPhysicalId = selected.ultraPhysicalId

            // crop prep
            ultraKeepFraction = computeUltraKeepFraction(selected.widePhysicalId, selected.ultraPhysicalId)
            updateLog("Ultra keep fraction: ${"%.3f".format(ultraKeepFraction)}")

            // just debug logging
//            val wChars = cameraManager.getCameraCharacteristics(selected.widePhysicalId)
//            val uChars = cameraManager.getCameraCharacteristics(selected.ultraPhysicalId)
//
//            val wPre = wChars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
//            val wAct = wChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
//
//            val uPre = uChars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
//            val uAct = uChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
//
//            updateLog("W pre=$wPre act=$wAct")
//            updateLog("U pre=$uPre act=$uAct")

            val characteristics = cameraManager.getCameraCharacteristics(selected.logicalId)
            // figure out the canonical sensor orientation
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            val size = chooseCommonJpegSize(selected.widePhysicalId, selected.ultraPhysicalId)
            stillSize = size

            setupImageReaders(size)

            updateLog("Found cameras.")
            updateLog("Per-camera image size: ${size.width}x${size.height}.")

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            cameraManager.openCamera(selected.logicalId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    updateLog("Camera disconnected.")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    updateLog("Camera error: $error")
                }
            }, cameraHandler)

        } catch (e: Exception) {
            Log.e(TAG, "startCamera failed", e)
            updateLog("Start failed: ${e.message}")
        }
    }

    private fun selectRearLogicalWideUltra(): SelectedCameras {
        val ids = cameraManager.cameraIdList.toList()

        for (id in ids) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
            val isLogical = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            if (!isLogical) continue

            val physicalIds = chars.physicalCameraIds
            if (physicalIds.size < 2) continue

            val phys = physicalIds.mapNotNull { pid ->
                try {
                    val pc = cameraManager.getCameraCharacteristics(pid)
                    val focals = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val minFocal = focals?.minOrNull()
                    if (minFocal == null) null else PhysicalInfo(pid, minFocal)
                } catch (_: Exception) {
                    null
                }
            }.sortedBy { it.minFocalMm }

            // Sorted: smallest focal = ultra-wide, next = wide.
            if (phys.size >= 2) {
                val ultra = phys[0].id
                val wide = phys[1].id
                return SelectedCameras(logicalId = id, widePhysicalId = wide, ultraPhysicalId = ultra)
            }
        }

        throw IllegalStateException("No rear logical multi-camera found (Pixel 7 should support it).")
    }

    private fun chooseCommonJpegSize(wideId: String, ultraId: String): Size {
        fun jpegSizes(cameraId: String): List<Size> {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
            // Query for JPEG sizes
            val sizes = map.getOutputSizes(ImageFormat.JPEG) ?: return emptyList()
            return sizes.toList()
        }

        val wideSizes = jpegSizes(wideId)
        val ultraSizes = jpegSizes(ultraId)

        // Find common sizes between the two physical cameras
        val ultraSet = ultraSizes.map { it.width to it.height }.toSet()
        val common = wideSizes.filter { ultraSet.contains(it.width to it.height) }

        if (common.isEmpty()) {
            throw IllegalStateException("No common JPEG sizes found between wide and ultra-wide cameras.")
        }

        // Find the largest common size for the two camera streams.
        // In many cases, this will be ~12MP, especially on Pixel devices.
        return common.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: throw IllegalStateException("Could not find a max size.")
    }

    private fun chooseOptimalPreviewSize(cameraId: String): Size {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val outputSizes = map.getOutputSizes(SurfaceTexture::class.java)
        return outputSizes.filter { it.width <= 1920 && it.height <= 1080 }
            .maxByOrNull { it.width.toLong() * it.height.toLong() } ?: outputSizes[0]
    }

    private fun setupImageReaders(size: Size) {
        wideReader?.close()
        ultraReader?.close()

        wideReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        ultraReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)

        wideReader!!.setOnImageAvailableListener({ reader ->
            reader.acquireLatestImage()?.let { handleImage(isWide = true, image = it) }
        }, imageHandler)
        ultraReader!!.setOnImageAvailableListener({ reader ->
            reader.acquireLatestImage()?.let { handleImage(isWide = false, image = it) }
        }, imageHandler)
    }

    private fun createSession() {
        val cam = cameraDevice ?: return
        val wideId = widePhysicalId ?: return
        val ultraId = ultraPhysicalId ?: return
        val w = wideReader ?: return
        val u = ultraReader ?: return

        val surfaceTexture = viewFinder.surfaceTexture ?: return
        val previewSize = chooseOptimalPreviewSize(wideId)
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface = Surface(surfaceTexture)

        val wideConfig = OutputConfiguration(w.surface).apply { setPhysicalCameraId(wideId) }
        val ultraConfig = OutputConfiguration(u.surface).apply { setPhysicalCameraId(ultraId) }
        // set preview to be the wide camera
        val previewConfig = OutputConfiguration(previewSurface!!).apply { setPhysicalCameraId(wideId) }

        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(previewConfig, wideConfig, ultraConfig),
            sessionExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    startPreview()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    updateLog("Capture session config failed.")
                }
            })
        try {
            cam.createCaptureSession(sessionConfig)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "createCaptureSession failed", e)
            updateLog("Session creation failed: ${e.message}")
        }
    }

    private fun startPreview() {
        updatePreview()
        runOnUiThread {
            captureButton.isEnabled = true
            flashButton.isEnabled = true
            updateLog("Everything looks good. Ready to shoot!")
        }
    }

    private fun updatePreview() {
        val s = session ?: return
        val cam = cameraDevice ?: return
        try {
            val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(previewSurface!!)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

            if (isTorchOn) {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            } else {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }

            s.setRepeatingRequest(builder.build(), null, cameraHandler)

        } catch (e: Exception) {
            Log.e(TAG, "updatePreview failed", e)
            updateLog("Preview update failed: ${e.message}")
        }
    }

    // In toggleTorch()
    private fun toggleTorch() {
        isTorchOn = !isTorchOn
        val torchState = if (isTorchOn) "ON" else "OFF"
        flashButton.text = "TORCH $torchState"
        updatePreview()
    }

    private fun toggleLogView() {
        isLogVisible = !isLogVisible
        runOnUiThread {
            statusText.visibility = if (isLogVisible) View.VISIBLE else View.GONE
        }
    }


    private fun takeStereo() {

        // will be used for datetime EXIF later
        lastCaptureWallTimeMs = System.currentTimeMillis()

        val cam = cameraDevice ?: run { updateLog("Camera not ready"); return }
        val s = session ?: run { updateLog("Session not ready"); return }
        val wideId = widePhysicalId ?: run { updateLog("No wide id"); return }
        val ultraId = ultraPhysicalId ?: run { updateLog("No ultra id"); return }
        val wide = wideReader ?: run { updateLog("No wide reader"); return }
        val ultra = ultraReader ?: run { updateLog("No ultra reader"); return }

        runOnUiThread {
            captureButton.isEnabled = false
            updateLog("Capturing...")
        }

        synchronized(pairLock) {
            captureArmed = true
            pendingWide = null
            pendingUltra = null
        }

        drain(wide)
        drain(ultra)

        try {
            val builder = cam.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE,
                setOf(wideId, ultraId)
            )
            builder.addTarget(wide.surface)
            builder.addTarget(ultra.surface)
            builder.set(CaptureRequest.JPEG_QUALITY, JPEG_CAPTURE_QUALITY.toByte())
            // lens distortion correction (highest quality)
            builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
//            builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)

            // Make sure torch stays on during photo capture also.
            // Otherwise, it blinks off.
            if (isTorchOn) {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            }

            // make sure it's rotated correctly
            val deviceRotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                display?.rotation ?: 0
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.rotation
            }
            val jpegOrientation = (sensorOrientation - (deviceRotation * 90) + 360) % 360
            builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)

            // autofocus, exposure, and white balance all set to AUTO
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

            s.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                    Log.e(TAG, "Capture failed: ${f.reason}")
                    updateLog("Capture failed!")
                    synchronized(pairLock) { captureArmed = false }
                    runOnUiThread { captureButton.isEnabled = true }
                }
//                // new debug for cropping
//                override fun onCaptureCompleted(
//                    session: CameraCaptureSession,
//                    request: CaptureRequest,
//                    result: TotalCaptureResult
//                ) {
//                    val wideId = widePhysicalId ?: return
//                    val ultraId = ultraPhysicalId ?: return
//
//                    val phys = result.physicalCameraTotalResults  // map: physicalId -> TotalCaptureResult
//
//                    val wRes = phys[wideId]
//                    val uRes = phys[ultraId]
//
//                    val wCrop = wRes?.get(CaptureResult.SCALER_CROP_REGION)
//                    val uCrop = uRes?.get(CaptureResult.SCALER_CROP_REGION)
//
////                    updateLog("W:$wCrop;U$uCrop")
//                    updateLog("U$uCrop")
//                }
            }, cameraHandler)

        } catch (e: Exception) {
            Log.e(TAG, "takeStereo failed", e)
            updateLog("Capture start failed: ${e.message}")
            synchronized(pairLock) { captureArmed = false }
            runOnUiThread { captureButton.isEnabled = true }
        }
    }

    private fun drain(reader: ImageReader) {
        try {
            while (true) {
                val img = reader.acquireLatestImage() ?: break
                img.close()
            }
        } catch (_: Exception) {}
    }

    // for cropping
    private fun computeUltraKeepFraction(wideId: String, ultraId: String): Float {

        // not cropping right
        val w = cameraManager.getCameraCharacteristics(wideId)
        val u = cameraManager.getCameraCharacteristics(ultraId)
//
//        // Preferred: intrinsics (if present). Gives fx in pixels; ratio tells us zoom needed.
//        val wIntr = w.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
//        val uIntr = u.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
//        val wActive = w.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
//        val uActive = u.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
//
//        if (wIntr != null && uIntr != null && wActive != null && uActive != null &&
//            wIntr.size >= 2 && uIntr.size >= 2
//        ) {
//            val fxW = wIntr[0] / wActive.width().toFloat()
//            val fyW = wIntr[1] / wActive.height().toFloat()
//            val fxU = uIntr[0] / uActive.width().toFloat()
//            val fyU = uIntr[1] / uActive.height().toFloat()
//
//            val keepX = fxU / fxW
//            val keepY = fyU / fyW
//
//            // Conservative: choose the smaller keep so UW won't remain wider in either axis
//            val keep = minOf(keepX, keepY).toFloat()
//
//            return keep.coerceIn(0.25f, 0.95f)
//        }

//        // new try with SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE
//        // still not cropping right
//        val w = cameraManager.getCameraCharacteristics(wideId)
//        val u = cameraManager.getCameraCharacteristics(ultraId)
//
//        val wIntr = w.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
//        val uIntr = u.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
//
//        // Intrinsics are in PRE_CORRECTION coordinates (per docs)
//        val wPre = w.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
//        val uPre = u.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
//
//        if (wIntr != null && uIntr != null && wPre != null && uPre != null &&
//            wIntr.size >= 2 && uIntr.size >= 2
//        ) {
//            val fxWn = wIntr[0] / wPre.width().toFloat()
//            val fyWn = wIntr[1] / wPre.height().toFloat()
//            val fxUn = uIntr[0] / uPre.width().toFloat()
//            val fyUn = uIntr[1] / uPre.height().toFloat()
//
//            val keepX = fxUn / fxWn
//            val keepY = fyUn / fyWn
//
//            return minOf(keepX, keepY).coerceIn(0.25f, 0.95f)
//        }

//        // Fallback: focal length + physical sensor size.
//        val wF = w.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
//        val uF = u.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
//        val wPhys = w.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
//        val uPhys = u.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
//
//        if (wF != null && uF != null && wPhys != null && uPhys != null) {
//            val fxW = wF / wPhys.width
//            val fyW = wF / wPhys.height
//            val fxU = uF / uPhys.width
//            val fyU = uF / uPhys.height
//
//            val keepX = fxU / fxW
//            val keepY = fyU / fyW
//
//            val keep = minOf(keepX, keepY).toFloat()
//            return keep.coerceIn(0.25f, 0.95f)
//        }

        // Last resort constant you can tune manually
//        return 0.55f
        return 0.59f
    }

    private fun handleImage(isWide: Boolean, image: Image) {
        val buffer = image.planes[0].buffer
        val jpegBytes = ByteArray(buffer.remaining())
        buffer.get(jpegBytes)
        val ts = image.timestamp
        image.close()

        var toSaveWide: JpegResult? = null
        var toSaveUltra: JpegResult? = null

        synchronized(pairLock) {
            if (!captureArmed) return
            if (isWide) pendingWide = JpegResult(jpegBytes, ts) else pendingUltra = JpegResult(jpegBytes, ts)
            if (pendingWide != null && pendingUltra != null) {

                // READY TO TAKE PHOTO AGAIN!
                // UNLOCK SHUTTER BUTTON
                runOnUiThread {
                    captureButton.isEnabled = true
                }

                captureArmed = false
                toSaveWide = pendingWide
                toSaveUltra = pendingUltra
                pendingWide = null
                pendingUltra = null
            }
        }
        if (toSaveWide != null && toSaveUltra != null) {
            savePair(toSaveWide!!, toSaveUltra!!)
        }
    }

    private fun savePair(wide: JpegResult, ultra: JpegResult) {

        val deltaMs = abs(wide.timestampNs - ultra.timestampNs) / 1_000_000.0

        // Use the accurate wall-clock time we saved for the filename
        val stamp = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US).format(Date(lastCaptureWallTimeMs))
        val base = stamp

//        // save individual shots without crop
//        saveJpeg("${base}_WIDE.jpg", wide.bytes, lastCaptureWallTimeMs)
//        saveJpeg("${base}_ULTRA.jpg", ultra.bytes, lastCaptureWallTimeMs)
//        updateLog("Saved: $base (Δt ${"%.4f".format(deltaMs)} ms)")

        // SBS output!
        try {
            val sbsBytes = buildFullResSbsJpeg(
                wideJpeg = wide.bytes,
                ultraJpeg = ultra.bytes,
                ultraKeep = ultraKeepFraction
            )
//            saveJpeg("${base}.jpg", sbsBytes, wide.timestampNs)
            saveJpeg("${base}.jpg", sbsBytes, lastCaptureWallTimeMs)
            updateLog("Saved: $base (Δt ${"%.4f".format(deltaMs)} ms)")
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OOM building SBS", oom)
            updateLog("SBS failed (OOM). Consider RGB_565 + region decode or smaller SBS.")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed building SBS", t)
            updateLog("SBS failed: ${t.message}")
        }

    }

    private fun buildFullResSbsJpeg(wideJpeg: ByteArray, ultraJpeg: ByteArray, ultraKeep: Float): ByteArray {
        // Decode BOTH as RGB_565 to reduce memory (huge difference for full-res SBS)
        val wideBmp = decodeJpegUpright(wideJpeg, prefer565 = true)
        val ultraBmp = decodeJpegUpright(ultraJpeg, prefer565 = true)

        // Crop ultrawide center to match wide framing and scale to wide size
        val ultraMatched = cropUltraToMatchWide(ultraBmp, wideBmp.width, wideBmp.height, ultraKeep)

        // Stitch SBS full-res
        val out = Bitmap.createBitmap(wideBmp.width * 2, wideBmp.height, Bitmap.Config.RGB_565)
        val canvas = Canvas(out)
//        canvas.drawBitmap(wideBmp, 0f, 0f, null)
//        canvas.drawBitmap(ultraMatched, wideBmp.width.toFloat(), 0f, null)
        canvas.drawBitmap(ultraMatched, 0f, 0f, null)
        canvas.drawBitmap(wideBmp, wideBmp.width.toFloat(), 0f, null)

        // Encode
        val baos = ByteArrayOutputStream()
        // output SBS jpg with 100% quality
        out.compress(Bitmap.CompressFormat.JPEG, 100, baos)

        // Cleanup aggressively
        wideBmp.recycle()
        ultraBmp.recycle()
        ultraMatched.recycle()
        out.recycle()

        return baos.toByteArray()
    }

    private fun cropUltraToMatchWide(srcUltra: Bitmap, wideW: Int, wideH: Int, keepFraction: Float): Bitmap {
        val keep = keepFraction.coerceIn(0.25f, 0.95f)

        val srcW = srcUltra.width
        val srcH = srcUltra.height
        val wideAspect = wideW.toFloat() / wideH.toFloat()

        // Start with width-based crop, then enforce aspect ratio
        var cropW = (srcW * keep).toInt().coerceIn(1, srcW)
        var cropH = (cropW / wideAspect).toInt()

        if (cropH > srcH) {
            cropH = (srcH * keep).toInt().coerceIn(1, srcH)
            cropW = (cropH * wideAspect).toInt().coerceIn(1, srcW)
        }

        val left = ((srcW - cropW) / 2).coerceAtLeast(0)
        val top = ((srcH - cropH) / 2).coerceAtLeast(0)

        val cropped = Bitmap.createBitmap(srcUltra, left, top, cropW, cropH)

        // Scale to match wide exactly
        val scaled = Bitmap.createScaledBitmap(cropped, wideW, wideH, true)
        if (scaled != cropped) cropped.recycle()

        return scaled
    }

    private fun decodeJpegUpright(jpeg: ByteArray, prefer565: Boolean): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = if (prefer565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            inDither = prefer565
        }

        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            ?: throw IllegalStateException("decodeByteArray failed")

        // Some devices rotate pixels; others set EXIF orientation.
        // We handle EXIF so output is always upright.
        val exif = ExifInterface(ByteArrayInputStream(jpeg))
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        return applyExifOrientation(bmp, orientation)
    }

    private fun applyExifOrientation(src: Bitmap, orientation: Int): Bitmap {
        if (orientation == ExifInterface.ORIENTATION_NORMAL) return src

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.preScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.preScale(-1f, 1f) }
            else -> return src
        }

        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out != src) src.recycle()
        return out
    }

    private fun saveJpeg(filename: String, bytes: ByteArray, captureTimeMs: Long) {
        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM_DIR")
        }

        var uri: Uri? = null
        try {
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw Exception("MediaStore insert failed")
            resolver.openOutputStream(uri).use { it?.write(bytes) }

            // BEGIN - Add Date Taken meta to jpeg
            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)

                // Use the wall-clock time passed directly into this function.
                val dateTime = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(captureTimeMs))
                val subSec = (captureTimeMs % 1000).toString().padStart(3, '0')

                // Set the EXIF tags
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime) // Date Taken
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateTime) // Date Digitized
                exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, subSec)
                exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, subSec)

                exif.setAttribute(ExifInterface.TAG_MAKE, android.os.Build.MANUFACTURER)
                exif.setAttribute(ExifInterface.TAG_MODEL, android.os.Build.MODEL)

                exif.saveAttributes()
            }
            // END - Add Date Taken meta to jpegs

        } catch (e: Exception) {
            Log.e(TAG, "Save failed for $filename", e)
            if (uri != null) resolver.delete(uri, null, null)
            updateLog("Save failed for $filename")
        }
    }

    private fun closeCamera() {
        try {
            session?.close()
            cameraDevice?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        } finally {
            session = null
            cameraDevice = null
        }
    }

    private fun updateLog(msg: String) {
        runOnUiThread {
            // Also log to the system Logcat for persistent debugging
            Log.d(TAG, "MESSAGE: $msg")

            // Add the new message to the top of our list
            logMessages.addLast(msg)

            // If the list is too long, remove the oldest message
            if (logMessages.size > MAX_LOG_LINES) {
                logMessages.removeFirst()
            }

            // Build the log string and set it on the TextView
            statusText.text = logMessages.joinToString("\n")
        }
    }
}
