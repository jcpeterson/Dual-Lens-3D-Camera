package com.example.duallens3dcamera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
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
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "StereoInstantV0"
        private const val REQ_CAMERA = 1001

        // v0 stability > max quality. If you want bigger later, raise these.
        private const val MAX_STILL_WIDTH = 1600
        private const val MAX_STILL_HEIGHT = 1600

        private const val JPEG_QUALITY = 95
        private const val ALBUM_DIR = "StereoInstantV0"
    }

    private lateinit var viewFinderLeft: TextureView
    private lateinit var viewFinderRight: TextureView
    private lateinit var captureButton: Button
    private lateinit var statusText: TextView

    private val cameraManager by lazy {
        getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
    }

    private var logicalCameraId: String? = null
    private var widePhysicalId: String? = null
    private var ultraPhysicalId: String? = null

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    private var previewSurfaceLeft: Surface? = null
    private var previewSurfaceRight: Surface? = null
    private var previewRequest: CaptureRequest? = null

    private var wideReader: ImageReader? = null
    private var ultraReader: ImageReader? = null
    private var stillSize: Size? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null

    private val sessionExecutor = Executors.newSingleThreadExecutor()

    private data class JpegResult(val bytes: ByteArray, val timestampNs: Long) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as JpegResult
            if (!bytes.contentEquals(other.bytes)) return false
            if (timestampNs != other.timestampNs) return false
            return true
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + timestampNs.hashCode()
            return result
        }
    }

    private val pairLock = Any()
    private var captureArmed = false
    private var pendingWide: JpegResult? = null
    private var pendingUltra: JpegResult? = null

    private var surfacesInitialized = false

    private val textureListenerLeft = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { checkAndStart() }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private val textureListenerRight = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { checkAndStart() }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun checkAndStart() {
        if (viewFinderLeft.isAvailable && viewFinderRight.isAvailable) {
            if (!surfacesInitialized) {
                surfacesInitialized = true
                maybeStartCamera()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinderLeft = findViewById(R.id.viewFinder_left)
        viewFinderRight = findViewById(R.id.viewFinder_right)
        captureButton = findViewById(R.id.captureButton)
        statusText = findViewById(R.id.statusText)

        captureButton.setOnClickListener { takeStereo() }
        captureButton.isEnabled = false

        statusText.text = "Waiting for camera permission…"
    }

    override fun onResume() {
        super.onResume()
        startThreads()
        surfacesInitialized = false
        viewFinderLeft.surfaceTextureListener = textureListenerLeft
        viewFinderRight.surfaceTextureListener = textureListenerRight
        checkAndStart()
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                toast("Camera permission is required to use this app.")
                finish()
            }
        }
    }

    private data class SelectedCameras(
        val logicalId: String,
        val widePhysicalId: String,
        val ultraPhysicalId: String
    )

    private data class PhysicalInfo(val id: String, val minFocalMm: Float)

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun startCamera() {
        if (cameraDevice != null) return
        if (!viewFinderLeft.isAvailable || !viewFinderRight.isAvailable) return

        try {
            val selected = selectRearLogicalWideUltra()
            logicalCameraId = selected.logicalId
            widePhysicalId = selected.widePhysicalId
            ultraPhysicalId = selected.ultraPhysicalId

            val size = chooseCommonYuvSize(selected.widePhysicalId, selected.ultraPhysicalId)
            stillSize = size

            setupImageReaders(size)

            statusText.text = buildString {
                appendLine("Logical camera: ${selected.logicalId}")
                appendLine("Wide physical (left): ${selected.widePhysicalId}")
                appendLine("Ultra physical (right): ${selected.ultraPhysicalId}")
                appendLine("Capture size: ${size.width}x${size.height} (YUV → JPEG)")
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
            statusText.text = "Error: ${e.message}"
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

            if (phys.size >= 2) {
                val ultra = phys[0].id
                val wide = phys[1].id
                return SelectedCameras(logicalId = id, widePhysicalId = wide, ultraPhysicalId = ultra)
            }
        }

        throw IllegalStateException("No rear logical multi-camera found (Pixel 7 should support it).")
    }

    private fun chooseCommonYuvSize(wideId: String, ultraId: String): Size {
        fun yuvSizes(cameraId: String): List<Size> {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
            return map.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: emptyList()
        }

        val wideSizes = yuvSizes(wideId)
        val ultraSizes = yuvSizes(ultraId)

        val ultraSet = ultraSizes.map { it.width to it.height }.toSet()
        val common = wideSizes.filter { ultraSet.contains(it.width to it.height) }

        if (common.isEmpty()) {
            return Size(1280, 720)
        }

        val capped = common.filter { it.width <= MAX_STILL_WIDTH && it.height <= MAX_STILL_HEIGHT }
        val pickFrom = capped.ifEmpty { common }

        return pickFrom.maxByOrNull { it.width.toLong() * it.height.toLong() }!!
    }

    private fun setupImageReaders(size: Size) {
        wideReader?.close()
        ultraReader?.close()

        wideReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        ultraReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)

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

        val surfaceTextureLeft = viewFinderLeft.surfaceTexture!!
        surfaceTextureLeft.setDefaultBufferSize(size.width, size.height)
        previewSurfaceLeft = Surface(surfaceTextureLeft)

        val surfaceTextureRight = viewFinderRight.surfaceTexture!!
        surfaceTextureRight.setDefaultBufferSize(size.width, size.height)
        previewSurfaceRight = Surface(surfaceTextureRight)

        val previewConfigLeft = OutputConfiguration(previewSurfaceLeft!!).apply { setPhysicalCameraId(wideId) }
        val previewConfigRight = OutputConfiguration(previewSurfaceRight!!).apply { setPhysicalCameraId(ultraId) }
        val wideConfig = OutputConfiguration(wideReader!!.surface).apply { setPhysicalCameraId(wideId) }
        val ultraConfig = OutputConfiguration(ultraReader!!.surface).apply { setPhysicalCameraId(ultraId) }

        val outputs = listOf(previewConfigLeft, previewConfigRight, wideConfig, ultraConfig)

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
                        toast("Ready.")
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
        val ultraId = ultraPhysicalId ?: return
        val handler = cameraHandler ?: return

        try {
            val builder = cam.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW,
                setOf(wideId, ultraId)
            )
            builder.addTarget(previewSurfaceLeft!!)
            builder.addTarget(previewSurfaceRight!!)

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
            s.stopRepeating()
            s.abortCaptures()

            val builder = cam.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE,
                setOf(wideId, ultraId)
            )
            builder.addTarget(wide.surface)
            builder.addTarget(ultra.surface)

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

    private fun handleImage(isWide: Boolean, image: Image) {
        val jpegBytes = try {
            yuv420ToJpeg(image)
        } catch (e: Exception) {
            Log.e(TAG, "YUV->JPEG failed", e)
            image.close()
            return
        }
        val ts = image.timestamp
        image.close()

        var toSaveWide: JpegResult? = null
        var toSaveUltra: JpegResult? = null

        synchronized(pairLock) {
            if (!captureArmed) return

            if (isWide) {
                pendingWide = JpegResult(jpegBytes, ts)
            } else {
                pendingUltra = JpegResult(jpegBytes, ts)
            }

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

    private fun yuv420ToJpeg(image: Image): ByteArray {
        val nv21 = yuv420ToNv21(image)
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        return ByteArrayOutputStream().use { out ->
            yuv.compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY, out)
            out.toByteArray()
        }
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val out = ByteArray(ySize + (width * height / 2))

        val yPlane = image.planes[0].buffer
        val uPlane = image.planes[1].buffer
        val vPlane = image.planes[2].buffer

        yPlane.get(out, 0, ySize)

        val uPixelStride = image.planes[1].pixelStride
        val vPixelStride = image.planes[2].pixelStride

        if (uPixelStride == 2 && vPixelStride == 2 && image.planes[1].rowStride == image.planes[2].rowStride) {
            vPlane.get(out, ySize, vPlane.remaining().coerceAtMost(out.size - ySize))
            if (uPlane.hasArray() && uPlane.arrayOffset() == 0 && uPlane.array().size == out.size) {
                 System.arraycopy(uPlane.array(), 1, out, ySize + 1, uPlane.limit() - 1)
            }
        } else {
            var outPos = ySize
            for (row in 0 until height / 2) {
                for (col in 0 until width / 2) {
                    val vPos = row * image.planes[2].rowStride + col * vPixelStride
                    val uPos = row * image.planes[1].rowStride + col * uPixelStride
                    out[outPos++] = vPlane[vPos]
                    out[outPos++] = uPlane[uPos]
                }
            }
        }
        return out
    }

    private fun savePair(wide: JpegResult, ultra: JpegResult) {
        val deltaMs = abs(wide.timestampNs - ultra.timestampNs) / 1_000_000.0
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val base = "Stereo_${stamp}"

        val wideUri = saveJpeg("${base}_WIDE.jpg", wide.bytes)
        val ultraUri = saveJpeg("${base}_ULTRA.jpg", ultra.bytes)

        runOnUiThread {
            captureButton.isEnabled = true
            statusText.text = buildString {
                appendLine("Saved:")
                appendLine("  ${wideUri ?: "wide failed"}")
                appendLine("  ${ultraUri ?: "ultra failed"}")
                appendLine("Δt = ${"%.2f".format(deltaMs)} ms")
            }
            toast("Saved stereo pair (Δt ${"%.2f".format(deltaMs)} ms)")
        }
    }

    private fun saveJpeg(fileName: String, bytes: ByteArray): Uri? {
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + ALBUM_DIR
            )
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            resolver.openOutputStream(it)?.use { stream ->
                stream.write(bytes)
            }
        }
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

        try {
            previewSurfaceLeft?.release()
            previewSurfaceRight?.release()
        } catch (_: Exception) { }
        previewSurfaceLeft = null
        previewSurfaceRight = null

        synchronized(pairLock) {
            captureArmed = false
            pendingWide = null
            pendingUltra = null
        }
    }

    private fun toast(msg: String) {
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

}
