package com.example.duallens3dcamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.duallens3dcamera.camera.StereoCameraController
import com.example.duallens3dcamera.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity(), StereoCameraController.Callback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: StereoCameraController

    // ---- persisted settings ----
    private val prefs by lazy { getSharedPreferences("stereo_capture_prefs", Context.MODE_PRIVATE) }

    // Video resolution choices shown as vertical number only.
    // Internally mapped to 4:3 landscape sizes (w=vertical, h=vertical*3/4),
    // then rotated via muxer orientation hint to be upright portrait.
    private val videoVerticalOptions = listOf(1440, 1920, 2560, 4000) // shown on button
    private var videoResIndex: Int = 0 // default 1440 => 1080x1440 upright

    private val bitrateOptionsMbps = listOf(50, 100, 200)
    private var bitrateIndex: Int = 0 // default 50

    private var eisEnabled: Boolean = false // default OFF
    private var rawMode: Boolean = false // default JPG
    private var torchOn: Boolean = false // not persisted (you didn't ask)

    // ---- UI state flags ----
    private var isRecording: Boolean = false
    private var isBusy: Boolean = false

    private var previewConfig: StereoCameraController.PreviewConfig? = null

    // for ensuring the preview always has the right aspect
    @Volatile private var reapplyTransformOnNextFrame = false
    private fun requestPreviewTransformReapply() {
        reapplyTransformOnNextFrame = true
    }


    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraOk = grants[Manifest.permission.CAMERA] == true
        val audioOk = grants[Manifest.permission.RECORD_AUDIO] == true
        if (cameraOk && audioOk) {
            maybeStartCamera()
        } else {
            toastOnUi("CAMERA + RECORD_AUDIO permissions are required.")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        controller = StereoCameraController(this, this)

        loadPrefs()
        updateTopBarUi()
        updateTorchButton()
        updateUi()

        binding.btnVideoRes.setOnClickListener {
            if (isRecording || isBusy) return@setOnClickListener
            videoResIndex = (videoResIndex + 1) % videoVerticalOptions.size
            savePrefs()
            updateVideoResButton()
            applyVideoConfigToController()
        }

        binding.btnBitrate.setOnClickListener {
            if (isRecording || isBusy) return@setOnClickListener
            bitrateIndex = (bitrateIndex + 1) % bitrateOptionsMbps.size
            savePrefs()
            updateBitrateButton()
            applyVideoConfigToController()
        }

        binding.btnEis.setOnClickListener {
            if (isRecording || isBusy) return@setOnClickListener
            eisEnabled = !eisEnabled
            savePrefs()
            updateEisButton()
            applyVideoConfigToController()
        }

        binding.btnFormat.setOnClickListener {
            if (isRecording || isBusy) return@setOnClickListener
            rawMode = !rawMode
            savePrefs()
            updateFormatButton()
            controller.setPhotoOutputRaw(rawMode)
            requestPreviewTransformReapply() // make sure preview still has the right aspect
        }

        binding.btnTorch.setOnClickListener {
            if (isRecording || isBusy) return@setOnClickListener
            torchOn = !torchOn
            updateTorchButton()
            controller.setTorch(torchOn)
        }

        binding.btnPhoto.setOnClickListener {
            if (isRecording || isBusy) return@setOnClickListener
            isBusy = true
            updateUi()
            val ts = timestampForFilename()
            controller.captureStereoPhoto(ts, rawMode)
            requestPreviewTransformReapply() // make sure preview still has the right aspect
        }

        binding.btnRecord.setOnClickListener {
            if (isBusy) return@setOnClickListener

            if (!isRecording) {
                isRecording = true
                isBusy = true
                updateUi()

                // Ensure controller has current config (in case user changed it before camera started).
                applyVideoConfigToController()

                val ts = timestampForFilename()
                controller.startRecording(ts)
            } else {
                isBusy = true
                updateUi()
                controller.stopRecording()
            }
        }

        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                maybeStartCamera()
                configureTransform(width, height)
            }

            override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                configureTransform(width, height)
            }

            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                controller.stop()
                previewConfig = null
                return true
            }

