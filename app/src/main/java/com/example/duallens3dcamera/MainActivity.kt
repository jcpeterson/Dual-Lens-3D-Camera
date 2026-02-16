package com.example.duallens3dcamera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Looper
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.duallens3dcamera.settings.AppSettings
import com.example.duallens3dcamera.settings.SettingsActivity
import com.example.duallens3dcamera.camera.StereoCameraController
import com.example.duallens3dcamera.databinding.ActivityMainBinding
import com.google.android.material.slider.Slider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity(), StereoCameraController.Callback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: StereoCameraController

    // ---- persisted settings ----
    private val prefs by lazy { getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE) }

    private var eisEnabled: Boolean = false // default OFF
    private var rawMode: Boolean = false // default JPG
    private var torchOn: Boolean = false // not persisted (you didn't ask)
    private var zoom2xEnabled: Boolean = false // default 1x

    // Settings screen (loaded from SharedPreferences)
    private var videoResolution: Size = Size(1440, 1080)
    private var videoFps: Int = 30
    private var videoBitrateBps: Int = 50_000_000

    private var previewResolution: Size = Size(800, 600)
    private var previewFps: Int = 30

    private var photoNoiseReductionMode: Int = CaptureRequest.NOISE_REDUCTION_MODE_OFF
    private var photoDistortionCorrectionMode: Int =
        CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
    private var photoEdgeMode: Int = CaptureRequest.EDGE_MODE_OFF
    private var photoToneMapping: StereoCameraController.ToneMapping =
        StereoCameraController.ToneMapping.AUTO
    private var photoTonemapAeCompSteps: Int = 0
    private var photoStillResolutionMode: StereoCameraController.StillResolutionMode =
        StereoCameraController.StillResolutionMode.LARGEST_COMMON
    // Whether or not to save the two images separately on top of the sbs output
    // Note: Applies in JPG mode only (RAW mode always provides two dng files and not sbs)
    private var saveIndividualLensImages: Boolean = false


    // ---- UI state flags ----
    private var isRecording: Boolean = false
    private var isBusy: Boolean = false

    private var previewConfig: StereoCameraController.PreviewConfig? = null

    // ---- Exposure compensation overlay (viewfinder) ----
    private var aeCompRangeCommon: Range<Int>? = null
    private var aeCompStepEv: Float? = null
    private var aeCompUiInitialized: Boolean = false

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
        updateZoomButton()
        updateTorchButton()
        setupAeCompOverlayUi()
        updateUi()

