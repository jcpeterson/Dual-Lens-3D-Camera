package com.example.duallens3dcamera.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.example.duallens3dcamera.R

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = AppSettings.PREFS_NAME
        setPreferencesFromResource(R.xml.settings_preferences, rootKey)

        AppSettings.ensureCameraBasedDefaults(requireContext())

        val sp = preferenceManager.sharedPreferences ?: return

        setupPhotoPrefs()
        setupBitratePref()
        setupVideoProcessingPrefs()
        setupDebugPrefs(sp)

        val caps = AppSettings.loadStereoCaps(requireContext())
        if (caps == null) {
            findPreference<ListPreference>(AppSettings.KEY_VIDEO_RESOLUTION)?.isEnabled = false
            findPreference<ListPreference>(AppSettings.KEY_VIDEO_FPS)?.isEnabled = false
            findPreference<ListPreference>(AppSettings.KEY_PREVIEW_RESOLUTION)?.isEnabled = false
            findPreference<ListPreference>(AppSettings.KEY_PREVIEW_FPS)?.isEnabled = false
            return
        }

        setupVideoPrefs(sp, caps)
        setupPreviewPrefs(sp, caps)
    }

    private fun setupPhotoPrefs() {
        val noise = findPreference<ListPreference>(AppSettings.KEY_PHOTO_NOISE_REDUCTION) ?: return
        val dist = findPreference<ListPreference>(AppSettings.KEY_PHOTO_DISTORTION_CORRECTION) ?: return
        val edge = findPreference<ListPreference>(AppSettings.KEY_PHOTO_EDGE_MODE) ?: return
        val prime = findPreference<ListPreference>(AppSettings.KEY_PHOTO_ULTRAWIDE_PRIME_INTERVAL)

        noise.entries = arrayOf("Off", "Fast", "High quality")
        noise.entryValues = arrayOf("off", "fast", "hq")
        noise.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        dist.entries = arrayOf("High quality", "Fast", "Off")
        dist.entryValues = arrayOf("hq", "fast", "off")
        dist.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        edge.entries = arrayOf("Off", "Fast", "High quality")
        edge.entryValues = arrayOf("off", "fast", "hq")
        edge.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        prime?.entries = arrayOf(
            "Off",
            "Once when app becomes active",
            "Every 1s",
            "Every 2s",
            "Every 3s",
            "Every 4s",
            "Every 5s",
            "Every 10s"
        )
        prime?.entryValues = arrayOf("off", "on_app_open", "1", "2", "3", "4", "5", "10")
        prime?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
    }

    private fun setupBitratePref() {
        val bitrate = findPreference<ListPreference>(AppSettings.KEY_VIDEO_BITRATE_MBPS) ?: return
        val opts = AppSettings.VIDEO_BITRATE_OPTIONS_MBPS
        bitrate.entries = opts.map { "${it} Mbps" }.toTypedArray()
        bitrate.entryValues = opts.map { it.toString() }.toTypedArray()
        bitrate.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
    }

    private fun setupVideoProcessingPrefs() {
        val nr = findPreference<ListPreference>(AppSettings.KEY_VIDEO_NOISE_REDUCTION)
        val dc = findPreference<ListPreference>(AppSettings.KEY_VIDEO_DISTORTION_CORRECTION)
        val edge = findPreference<ListPreference>(AppSettings.KEY_VIDEO_EDGE_MODE)

        val entriesOffFastHq = arrayOf("Off", "Fast", "High quality")
        val valuesOffFastHq = arrayOf("off", "fast", "hq")

        nr?.entries = entriesOffFastHq
        nr?.entryValues = valuesOffFastHq
        nr?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        dc?.entries = arrayOf("Off", "Fast", "High quality")
        dc?.entryValues = arrayOf("off", "fast", "hq")
        dc?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        edge?.entries = entriesOffFastHq
        edge?.entryValues = valuesOffFastHq
        edge?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
    }

    private fun setupDebugPrefs(sp: SharedPreferences) {
        val videoLogEnabled = findPreference<SwitchPreferenceCompat>(AppSettings.KEY_DEBUG_VIDEO_LOG_ENABLED)
        val videoLogFramesOnly = findPreference<SwitchPreferenceCompat>(AppSettings.KEY_DEBUG_VIDEO_LOG_FRAMES_ONLY)

        fun syncFramesOnlyEnabledState() {
            videoLogFramesOnly?.isEnabled = (videoLogEnabled?.isChecked == true)
        }

        syncFramesOnlyEnabledState()
        videoLogEnabled?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as? Boolean ?: false
                videoLogFramesOnly?.isEnabled = enabled
                true
            }

        val dumpPref = findPreference<Preference>(AppSettings.KEY_DEBUG_DUMP_CAMERA_INFO)
        dumpPref?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            val ctx = requireContext().applicationContext
            Toast.makeText(ctx, "Writing device/camera dump…", Toast.LENGTH_SHORT).show()

            Thread {
                try {
                    val result = DebugDump.writeOneTimeDump(ctx)
                    activity?.runOnUiThread {
                        Toast.makeText(
                            ctx,
                            "Wrote dump: ${result.displayName} (Documents)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    activity?.runOnUiThread {
                        Toast.makeText(
                            ctx,
                            "Dump failed: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.start()

            true
        }
    }

    private fun setupVideoPrefs(sp: SharedPreferences, caps: AppSettings.StereoCaps) {
        val resPref = findPreference<ListPreference>(AppSettings.KEY_VIDEO_RESOLUTION) ?: return
        val fpsPref = findPreference<ListPreference>(AppSettings.KEY_VIDEO_FPS) ?: return

        val sizes = AppSettings.commonVideoSizes(caps)
        if (sizes.isEmpty()) {
            resPref.isEnabled = false
            fpsPref.isEnabled = false
            return
        }

        resPref.entries = sizes.map { "${it.width}x${it.height} (${AppSettings.aspectLabel(it)})" }.toTypedArray()
        resPref.entryValues = sizes.map { AppSettings.sizeToString(it) }.toTypedArray()
        resPref.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        val def = AppSettings.chooseDefaultVideoSize(sizes)
        val current = AppSettings.parseSize(sp.getString(AppSettings.KEY_VIDEO_RESOLUTION, null))
            ?.takeIf { s -> sizes.any { it.width == s.width && it.height == s.height } }
            ?: def

        sp.edit().putString(AppSettings.KEY_VIDEO_RESOLUTION, AppSettings.sizeToString(current)).apply()
        resPref.value = AppSettings.sizeToString(current)

        updateVideoFpsOptions(sp, caps, current, fpsPref)

        resPref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            val newSize = AppSettings.parseSize(newValue as String) ?: return@OnPreferenceChangeListener true
            updateVideoFpsOptions(sp, caps, newSize, fpsPref)
            true
        }
    }

    private fun updateVideoFpsOptions(
        sp: SharedPreferences,
        caps: AppSettings.StereoCaps,
        size: Size,
        fpsPref: ListPreference
    ) {
        val allowed = AppSettings.supportedVideoFpsForSize(caps, size)
        val safe = if (allowed.isEmpty()) listOf(30) else allowed

        fpsPref.entries = safe.map { "${it} fps" }.toTypedArray()
        fpsPref.entryValues = safe.map { it.toString() }.toTypedArray()
        fpsPref.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        fpsPref.isEnabled = safe.size > 1

        val current = sp.getString(AppSettings.KEY_VIDEO_FPS, null)?.toIntOrNull()
        val pick = when {
            current != null && safe.contains(current) -> current
            safe.contains(30) -> 30
            else -> safe.maxOrNull() ?: 30
        }
        sp.edit().putString(AppSettings.KEY_VIDEO_FPS, pick.toString()).apply()
        fpsPref.value = pick.toString()
    }

    private fun setupPreviewPrefs(sp: SharedPreferences, caps: AppSettings.StereoCaps) {
        val resPref = findPreference<ListPreference>(AppSettings.KEY_PREVIEW_RESOLUTION) ?: return
        val fpsPref = findPreference<ListPreference>(AppSettings.KEY_PREVIEW_FPS) ?: return

        val sizes = AppSettings.commonPreviewSizes(caps)
        if (sizes.isEmpty()) {
            resPref.isEnabled = false
            fpsPref.isEnabled = false
            return
        }

        resPref.entries = sizes.map { "${it.width}x${it.height} (${AppSettings.aspectLabel(it)})" }.toTypedArray()
        resPref.entryValues = sizes.map { AppSettings.sizeToString(it) }.toTypedArray()
        resPref.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        val def = AppSettings.chooseDefaultPreviewSize(sizes)
        val current = AppSettings.parseSize(sp.getString(AppSettings.KEY_PREVIEW_RESOLUTION, null))
            ?.takeIf { s -> sizes.any { it.width == s.width && it.height == s.height } }
            ?: def

        sp.edit().putString(AppSettings.KEY_PREVIEW_RESOLUTION, AppSettings.sizeToString(current)).apply()
        resPref.value = AppSettings.sizeToString(current)

        updatePreviewFpsOptions(sp, caps, current, fpsPref)

        resPref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            val newSize = AppSettings.parseSize(newValue as String) ?: return@OnPreferenceChangeListener true
            updatePreviewFpsOptions(sp, caps, newSize, fpsPref)
            true
        }
    }

    private fun updatePreviewFpsOptions(
        sp: SharedPreferences,
        caps: AppSettings.StereoCaps,
        size: Size,
        fpsPref: ListPreference
    ) {
        val allowed = AppSettings.supportedPreviewFpsForSize(caps, size)
        val safe = if (allowed.isEmpty()) listOf(30) else allowed

        fpsPref.entries = safe.map { "${it} fps" }.toTypedArray()
        fpsPref.entryValues = safe.map { it.toString() }.toTypedArray()
        fpsPref.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        fpsPref.isEnabled = safe.size > 1

        val current = sp.getString(AppSettings.KEY_PREVIEW_FPS, null)?.toIntOrNull()
        val pick = when {
            current != null && safe.contains(current) -> current
            safe.contains(30) -> 30
            else -> safe.maxOrNull() ?: 30
        }
        sp.edit().putString(AppSettings.KEY_PREVIEW_FPS, pick.toString()).apply()
        fpsPref.value = pick.toString()
    }
}
