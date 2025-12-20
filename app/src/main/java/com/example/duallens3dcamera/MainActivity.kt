// The goal of this app is to take photos with the main and
// ultrawide sensors at the same time for later stereoscopic use.

package com.example.duallens3dcamera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private var sensorOrientation = 0

    private val uiHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "DualLens3DCamera"
        private const val REQ_CAMERA = 1001
        private const val ALBUM_DIR = "DualLens3DCamera"
    }

    private lateinit var viewFinder: TextureView
    private lateinit var captureButton: Button
    private lateinit var statusText: TextView

    private val cameraManager by lazy {
        getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

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

    // Pairing state
    private data class JpegResult(val bytes: ByteArray, val timestampNs: Long)

    private val pairLock = Any()
    private var captureArmed = false
    private var pendingWide: JpegResult? = null
    private var pendingUltra: JpegResult? = null

    private fun chooseOptimalPreviewSize(cameraId: String): Size {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val outputSizes = map.getOutputSizes(SurfaceTexture::class.java)

        // Find the largest preview size that is no bigger than 1080p
//        return outputSizes.filter { it.width <= 1920 && it.height <= 1080 }
//            .maxByOrNull { it.width.toLong() * it.height.toLong() } ?: outputSizes[0]
        // Find the largest preview size that is no bigger than 480p
        return outputSizes.filter { it.width <= 640 && it.height <= 480 }
            .maxByOrNull { it.width.toLong() * it.height.toLong() } ?: outputSizes[0]
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            maybeStartCamera()
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        captureButton = findViewById(R.id.captureButton)
        statusText = findViewById(R.id.statusText)

        captureButton.setOnClickListener { takeStereo() }
        captureButton.isEnabled = false

//        statusText.text = "Waiting for preview surface…"
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
        } catch (_: InterruptedException) { }
        cameraThread = null
        cameraHandler = null
        imageThread = null
        imageHandler = null
    }

    private fun maybeStartCamera() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        startCamera()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (ok) maybeStartCamera() else toast("Camera permission required.")
        }
    }

    private data class SelectedCameras(
        val logicalId: String,
        val widePhysicalId: String,
        val ultraPhysicalId: String
    )

    private data class PhysicalInfo(val id: String, val minFocalMm: Float)

    private fun startCamera() {
        if (cameraDevice != null) return
        if (!viewFinder.isAvailable) return

        try {
            val selected = selectRearLogicalWideUltra()
            logicalCameraId = selected.logicalId
            widePhysicalId = selected.widePhysicalId
            ultraPhysicalId = selected.ultraPhysicalId

            // new
            val characteristics = cameraManager.getCameraCharacteristics(selected.logicalId)
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            val size = chooseCommonJpegSize(selected.widePhysicalId, selected.ultraPhysicalId)
            stillSize = size

            setupImageReaders(size)

//            toast(
//                buildString {
//                appendLine("Logical camera: ${selected.logicalId}")
//                appendLine("Wide physical: ${selected.widePhysicalId}")
//                appendLine("Ultra physical: ${selected.ultraPhysicalId}")
//                appendLine("Capture size: ${size.width}x${size.height} (JPEG)")
//            })

            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            cameraManager.openCamera(selected.logicalId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    toast("Camera disconnected.")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    toast("Camera error: $error")
                }
            }, cameraHandler)

        } catch (e: Exception) {
            Log.e(TAG, "startCamera failed", e)
            toast("Start failed: ${e.message}")
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

        // No need for MAX_STILL_WIDTH/HEIGHT anymore. Just find the largest common size.
        // This will give you the ~12MP resolution you're looking for.
        return common.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: throw IllegalStateException("Could not find a max size.")
    }


    private fun setupImageReaders(size: Size) {
        wideReader?.close()
        ultraReader?.close()

        wideReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        ultraReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)

        wideReader!!.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            handleImage(isWide = true, image = img)
        }, imageHandler)

        ultraReader!!.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            handleImage(isWide = false, image = img)
        }, imageHandler)
    }

    private fun createSession() {
        val cam = cameraDevice ?: return
        val wideId = widePhysicalId ?: return
        val ultraId = ultraPhysicalId ?: return
        val size = stillSize ?: return
        val ch = cameraHandler ?: return

        val surfaceTexture = viewFinder.surfaceTexture ?: return

//        surfaceTexture.setDefaultBufferSize(size.width, size.height)

        val previewSize = chooseOptimalPreviewSize(wideId)
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)

        previewSurface = Surface(surfaceTexture)

        val previewConfig = OutputConfiguration(previewSurface!!).apply { setPhysicalCameraId(wideId) }
        val wideConfig = OutputConfiguration(wideReader!!.surface).apply { setPhysicalCameraId(wideId) }
        val ultraConfig = OutputConfiguration(ultraReader!!.surface).apply { setPhysicalCameraId(ultraId) }

        val outputs = listOf(previewConfig, wideConfig, ultraConfig)

        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            sessionExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    startPreview()
                    runOnUiThread {
                        captureButton.isEnabled = true
                        toast("Ready. Hold phone in portrait orientation.")
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    session = null
                    runOnUiThread {
                        captureButton.isEnabled = false
                        toast("Session config failed.")
                    }
                }
            }
        )

        try {
            cam.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e(TAG, "createSession failed", e)
            toast("Create session failed: ${e.message}")
        }
    }

    private fun startPreview() {
        val cam = cameraDevice ?: return
        val s = session ?: return
        val wideId = widePhysicalId ?: return
        val handler = cameraHandler ?: return

        try {
            val builder = cam.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW,
                setOf(wideId)
            )
            builder.addTarget(previewSurface!!)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

            previewRequest = builder.build()
            s.setRepeatingRequest(previewRequest!!, null, handler)
        } catch (e: Exception) {
            Log.e(TAG, "startPreview failed", e)
            toast("Preview failed: ${e.message}")
        }
    }

    private fun takeStereo() {
        val cam = cameraDevice ?: run { toast("Camera not ready"); return }
        val s = session ?: run { toast("Session not ready"); return }
        val handler = cameraHandler ?: run { toast("No camera handler"); return }
        val wideId = widePhysicalId ?: run { toast("No wide id"); return }
        val ultraId = ultraPhysicalId ?: run { toast("No ultra id"); return }

        val wide = wideReader ?: run { toast("No wide reader"); return }
        val ultra = ultraReader ?: run { toast("No ultra reader"); return }

        runOnUiThread { captureButton.isEnabled = false }

        synchronized(pairLock) {
            captureArmed = true
            pendingWide = null
            pendingUltra = null
        }

        drain(wide)
        drain(ultra)

        try {
            // Pause preview to reduce pipeline contention for v0.
            s.stopRepeating()
            s.abortCaptures()

            val builder = cam.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE,
                setOf(wideId, ultraId)
            )
            builder.addTarget(wide.surface)
            builder.addTarget(ultra.surface)

            // set JPEG quality
            builder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())

            // lens distortion correction
            builder.set(
                CaptureRequest.DISTORTION_CORRECTION_MODE,
                CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
            )

            // make sure it's rotated correctly
            val deviceRotation = display!!.rotation
            val jpegOrientation = (sensorOrientation - (deviceRotation * 90) + 360) % 360
            builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)

            // autofocus, exposure, and white balance all set to AUTO
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

            s.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    runOnUiThread {
                        captureButton.isEnabled = true
                        toast("Capture failed: ${failure.reason}")
                    }
                    restartPreviewSafely(session)
                }

                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    restartPreviewSafely(session)
                }

                private fun restartPreviewSafely(session: CameraCaptureSession) {
                    try {
                        previewRequest?.let { session.setRepeatingRequest(it, null, handler) }
                    } catch (_: Exception) { }
                }
            }, handler)

        } catch (e: CameraAccessException) {
            Log.e(TAG, "takeStereo CameraAccessException", e)
            runOnUiThread { captureButton.isEnabled = true }
            toast("Capture error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "takeStereo failed", e)
            runOnUiThread { captureButton.isEnabled = true }
            toast("Capture error: ${e.message}")
        }
    }

    private fun drain(reader: ImageReader) {
        try {
            while (true) {
                val img = reader.acquireLatestImage() ?: break
                img.close()
            }
        } catch (_: Exception) { }
    }

    // Replace the entire handleImage function with this simpler version
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
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val base = "Stereo_${stamp}"

        val wideUri = saveJpeg("${base}_WIDE.jpg", wide.bytes)
        val ultraUri = saveJpeg("${base}_ULTRA.jpg", ultra.bytes)

        runOnUiThread {
            captureButton.isEnabled = true
            toast("Δt = ${"%.2f".format(deltaMs)} ms")
        }
    }

    private fun saveJpeg(fileName: String, bytes: ByteArray): Uri? {
        val resolver = contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            // API 29+ scoped storage path:
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + ALBUM_DIR
            )
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        return uri
    }

    private fun closeCamera() {
        captureButton.isEnabled = false

        try { session?.close() } catch (_: Exception) { }
        session = null

        try { cameraDevice?.close() } catch (_: Exception) { }
        cameraDevice = null

        try { wideReader?.close() } catch (_: Exception) { }
        wideReader = null

        try { ultraReader?.close() } catch (_: Exception) { }
        ultraReader = null

        try { previewSurface?.release() } catch (_: Exception) { }
        previewSurface = null

        synchronized(pairLock) {
            captureArmed = false
            pendingWide = null
            pendingUltra = null
        }
    }

    private fun toast(msg: String) {
        runOnUiThread {
            // Cancel any previous message-clearing callbacks
            uiHandler.removeCallbacksAndMessages(null)
            // Set the new message
            statusText.text = msg
            // Post a new callback to clear the message after 5 seconds
            uiHandler.postDelayed({
                // Check if the message is still the one we set before clearing it
                if (statusText.text == msg) {
                    statusText.text = "" // Clear the text
                }
            }, 5000) // 5000 milliseconds = 5 seconds
        }
    }



}
