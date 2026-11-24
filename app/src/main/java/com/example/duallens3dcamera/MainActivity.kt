package com.example.duallens3dcamera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.duallens3dcamera.capture.TimeLapseRunner
import com.example.duallens3dcamera.settings.AppSettings
import com.example.duallens3dcamera.util.ExposureMath
import com.example.duallens3dcamera.util.LevelMath
import com.example.duallens3dcamera.settings.SettingsActivity
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
    private var photoStillResolutionMode: StereoCameraController.StillResolutionMode =
        StereoCameraController.StillResolutionMode.LARGEST_COMMON
    // Whether or not to save the two images separately on top of the sbs output
    // Note: Applies in JPG mode only (RAW mode always provides two dng files and not sbs)
    private var saveIndividualLensImages: Boolean = false


    // ---- UI state flags ----
    private var isRecording: Boolean = false
    private var isBusy: Boolean = false

    private var preview3dMode = StereoCameraController.Preview3dMode.OFF

    // ---- self-timer ----
    private val timerChoices = intArrayOf(0, 3, 5, 10)
    private var selfTimerSec = 0
    private var countdownLeft = 0
    private val countdownActive get() = countdownLeft > 0
    private val uiHandler = Handler(Looper.getMainLooper())
    private var toneGen: ToneGenerator? = null
    private val countdownTick = object : Runnable {
        override fun run() {
            if (!countdownActive) return
            countdownLeft--
            if (countdownLeft <= 0) {
                binding.countdownText.visibility = View.GONE
                beep(ToneGenerator.TONE_PROP_ACK, 200)
                updateUi()
                takeStereoPhoto(timestampForFilename())
            } else {
                showCountdown()
                uiHandler.postDelayed(this, 1000)
            }
        }
    }

    // ---- grid / spirit level ----
    private enum class Guide(val label: String, val grid: Boolean, val level: Boolean) {
        OFF("Grid off", false, false), GRID("Grid 3x3", true, false),
        GRID_LEVEL("Grid+Lvl", true, true), LEVEL("Level", false, true)
    }
    private var guide = Guide.OFF
    private val sensorManager by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    private val gravity = FloatArray(3)
    private var gravityValid = false
    private var sensorRegistered = false
    private val gravityListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            // Light smoothing so the line doesn't jitter; TYPE_GRAVITY is already filtered.
            val a = if (e.sensor.type == Sensor.TYPE_GRAVITY) 0.35f else 0.12f
            for (i in 0..2) gravity[i] = if (gravityValid) gravity[i] + a * (e.values[i] - gravity[i]) else e.values[i]
            gravityValid = true
            val l = LevelMath.fromGravity(gravity[0], gravity[1], gravity[2])
            binding.levelGrid.level = l
            // Turn labels, countdown and status to face the user in portrait or landscape.
            val r = LevelMath.uiRotation(uiRotation, l)
            if (r != uiRotation) applyUiRotation(r)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    // ---- Pro (manual ISO / shutter), null = auto ----
    private var proIso: Int? = null
    private var proShutterNs: Long? = null

    // ---- time-lapse ----
    private var lapse: TimeLapseRunner? = null
    private val lapseRunning get() = lapse?.running == true
    private var lapseBaseName = ""
    private val dimScreen = Runnable { setScreenBrightness(0.05f) }

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

        // The short top-bar buttons need every pixel for their labels (no default side padding).
        listOf(binding.btnEis, binding.btnPreview3d, binding.btnFormat, binding.btnPro).forEach {
            it.setPaddingRelative(0, it.paddingTop, 0, it.paddingBottom)
            it.minWidth = 0
            it.minimumWidth = 0
            it.textSize = 14f
        }

        loadPrefs()
        updateTopBarUi()
        updateZoomButton()
        updateTorchButton()
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

        binding.btnPreview3d.setOnClickListener {
            if (locked()) return@setOnClickListener
            preview3dMode = when (preview3dMode) {
                StereoCameraController.Preview3dMode.OFF -> StereoCameraController.Preview3dMode.SIDE_BY_SIDE
                StereoCameraController.Preview3dMode.SIDE_BY_SIDE -> StereoCameraController.Preview3dMode.ANAGLYPH
                StereoCameraController.Preview3dMode.ANAGLYPH -> StereoCameraController.Preview3dMode.OFF
            }
            prefs.edit().putString(AppSettings.KEY_PREVIEW_3D_MODE, preview3dMode.name).apply()
            controller.setPreview3dMode(preview3dMode)
            updatePreview3dUi()
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
            when {
                countdownActive -> cancelCountdown()
                lapseRunning -> lapse?.cancel()
                isRecording || isBusy -> Unit
                selfTimerSec > 0 -> startCountdown()
                else -> takeStereoPhoto(timestampForFilename())
            }
        }

        binding.btnTimer.setOnClickListener {
            if (locked()) return@setOnClickListener
            val i = timerChoices.indexOf(selfTimerSec).coerceAtLeast(0)
            selfTimerSec = timerChoices[(i + 1) % timerChoices.size]
            prefs.edit().putInt(AppSettings.KEY_SELF_TIMER_SEC, selfTimerSec).apply()
            updateToolButtons()
        }

        binding.btnGrid.setOnClickListener {
            guide = Guide.entries[(guide.ordinal + 1) % Guide.entries.size]
            prefs.edit().putString(AppSettings.KEY_VIEWFINDER_GUIDE, guide.name).apply()
            applyGuide()
        }

        binding.btnLapse.setOnClickListener {
            if (locked()) return@setOnClickListener
            showTimeLapseDialog()
        }

        binding.btnPro.setOnClickListener {
            if (binding.proPanel.visibility == View.VISIBLE) {
                binding.proPanel.visibility = View.GONE
            } else {
                if (!controller.proSupported()) {
                    toastOnUi("Manual ISO / shutter isn't available on this phone's lens pair.")
                    return@setOnClickListener
                }
                buildProPanel()
                binding.proPanel.visibility = View.VISIBLE
            }
        }

        binding.lapseStatus.setOnClickListener {
            // Brighten for a while so the progress can be read, then dim again.
            if (!lapseRunning) return@setOnClickListener
            setScreenBrightness(null)
            uiHandler.removeCallbacks(dimScreen)
            uiHandler.postDelayed(dimScreen, 20_000)
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
                controller.lockFocusForSequence() // only acts in "Lock at start" focus mode
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
            updateTopBarUi()
            updateZoomButton()
            updateTorchButton()
            updateUi()
            applyGuide()
            maybeStartCamera()
        }
    }


    override fun onPause() {
        if (countdownActive) cancelCountdown()
        if (lapseRunning) {
            lapse?.cancel()
            toastOnUi("Time-lapse stopped because the app was left.")
        }
        unregisterGravity()
        controller.stop()
        previewConfig = null
        super.onPause()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        toneGen?.release()
        toneGen = null
        super.onDestroy()
    }

    // ---------------- photo / self-timer ----------------

    private fun takeStereoPhoto(name: String) {
        isBusy = true
        updateUi()
        controller.captureStereoPhoto(name, rawMode)
        requestPreviewTransformReapply() // make sure preview still has the right aspect
    }

    private fun startCountdown() {
        countdownLeft = selfTimerSec
        showCountdown()
        updateUi()
        uiHandler.postDelayed(countdownTick, 1000)
    }

    private fun showCountdown() {
        binding.countdownText.text = countdownLeft.toString()
        binding.countdownText.visibility = View.VISIBLE
        beep(ToneGenerator.TONE_PROP_BEEP, 120)
    }

    private fun cancelCountdown() {
        countdownLeft = 0
        uiHandler.removeCallbacks(countdownTick)
        binding.countdownText.visibility = View.GONE
        updateUi()
    }

    private fun beep(tone: Int, ms: Int) {
        try {
            val tg = toneGen ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).also { toneGen = it }
            tg.startTone(tone, ms)
        } catch (_: Exception) {
            // No tone available (e.g. audio busy): the on-screen countdown still works.
        }
    }

    // ---------------- grid / level ----------------

    private fun applyGuide() {
        binding.levelGrid.showGrid = guide.grid
        binding.levelGrid.showLevel = guide.level
        // The sensor also keeps the countdown digits upright, so it runs whenever the app is open.
        registerGravity()
        updateToolButtons()
    }

    private fun registerGravity() {
        if (sensorRegistered) return
        val s = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (s == null) {
            if (guide.level) toastOnUi("No motion sensor: level not available.")
            return
        }
        gravityValid = false
        sensorRegistered = sensorManager.registerListener(gravityListener, s, SensorManager.SENSOR_DELAY_UI)
    }

    private fun unregisterGravity() {
        if (!sensorRegistered) return
        sensorManager.unregisterListener(gravityListener)
        sensorRegistered = false
    }

    // ---------------- time-lapse ----------------

    private fun showTimeLapseDialog() {
        val correctionOn = AppSettings.getStereoCorrectionEnabled(this)
        val minInterval = TimeLapseRunner.minIntervalSec(correctionOn)
        val pad = (20 * resources.displayMetrics.density).toInt()

        fun numberField(value: Int) = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
            setSelectAllOnFocus(true)
        }
        val intervalField = numberField(prefs.getInt(AppSettings.KEY_LAPSE_INTERVAL_SEC, 30).coerceAtLeast(minInterval))
        val framesField = numberField(prefs.getInt(AppSettings.KEY_LAPSE_FRAMES, 120))
        val summary = TextView(this).apply { setPadding(0, pad / 2, 0, 0) }

        fun refresh() {
            val iv = intervalField.text.toString().toIntOrNull()
            val n = framesField.text.toString().toIntOrNull()
            summary.text = when {
                iv == null || n == null || n < 1 -> "Enter the interval and number of photos."
                iv < minInterval -> "Interval must be at least $minInterval s" +
                    (if (correctionOn) " (3D correction takes about 4 s per photo)." else ".")
                else -> "Runs for about ${TimeLapseRunner.formatDuration(iv.toLong() * (n - 1))}.\n" +
                    "Keep the phone on the charger; the screen stays on (dimmed) and the app " +
                    "must stay open. Tap the progress bar to brighten."
            }
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        }
        intervalField.addTextChangedListener(watcher)
        framesField.addTextChangedListener(watcher)
        refresh()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(context).apply { text = "Interval between photos (seconds)" })
            addView(intervalField)
            addView(TextView(context).apply { text = "Number of photos"; setPadding(0, pad / 2, 0, 0) })
            addView(framesField)
            addView(summary)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Time-lapse")
            .setView(content)
            .setPositiveButton("Start", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val iv = intervalField.text.toString().toIntOrNull()
                val n = framesField.text.toString().toIntOrNull()
                if (iv == null || n == null || n < 1 || iv < minInterval) {
                    refresh()
                    return@setOnClickListener
                }
                prefs.edit()
                    .putInt(AppSettings.KEY_LAPSE_INTERVAL_SEC, iv)
                    .putInt(AppSettings.KEY_LAPSE_FRAMES, n)
                    .apply()
                dialog.dismiss()
                startTimeLapse(iv, n)
            }
        }
        dialog.show()
    }

    private fun startTimeLapse(intervalSec: Int, frames: Int) {
        lapseBaseName = timestampForFilename()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        uiHandler.postDelayed(dimScreen, 20_000)
        binding.lapseStatus.visibility = View.VISIBLE
        positionLapseStatus()
        controller.lockFocusForSequence() // only acts in "Lock at start" focus mode
        lapse = TimeLapseRunner(
            intervalMs = intervalSec * 1000L,
            totalFrames = frames,
            tryCapture = { index ->
                if (isBusy || isRecording) false
                else {
                    takeStereoPhoto(String.format(Locale.US, "%s_TL%04d", lapseBaseName, index))
                    true
                }
            },
            onProgress = { done, total, msToNext ->
                binding.lapseStatus.text = when {
                    msToNext == null -> "Time-lapse: $done / $total"
                    msToNext == 0L && isBusy -> "Time-lapse: $done / $total · capturing…"
                    else -> "Time-lapse: $done / $total · next in " +
                        TimeLapseRunner.formatDuration((msToNext + 999) / 1000) + " · tap STOP to end"
                }
                if (isLandscapeUi) positionLapseStatus()
            },
            onFinished = { done, total, cancelled ->
                controller.unlockFocusForSequence()
                uiHandler.removeCallbacks(dimScreen)
                setScreenBrightness(null)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                binding.lapseStatus.visibility = View.GONE
                lapse = null
                updateUi()
                toastOnUi(
                    (if (cancelled) "Time-lapse stopped: " else "Time-lapse done: ") +
                        "$done of $total photos, saved as ${lapseBaseName}_TL####."
                )
            }
        )
        updateUi()
        lapse?.start()
    }

    /** null = follow the system brightness. */
    private fun setScreenBrightness(value: Float?) {
        val lp = window.attributes
        lp.screenBrightness = value ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    private fun locked() = isBusy || isRecording || countdownActive || lapseRunning

    private fun updateToolButtons() {
        binding.btnTimer.text = if (selfTimerSec == 0) "Timer OFF" else "Timer ${selfTimerSec}s"
        binding.btnGrid.text = guide.label
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
                    stillResolutionMode = photoStillResolutionMode
                )
            )
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
        preview3dMode = try {
            StereoCameraController.Preview3dMode.valueOf(
                prefs.getString(AppSettings.KEY_PREVIEW_3D_MODE, null) ?: "OFF")
        } catch (_: Exception) { StereoCameraController.Preview3dMode.OFF }
        controller.setPreview3dMode(preview3dMode)
        controller.setStereoOutput(
            correction = AppSettings.getStereoCorrectionEnabled(this),
            crossEyed = AppSettings.getStereoCrossEyed(this),
            swapEyes = AppSettings.getStereoSwapEyes(this),
            colourAverage = AppSettings.getStereoColourAverage(this)
        )
        controller.setPrimeUwOnActive(
            AppSettings.getPhotoPrimeUwOnActiveEnabled(this)
        )
        controller.setMainFocusMode(
            StereoCameraController.MainFocusMode.fromPref(prefs.getString(AppSettings.KEY_MAIN_FOCUS_MODE, null))
        )
        proIso = prefs.getInt(AppSettings.KEY_PRO_ISO, 0).takeIf { it > 0 }
        proShutterNs = prefs.getLong(AppSettings.KEY_PRO_SHUTTER_NS, 0L).takeIf { it > 0L }
        controller.setProExposure(proIso, proShutterNs)
        updateProButton()
        selfTimerSec = prefs.getInt(AppSettings.KEY_SELF_TIMER_SEC, 0).takeIf { it in timerChoices } ?: 0
        guide = try {
            Guide.valueOf(prefs.getString(AppSettings.KEY_VIEWFINDER_GUIDE, null) ?: "OFF")
        } catch (_: Exception) { Guide.OFF }
        updateToolButtons()
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
        updatePreview3dUi()
        // In landscape the turned label would not fit the short top bar: show the gear only.
        binding.btnSettings.text = if (isLandscapeUi) "" else "SETTINGS"
    }

    // ---------------- landscape-friendly labels ----------------

    /** 0 = portrait, -90 / 90 = landscape, 180 = upside down; labels are turned by this much. */
    private var uiRotation = 0f
    private val isLandscapeUi get() = uiRotation == 90f || uiRotation == -90f

    // ---------------- Pro panel (manual ISO / shutter) ----------------

    private val proChips = mutableListOf<TextView>()

    /** Two rows of square chips: ISO and shutter. "AUTO" = automatic (or derived, see README). */
    private fun buildProPanel() {
        val panel = binding.proPanel
        panel.removeAllViews()
        proChips.clear()
        val isoRange = controller.commonIsoRange()
        val expRange = controller.commonExposureRangeNs()
        val isoValues = listOf<Int?>(null) + ExposureMath.ISO_CHOICES.filter { isoRange == null || it in isoRange }
        val shutterValues = listOf<Long?>(null) + ExposureMath.SHUTTER_CHOICES_NS.filter { expRange == null || it in expRange }
        panel.addView(proRow("ISO", isoValues, { it?.toString() ?: "AUTO" }, { proIso }) {
            proIso = it; applyPro()
        })
        panel.addView(proRow("SHUTTER", shutterValues, { it?.let(ExposureMath::shutterLabel) ?: "AUTO" }, { proShutterNs }) {
            proShutterNs = it; applyPro()
        })
    }

    private fun <T> proRow(
        title: String,
        values: List<T?>,
        label: (T?) -> String,
        current: () -> T?,
        onPick: (T?) -> Unit
    ): View {
        val d = resources.displayMetrics.density
        val size = (54 * d).toInt()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding((4 * d).toInt(), 0, 0, 0) }
        fun chip(text: String, clickable: Boolean) = TextView(this).apply {
            this.text = text
            gravity = android.view.Gravity.CENTER
            textSize = if (text.length > 5) 11f else 13f
            setTextColor(android.graphics.Color.WHITE)
            rotation = uiRotation
            layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins((2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt()) }
            isClickable = clickable
            proChips += this
        }
        row.addView(chip(title, false).apply {
            setTextColor(android.graphics.Color.rgb(255, 193, 7))
            textSize = 10f
        })
        val scroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        values.forEach { v ->
            val c = chip(label(v), true)
            c.setOnClickListener { onPick(v); refreshProRow(inner, values, current) }
            inner.addView(c)
        }
        scroll.addView(inner)
        row.addView(scroll)
        refreshProRow(inner, values, current)
        // Bring the chosen value into view (e.g. 1/125 is off-screen in a long shutter row).
        scroll.post {
            val idx = values.indexOf(current())
            val c = inner.getChildAt(idx.coerceAtLeast(0)) ?: return@post
            scroll.scrollTo((c.left - scroll.width / 2 + c.width / 2).coerceAtLeast(0), 0)
        }
        return row
    }

    private fun <T> refreshProRow(inner: LinearLayout, values: List<T?>, current: () -> T?) {
        val cur = current()
        for (i in 0 until inner.childCount) {
            val c = inner.getChildAt(i) as TextView
            val selected = values[i] == cur
            c.setBackgroundColor(if (selected) android.graphics.Color.rgb(255, 193, 7) else android.graphics.Color.TRANSPARENT)
            c.setTextColor(if (selected) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }

    private fun applyPro() {
        prefs.edit()
            .putInt(AppSettings.KEY_PRO_ISO, proIso ?: 0)
            .putLong(AppSettings.KEY_PRO_SHUTTER_NS, proShutterNs ?: 0L)
            .apply()
        controller.setProExposure(proIso, proShutterNs)
        updateProButton()
        if (isRecording && proShutterNs != null && proShutterNs!! > 1_000_000_000L / videoFps.coerceAtLeast(1)) {
            toastOnUi("While recording, the shutter can't be slower than 1/$videoFps s.")
        }
    }

    /** PRO turns amber while any manual value is active, so it's never forgotten. */
    private fun updateProButton() {
        val manual = proIso != null || proShutterNs != null
        binding.btnPro.setTextColor(if (manual) android.graphics.Color.rgb(255, 193, 7) else android.graphics.Color.WHITE)
        binding.btnPro.text = if (manual) "PRO•" else "PRO"
    }

    private fun rotatingButtons() = listOf(
        binding.btnSettings, binding.btnEis, binding.btnPreview3d, binding.btnFormat, binding.btnPro, binding.btnZoom,
        binding.btnTorch, binding.btnTimer, binding.btnGrid, binding.btnLapse,
        binding.btnPhoto, binding.btnRecord
    )

    private fun applyUiRotation(r: Float) {
        uiRotation = r
        rotatingButtons().forEach { it.animateContentRotation(r) }
        val cd = binding.countdownText
        cd.animate().rotation(cd.rotation + LevelMath.wrap180(r - cd.rotation)).setDuration(220).start()
        proChips.forEach { c -> c.animate().rotation(c.rotation + LevelMath.wrap180(r - c.rotation)).setDuration(220).start() }
        updateTopBarUi()
        updateUi()
        positionLapseStatus()
    }

    /**
     * The time-lapse bar runs across the top in portrait. In landscape it is turned and moved to
     * the preview edge that is "up" for the user, so it reads like a top bar there too.
     */
    private fun positionLapseStatus() {
        val v = binding.lapseStatus
        val tv = binding.textureView
        v.post {
            v.rotation = uiRotation
            if (!isLandscapeUi) {
                v.translationX = 0f
                v.translationY = 0f
                return@post
            }
            val margin = 8 * resources.displayMetrics.density
            val half = v.height / 2f + margin
            // Labels turned -90 (anticlockwise) have their top towards the screen's left edge.
            val targetCx = if (uiRotation == -90f) tv.left + half else tv.right - half
            v.translationX = targetCx - (v.left + v.width / 2f)
            v.translationY = (tv.top + tv.height / 2f) - (v.top + v.height / 2f)
        }
    }

    private fun updatePreview3dUi() {
        binding.btnPreview3d.text = when (preview3dMode) {
            StereoCameraController.Preview3dMode.OFF -> "2D"
            StereoCameraController.Preview3dMode.SIDE_BY_SIDE -> "3D"
            StereoCameraController.Preview3dMode.ANAGLYPH -> "ANA"
        }
        val show = preview3dMode != StereoCameraController.Preview3dMode.OFF && !isRecording
        binding.preview3d.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) binding.preview3d.setImageDrawable(null)
    }

    override fun onPreview3dFrame(bitmap: android.graphics.Bitmap) = onUi {
        if (binding.preview3d.visibility == View.VISIBLE) binding.preview3d.setImageBitmap(bitmap)
    }

    override fun onPreview3dUnavailable() = onUi {
        preview3dMode = StereoCameraController.Preview3dMode.OFF
        prefs.edit().putString(AppSettings.KEY_PREVIEW_3D_MODE, preview3dMode.name).apply()
        updatePreview3dUi()
        Toast.makeText(this, "Live 3D preview isn't supported on this phone (too many camera streams).", Toast.LENGTH_LONG).show()
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

        val sequence = countdownActive || lapseRunning
        val topEnabled = (!isBusy && !isRecording && !sequence)
//        setEnabledStyle(binding.btnVideoRes, topEnabled, android.R.color.black, android.R.color.white)
//        setEnabledStyle(binding.btnBitrate, topEnabled, android.R.color.black, android.R.color.white)
//        setEnabledStyle(binding.btnEis, topEnabled, android.R.color.black, android.R.color.white)
//        setEnabledStyle(binding.btnFormat, topEnabled, android.R.color.black, android.R.color.white)
//        setEnabledStyle(binding.btnZoom, topEnabled, android.R.color.black, android.R.color.white)
        setEnabledStyle(binding.btnSettings, topEnabled, android.R.color.black, android.R.color.white)
        setEnabledStyle(binding.btnEis, topEnabled, android.R.color.black, android.R.color.white)
        setEnabledStyle(binding.btnFormat, topEnabled, android.R.color.black, android.R.color.white)
        setEnabledStyle(binding.btnZoom, topEnabled, android.R.color.black, android.R.color.white)
        setEnabledStyle(binding.btnPreview3d, topEnabled, android.R.color.black, android.R.color.white)

        // Re-apply EIS strike state after enable/disable styling.
        updateEisButton()

        // Torch stays usable during a time-lapse (e.g. to light a subject at night).
        val torchEnabled = (!isBusy && !isRecording && !countdownActive)
        binding.btnTorch.isEnabled = torchEnabled
        if (!torchEnabled) {
            binding.btnTorch.backgroundTintList = gray
            binding.btnTorch.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        } else {
            updateTorchButton()
        }
        setEnabledStyle(binding.btnTimer, topEnabled, android.R.color.black, android.R.color.white)
        setEnabledStyle(binding.btnLapse, topEnabled, android.R.color.black, android.R.color.white)

        // PHOTO doubles as CANCEL (self-timer) and STOP (time-lapse).
        binding.btnPhoto.text = when {
            countdownActive -> "CANCEL"
            lapseRunning -> if (isLandscapeUi) "STOP" else "STOP TIME-LAPSE"
            else -> "PHOTO"
        }
        binding.btnPhoto.setIconResource(if (sequence) R.drawable.ic_stop else R.drawable.ic_photo_camera)
        binding.btnRecord.setIconResource(if (isRecording) R.drawable.ic_stop else R.drawable.ic_videocam)
        setEnabledStyle(binding.btnPhoto, enabled = sequence || (!isBusy && !isRecording), tint = android.R.color.holo_green_dark, textColor = android.R.color.white)

        val recordEnabled = !isBusy && !sequence
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
        updatePreview3dUi()
        requestPreviewTransformReapply() // make sure preview still has the right aspect
    }

    override fun onRecordingStopped() = onUi {
        isBusy = false
        isRecording = false
        controller.unlockFocusForSequence()
        updateUi()
        updatePreview3dUi()
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