//            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {
                if (reapplyTransformOnNextFrame) {
                    reapplyTransformOnNextFrame = false
                    configureTransform(binding.textureView.width, binding.textureView.height)
                }
            }

        }
    }

    override fun onResume() {
        super.onResume()
        if (!hasAllPermissions()) {
            requestPermissions()
        } else {
            maybeStartCamera()
        }
    }

    override fun onPause() {
        controller.stop()
        previewConfig = null
        super.onPause()
    }

    private fun maybeStartCamera() {
        if (!hasAllPermissions()) return
        if (!binding.textureView.isAvailable) return
        if (previewConfig != null) return

        val st = binding.textureView.surfaceTexture ?: return
        val rotation = display?.rotation ?: Surface.ROTATION_0

        previewConfig = controller.start(
            surfaceTexture = st,
            displayRotation = rotation,
            initialRawMode = rawMode,
            initialTorchOn = torchOn,
            initialRequestedRecordSize = currentRequestedRecordSize(),
            initialVideoBitrateBps = currentVideoBitrateBps(),
            initialEisEnabled = eisEnabled
        )

        binding.textureView.post {
            configureTransform(binding.textureView.width, binding.textureView.height)
        }
    }

    private fun hasAllPermissions(): Boolean {
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val aud = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return cam && aud
    }

    private fun requestPermissions() {
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    // ---------------- prefs ----------------

    private fun loadPrefs() {
        videoResIndex = prefs.getInt("videoResIndex", 0).coerceIn(0, videoVerticalOptions.size - 1)
        bitrateIndex = prefs.getInt("bitrateIndex", 0).coerceIn(0, bitrateOptionsMbps.size - 1)
        eisEnabled = prefs.getBoolean("eisEnabled", false)
        rawMode = prefs.getBoolean("rawMode", false)
    }

    private fun savePrefs() {
        prefs.edit()
            .putInt("videoResIndex", videoResIndex)
            .putInt("bitrateIndex", bitrateIndex)
            .putBoolean("eisEnabled", eisEnabled)
            .putBoolean("rawMode", rawMode)
            .apply()
    }

    // ---------------- top bar UI ----------------

    private fun updateTopBarUi() {
        updateVideoResButton()
        updateBitrateButton()
        updateEisButton()
        updateFormatButton()
    }

    private fun updateVideoResButton() {
        binding.btnVideoRes.text = videoVerticalOptions[videoResIndex].toString()
    }

    private fun updateBitrateButton() {
        binding.btnBitrate.text = "${bitrateOptionsMbps[bitrateIndex]}"
    }

    private fun updateEisButton() {
        binding.btnEis.text = "EIS"
        val base = binding.btnEis.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        binding.btnEis.paintFlags = if (eisEnabled) base else (base or Paint.STRIKE_THRU_TEXT_FLAG)
    }

    private fun updateFormatButton() {
        binding.btnFormat.text = if (rawMode) "RAW" else "JPG"
    }

    private fun updateTorchButton() {
        if (torchOn) {
            binding.btnTorch.text = "Torch ON"
            binding.btnTorch.setTextColor(ContextCompat.getColor(this, android.R.color.black))
            binding.btnTorch.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.white)
        } else {
            binding.btnTorch.text = "Torch OFF"
            binding.btnTorch.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            binding.btnTorch.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.black)
        }
    }

    private fun currentRequestedRecordSize(): Size {
        val vertical = videoVerticalOptions[videoResIndex]      // e.g. 1440
        val horizontal = (vertical * 3) / 4                    // e.g. 1080
        // Use landscape 4:3 (w > h). Muxer orientationHint rotates to portrait.
        return Size(vertical, horizontal)                       // e.g. 1440x1080
    }

    private fun currentVideoBitrateBps(): Int {
        val mbps = bitrateOptionsMbps[bitrateIndex]
        return mbps * 1_000_000
    }

    private fun applyVideoConfigToController() {
        controller.setVideoConfig(
            requestedRecordSize = currentRequestedRecordSize(),
            videoBitrateBps = currentVideoBitrateBps(),
            eisEnabled = eisEnabled
        )
    }

    // ---------------- main buttons enable/disable ----------------

    private fun updateUi() {
        val gray = ContextCompat.getColorStateList(this, android.R.color.darker_gray)

        fun setEnabledStyle(
            button: com.google.android.material.button.MaterialButton,
            enabled: Boolean,
            tint: Int,
            textColor: Int
        ) {
            button.isEnabled = enabled
            if (enabled) {
                button.backgroundTintList = ContextCompat.getColorStateList(this, tint)
                button.setTextColor(ContextCompat.getColor(this, textColor))
            } else {
                button.backgroundTintList = gray
                button.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            }
        }

        val topEnabled = (!isBusy && !isRecording)
        setEnabledStyle(binding.btnVideoRes, topEnabled, android.R.color.black, android.R.color.white)
        setEnabledStyle(binding.btnBitrate, topEnabled, android.R.color.black, android.R.color.white)
        setEnabledStyle(binding.btnEis, topEnabled, android.R.color.black, android.R.color.white)
        setEnabledStyle(binding.btnFormat, topEnabled, android.R.color.black, android.R.color.white)

        // Re-apply EIS strike state after enable/disable styling.
        updateEisButton()

        val torchEnabled = (!isBusy && !isRecording)
        binding.btnTorch.isEnabled = torchEnabled
        if (!torchEnabled) {
            binding.btnTorch.backgroundTintList = gray
            binding.btnTorch.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        } else {
            updateTorchButton()
        }

        setEnabledStyle(binding.btnPhoto, enabled = (!isBusy && !isRecording), tint = android.R.color.holo_green_dark, textColor = android.R.color.white)

        val recordEnabled = !isBusy
        binding.btnRecord.isEnabled = recordEnabled
        binding.btnRecord.text = if (isRecording) "STOP" else "RECORD"
        if (recordEnabled) {
            binding.btnRecord.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
            binding.btnRecord.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        } else {
            binding.btnRecord.backgroundTintList = gray
            binding.btnRecord.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        }
    }

    private fun timestampForFilename(): String {
        // ms needed to avoid collisions when shooting multiple times in the same second.
        val sdf = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
        return sdf.format(Date())
    }


    private fun toastOnUi(msg: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    private inline fun onUi(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else runOnUiThread { block() }
    }

    // ---------------- StereoCameraController.Callback (force UI thread) ----------------

    override fun onStatus(msg: String) = onUi { toastOnUi(msg) }

    override fun onError(msg: String) = onUi {
        toastOnUi(msg)
        isRecording = false
        isBusy = false
        updateUi()
    }

    override fun onFallbackSizeUsed(msg: String) = onUi { toastOnUi(msg) }

    override fun onRecordingStarted() = onUi {
        isBusy = false
        isRecording = true
        updateUi()
        requestPreviewTransformReapply() // make sure preview still has the right aspect
    }

    override fun onRecordingStopped() = onUi {
        isBusy = false
        isRecording = false
        updateUi()
    }

    override fun onPhotoCaptureDone() = onUi {
        isBusy = false
        updateUi()
    }

    // ---------------- Preview transform (NO rotation; just aspect-preserving scale) ----------------
    // This is the version that fixed your sideways preview.
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val cfg = previewConfig ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return

        val previewSize = cfg.previewSize

        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        // Treat buffer as swapped for portrait presentation of a 4:3 buffer.
        val bufferRect = RectF(
            0f, 0f,
            previewSize.height.toFloat(),
            previewSize.width.toFloat()
        )
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())

        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)

        val scale = max(
            viewWidth.toFloat() / previewSize.height.toFloat(),
            viewHeight.toFloat() / previewSize.width.toFloat()
        )
        matrix.postScale(scale, scale, centerX, centerY)

        binding.textureView.setTransform(matrix)
    }
}