//        binding.btnVideoRes.setOnClickListener {
//            if (isRecording || isBusy) return@setOnClickListener
//            videoResIndex = (videoResIndex + 1) % videoVerticalOptions.size
//            savePrefs()
//            updateVideoResButton()
//            applyVideoConfigToController()
//        }
//
//        binding.btnBitrate.setOnClickListener {
//            if (isRecording || isBusy) return@setOnClickListener
//            bitrateIndex = (bitrateIndex + 1) % bitrateOptionsMbps.size
//            savePrefs()
//            updateBitrateButton()
//            applyVideoConfigToController()
//        }

        binding.btnSettings.setOnClickListener {
            if (isRecording || isBusy) return@setOnClickListener
            startActivity(Intent(this, SettingsActivity::class.java))
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

        binding.btnZoom.setOnClickListener {
            if (isRecording || isBusy) return@setOnClickListener
            zoom2xEnabled = !zoom2xEnabled
            savePrefs()
            updateZoomButton()
            controller.setZoom2x(zoom2xEnabled)
            requestPreviewTransformReapply() // keep preview aspect sane
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

//    override fun onResume() {
//        super.onResume()
//        if (!hasAllPermissions()) {
//            requestPermissions()
//        } else {
//            maybeStartCamera()
//        }
//    }
    override fun onResume() {
        super.onResume()
        if (!hasAllPermissions()) {
            requestPermissions()
        } else {
            AppSettings.ensureCameraBasedDefaults(this)
            loadPrefs()
            refreshAeCompOverlayCaps()
            syncAeCompOverlayFromState(applyToController = false)
            updateTopBarUi()
            updateZoomButton()
            updateTorchButton()
            updateUi()
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

//        previewConfig = controller.start(
//            surfaceTexture = st,
//            displayRotation = rotation,
//            initialRawMode = rawMode,
//            initialTorchOn = torchOn,
//            initialRequestedRecordSize = currentRequestedRecordSize(),
//            initialVideoBitrateBps = currentVideoBitrateBps(),
//            initialEisEnabled = eisEnabled,
//            initialZoom2x = zoom2xEnabled
//        )

        previewConfig = controller.start(
            StereoCameraController.StartParams(
                surfaceTexture = st,
                displayRotation = rotation,
                rawMode = rawMode,
                torchOn = torchOn,
                zoom2xEnabled = zoom2xEnabled,
                video = StereoCameraController.VideoConfig(
                    requestedRecordSize = currentRequestedRecordSize(),
                    requestedRecordFps = currentVideoFps(),
                    videoBitrateBps = currentVideoBitrateBps(),
                    eisEnabled = eisEnabled
                ),
                preview = StereoCameraController.PreviewSettings(
                    requestedPreviewSize = previewResolution,
                    previewTargetFps = previewFps
                ),
                photo = StereoCameraController.PhotoTuning(
                    noiseReductionMode = photoNoiseReductionMode,
                    distortionCorrectionMode = photoDistortionCorrectionMode,
                    edgeMode = photoEdgeMode,
                    toneMapping = photoToneMapping,
                    toneMapAeCompensationSteps = photoTonemapAeCompSteps,
                    stillResolutionMode = photoStillResolutionMode
                )
            )
        )

        // Now that a preview config exists, re-sync UI (enables the EV overlay if applicable).
        updateUi()

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

//    private fun loadPrefs() {
//        videoResIndex = prefs.getInt("videoResIndex", 0).coerceIn(0, videoVerticalOptions.size - 1)
//        bitrateIndex = prefs.getInt("bitrateIndex", 0).coerceIn(0, bitrateOptionsMbps.size - 1)
//        eisEnabled = prefs.getBoolean("eisEnabled", false)
//        rawMode = prefs.getBoolean("rawMode", false)
//        zoom2xEnabled = prefs.getBoolean("zoom2xEnabled", false)
//    }
//
//    private fun savePrefs() {
//        prefs.edit()
//            .putInt("videoResIndex", videoResIndex)
//            .putInt("bitrateIndex", bitrateIndex)
//            .putBoolean("eisEnabled", eisEnabled)
//            .putBoolean("rawMode", rawMode)
//            .putBoolean("zoom2xEnabled", zoom2xEnabled)
//            .apply()
//    }
//
//    // ---------------- top bar UI ----------------
//
    private fun updateZoomButton() {
        binding.btnZoom.text = if (zoom2xEnabled) "2x" else "1x"
    }
//
//    private fun updateTopBarUi() {
//        updateVideoResButton()
//        updateBitrateButton()
//        updateEisButton()
//        updateFormatButton()
//    }
//
//    private fun updateVideoResButton() {
//        binding.btnVideoRes.text = videoVerticalOptions[videoResIndex].toString()
//    }
//
//    private fun updateBitrateButton() {
//        binding.btnBitrate.text = "${bitrateOptionsMbps[bitrateIndex]}"
//    }
//
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
//
//    private fun currentRequestedRecordSize(): Size {
//        val vertical = videoVerticalOptions[videoResIndex]      // e.g. 1440
//        val horizontal = (vertical * 3) / 4                    // e.g. 1080
//        // Use landscape 4:3 (w > h). Muxer orientationHint rotates to portrait.
//        return Size(vertical, horizontal)                       // e.g. 1440x1080
//    }
//
//    private fun currentVideoBitrateBps(): Int {
//        val mbps = bitrateOptionsMbps[bitrateIndex]
//        return mbps * 1_000_000
//    }
//
//    private fun applyVideoConfigToController() {
//        controller.setVideoConfig(
//            requestedRecordSize = currentRequestedRecordSize(),
//            videoBitrateBps = currentVideoBitrateBps(),
//            eisEnabled = eisEnabled
//        )
//    }

    private fun loadPrefs() {
        eisEnabled = prefs.getBoolean(AppSettings.KEY_EIS_ENABLED, false)
        rawMode = prefs.getBoolean(AppSettings.KEY_RAW_MODE, false)
        zoom2xEnabled = prefs.getBoolean(AppSettings.KEY_ZOOM_2X, false)

        videoResolution = AppSettings.getVideoResolution(this)
        videoFps = AppSettings.getVideoFps(this)
        videoBitrateBps = AppSettings.getVideoBitrateBps(this)

        previewResolution = AppSettings.getPreviewResolution(this)
        previewFps = AppSettings.getPreviewFps(this)

        photoNoiseReductionMode = AppSettings.getPhotoNoiseReductionMode(this)
        photoDistortionCorrectionMode = AppSettings.getPhotoDistortionCorrectionMode(this)
        photoEdgeMode = AppSettings.getPhotoEdgeMode(this)
        photoToneMapping = StereoCameraController.ToneMapping.fromPrefValue(
            AppSettings.getPhotoToneMapping(this)
        )
        photoTonemapAeCompSteps = AppSettings.getPhotoTonemapAeCompSteps(this)
        photoStillResolutionMode = StereoCameraController.StillResolutionMode.fromPrefValue(
            AppSettings.getPhotoStillResolutionMode(this)
        )
        saveIndividualLensImages = AppSettings.getPhotoSaveIndividualImages(this)

        // Settings-only configs that the controller reads later (recording/stills),
        // so they don't need to be passed into controller.start(...).
        controller.setVideoProcessingModes(
            noiseReductionMode = AppSettings.getVideoNoiseReductionMode(this),
            distortionCorrectionMode = AppSettings.getVideoDistortionCorrectionMode(this),
            edgeMode = AppSettings.getVideoEdgeMode(this)
        )

        controller.setDebugConfig(
            enableStereoRecordingLog = AppSettings.getDebugVideoLogEnabled(this),
            stereoLogFramesOnly = AppSettings.getDebugVideoLogFramesOnly(this),
            enablePhotoJsonLog = AppSettings.getDebugPhotoJsonLogEnabled(this),
            enablePhotoSyncToast = AppSettings.getDebugPhotoSyncToastEnabled(this)
        )
        controller.setPhotoSaveIndividualLensImages(saveIndividualLensImages)
        controller.setPrimeUwOnActive(
            AppSettings.getPhotoPrimeUwOnActiveEnabled(this)
        )
    }

    private fun savePrefs() {
        prefs.edit()
            .putBoolean(AppSettings.KEY_EIS_ENABLED, eisEnabled)
            .putBoolean(AppSettings.KEY_RAW_MODE, rawMode)
            .putBoolean(AppSettings.KEY_ZOOM_2X, zoom2xEnabled)
            .apply()
    }

    private fun updateTopBarUi() {
        updateEisButton()
        updateFormatButton()
    }

    private fun currentRequestedRecordSize(): Size = videoResolution
    private fun currentVideoFps(): Int = videoFps
    private fun currentVideoBitrateBps(): Int = videoBitrateBps

    private fun applyVideoConfigToController() {
        controller.setVideoConfig(
            requestedRecordSize = currentRequestedRecordSize(),
            requestedRecordFps = currentVideoFps(),
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
//        setEnabledStyle(binding.btnVideoRes, topEnabled, android.R.color.black, android.R.color.white)
//        setEnabledStyle(binding.btnBitrate, topEnabled, android.R.color.black, android.R.color.white)
//        setEnabledStyle(binding.btnEis, topEnabled, android.R.color.black, android.R.color.white)
//        setEnabledStyle(binding.btnFormat, topEnabled, android.R.color.black, android.R.color.white)
//        setEnabledStyle(binding.btnZoom, topEnabled, android.R.color.black, android.R.color.white)
        setEnabledStyle(binding.btnSettings, topEnabled, android.R.color.black, android.R.color.white)
        setEnabledStyle(binding.btnEis, topEnabled, android.R.color.black, android.R.color.white)
        setEnabledStyle(binding.btnFormat, topEnabled, android.R.color.black, android.R.color.white)
        setEnabledStyle(binding.btnZoom, topEnabled, android.R.color.black, android.R.color.white)

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

        // Exposure compensation overlay (visible only when Tone Mapping != Auto)
        syncAeCompOverlayFromState(applyToController = false)
    }

    // -----------------------------------------------------------------------------------------
    // Exposure compensation overlay (viewfinder)
    // -----------------------------------------------------------------------------------------

    private fun setupAeCompOverlayUi() {
        if (aeCompUiInitialized) return
        aeCompUiInitialized = true

        // Start hidden; we only show it when Tone Mapping != Auto.
        binding.evPanel.visibility = View.GONE

        // Keep the label in sync as the user drags.
        binding.sliderEv.addOnChangeListener { _, value, _ ->
            val steps = value.toInt()
            updateAeCompLabel(steps)
            updateAeCompPlusMinusEnabled(steps)
        }

        // Apply only when the user releases the slider (to avoid spamming repeating requests).
        binding.sliderEv.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                applyAeCompStepsFromUi(slider.value.toInt())
            }
        })

        binding.btnEvMinus.setOnClickListener { stepAeCompBy(-1) }
        binding.btnEvPlus.setOnClickListener { stepAeCompBy(+1) }

        // Initial label
        updateAeCompLabel(photoTonemapAeCompSteps)
        updateAeCompPlusMinusEnabled(photoTonemapAeCompSteps)
    }

    /**
     * Refresh AE compensation range/step from camera characteristics (best-effort).
     *
     * We compute a conservative range by intersecting wide + ultrawide ranges so the same value
     * can be applied to both lenses.
     */
    private fun refreshAeCompOverlayCaps() {
        if (!aeCompUiInitialized) return

        val caps = AppSettings.loadStereoCaps(this) ?: return

        val wideRange = caps.wide1xChars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val ultraRange = caps.ultraChars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        aeCompRangeCommon = intersectRanges(wideRange, ultraRange)

        val step = caps.wide1xChars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        aeCompStepEv = if (step != null && step.denominator != 0) {
            step.numerator.toFloat() / step.denominator.toFloat()
        } else {
            null
        }

        // Apply to the slider if we have a range.
        aeCompRangeCommon?.let { r ->
            binding.sliderEv.valueFrom = r.lower.toFloat()
            binding.sliderEv.valueTo = r.upper.toFloat()
            binding.sliderEv.stepSize = 1f

            // Keep the current value valid for the new range.
            val v = binding.sliderEv.value
            binding.sliderEv.value = v.coerceIn(binding.sliderEv.valueFrom, binding.sliderEv.valueTo)
        }
    }

    private fun intersectRanges(a: Range<Int>?, b: Range<Int>?): Range<Int>? {
        return when {
            a == null && b == null -> null
            a == null -> b
            b == null -> a
            else -> {
                val lo = max(a.lower, b.lower)
                val hi = min(a.upper, b.upper)
                if (lo <= hi) Range(lo, hi) else a
            }
        }
    }

    private fun shouldShowAeCompOverlay(): Boolean {
        return photoToneMapping != StereoCameraController.ToneMapping.AUTO && !isRecording
    }

    /**
     * Sync the viewfinder overlay (visibility, enabled state, label, and slider position)
     * from the current activity state.
     */
    private fun syncAeCompOverlayFromState(applyToController: Boolean) {
        if (!aeCompUiInitialized) return

        val show = shouldShowAeCompOverlay()
        binding.evPanel.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        val r = aeCompRangeCommon
        val clampedSteps = if (r != null) {
            photoTonemapAeCompSteps.coerceIn(r.lower, r.upper)
        } else {
            // Fall back to the slider's current bounds (prevents out-of-range crashes
            // before we have camera characteristics).
            val lo = binding.sliderEv.valueFrom.toInt()
            val hi = binding.sliderEv.valueTo.toInt()
            photoTonemapAeCompSteps.coerceIn(lo, hi)
        }

        if (clampedSteps != photoTonemapAeCompSteps) {
            // Persist the clamped value so the UI never shows something that can't be applied.
            photoTonemapAeCompSteps = clampedSteps
            prefs.edit().putInt(AppSettings.KEY_PHOTO_TONEMAP_AE_COMP, clampedSteps).apply()
            if (applyToController && previewConfig != null) {
                controller.setPhotoTonemapAeCompensationSteps(clampedSteps)
            }
        }

        // Keep slider + label aligned with current value.
        if (binding.sliderEv.value.toInt() != clampedSteps) {
            // Ensure slider stays within bounds even if caps weren't available at app launch.
            binding.sliderEv.value = clampedSteps.toFloat()
        }
        updateAeCompLabel(clampedSteps)

        val controlsEnabled = (!isBusy && !isRecording && previewConfig != null)
        binding.sliderEv.isEnabled = controlsEnabled
        binding.btnEvMinus.isEnabled = controlsEnabled
        binding.btnEvPlus.isEnabled = controlsEnabled
        binding.evPanel.alpha = if (controlsEnabled) 0.85f else 0.45f

        updateAeCompPlusMinusEnabled(clampedSteps)
    }

    private fun updateAeCompLabel(steps: Int) {
        val stepEv = aeCompStepEv
        binding.tvEvLabel.text = if (stepEv != null) {
            val ev = steps.toFloat() * stepEv
            String.format(Locale.US, "Exposure: %+.2f EV", ev)
        } else {
            String.format(Locale.US, "Exposure: %+d", steps)
        }
    }

    private fun updateAeCompPlusMinusEnabled(steps: Int) {
        val r = aeCompRangeCommon
        if (r != null) {
            binding.btnEvMinus.isEnabled = binding.btnEvMinus.isEnabled && (steps > r.lower)
            binding.btnEvPlus.isEnabled = binding.btnEvPlus.isEnabled && (steps < r.upper)
        }
    }

    private fun stepAeCompBy(deltaSteps: Int) {
        if (!shouldShowAeCompOverlay()) return
        if (!binding.sliderEv.isEnabled) return

        val r = aeCompRangeCommon
        val current = binding.sliderEv.value.toInt()
        val next = if (r != null) {
            (current + deltaSteps).coerceIn(r.lower, r.upper)
        } else {
            current + deltaSteps
        }
        if (next == current) return
        binding.sliderEv.value = next.toFloat()
        applyAeCompStepsFromUi(next)
    }

    private fun applyAeCompStepsFromUi(steps: Int) {
        if (!shouldShowAeCompOverlay()) return
        if (!binding.sliderEv.isEnabled) return

        val r = aeCompRangeCommon
        val clamped = if (r != null) steps.coerceIn(r.lower, r.upper) else steps
        if (clamped == photoTonemapAeCompSteps) return

        photoTonemapAeCompSteps = clamped
        prefs.edit().putInt(AppSettings.KEY_PHOTO_TONEMAP_AE_COMP, clamped).apply()
        controller.setPhotoTonemapAeCompensationSteps(clamped)

        // Update label/buttons immediately.
        updateAeCompLabel(clamped)
        updateAeCompPlusMinusEnabled(clamped)
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
