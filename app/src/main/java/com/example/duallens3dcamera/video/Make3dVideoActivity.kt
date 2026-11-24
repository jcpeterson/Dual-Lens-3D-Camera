package com.example.duallens3dcamera.video

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.DateUtils
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.duallens3dcamera.camera.LensDiscovery
import com.example.duallens3dcamera.media.MediaStoreUtils
import com.example.duallens3dcamera.settings.AppSettings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lists the app's recordings (pairs of *_wide.mp4 / *_ultrawide.mp4) and turns a chosen pair into
 * one corrected side-by-side 3D video (Pictures/StereoCapture/3d/<stem>_3d.mp4).
 */
class Make3dVideoActivity : AppCompatActivity() {

    private data class Pair3d(val stem: String, val wide: Uri, val ultra: Uri, val durationMs: Long, val takenMs: Long) {
        override fun toString(): String {
            val secs = (durationMs + 500) / 1000
            return "$stem   (${secs}s)"
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var list: ListView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Make 3D video"
        val pad = (16 * resources.displayMetrics.density).toInt()
        empty = TextView(this).apply {
            setPadding(pad, pad, pad, pad)
            text = "Pick a recording. The two lenses are aligned, colour-matched and combined into one " +
                "side-by-side 3D video in Pictures/StereoCapture/3d. Layout and eye order follow the " +
                "\"3D output\" settings."
        }
        list = ListView(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(empty)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        list.setOnItemClickListener { parent, _, pos, _ ->
            confirm(parent.getItemAtPosition(pos) as Pair3d)
        }
    }

    override fun onResume() {
        super.onResume()
        val pairs = loadPairs()
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, pairs)
        if (pairs.isEmpty()) empty.append("\n\nNo recordings found yet.")
    }

    private fun loadPairs(): List<Pair3d> {
        val wide = HashMap<String, Triple<Uri, Long, Long>>()
        val ultra = HashMap<String, Uri>()
        val cols = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATE_TAKEN
        )
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols,
            "${MediaStore.Video.Media.RELATIVE_PATH} = ?", arrayOf(MediaStoreUtils.VIDEOS_RELATIVE_PATH),
            "${MediaStore.Video.Media.DATE_TAKEN} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                val name = c.getString(1) ?: continue
                when {
                    name.endsWith("_wide.mp4") -> wide[name.removeSuffix("_wide.mp4")] = Triple(uri, c.getLong(2), c.getLong(3))
                    name.endsWith("_ultrawide.mp4") -> ultra[name.removeSuffix("_ultrawide.mp4")] = uri
                }
            }
        }
        return wide.mapNotNull { (stem, w) -> ultra[stem]?.let { Pair3d(stem, w.first, it, w.second, w.third) } }
            .sortedByDescending { it.takenMs }
    }

    private fun confirm(p: Pair3d) {
        val options = arrayOf("Full side-by-side (best quality)", "Half side-by-side (3D TV / smaller file)")
        var choice = 0
        AlertDialog.Builder(this)
            .setTitle(p.stem)
            .setSingleChoiceItems(options, 0) { _, which -> choice = which }
            .setPositiveButton("Make 3D video") { _, _ -> process(p, half = choice == 1) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun wideIsLeft(ctx: Context): Boolean {
        // Same default as photos: verified main = left eye on phones with a hidden logical camera
        // (Redmi Note 12 Pro 5G); other phones keep the original ultrawide-left layout.
        val rig = try {
            LensDiscovery.discoverStereoRig(ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager)
        } catch (_: Exception) { null }
        val byDefault = rig?.logicalIsHidden ?: false
        return byDefault != AppSettings.getStereoSwapEyes(ctx)
    }

    private fun process(p: Pair3d, half: Boolean) {
        val cancelled = AtomicBoolean(false)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val status = TextView(this).apply { text = "Starting..." }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Making 3D video")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0)
                addView(status); addView(bar)
            })
            .setCancelable(false)
            .setNegativeButton("Stop") { _, _ -> cancelled.set(true) }
            .show()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val app = applicationContext
        val crossEyed = AppSettings.getStereoCrossEyed(app)
        val wideLeft = wideIsLeft(app)
        Thread {
            val name = "${p.stem}_3d${if (crossEyed) "_cross" else ""}${if (half) "_half" else ""}.mp4"
            var out: Uri? = null
            val message = try {
                out = MediaStoreUtils.createPending3dVideo(app, name, p.takenMs)
                val r = StereoVideoComposer(
                    app, p.wide, p.ultra, out, wideLeft, crossEyed, half, AppSettings.getStereoColourAverage(app),
                    onProgress = { stage, f ->
                        main.post { status.text = "$stage...  ${(f * 100).toInt()}%"; bar.progress = (f * 1000).toInt() }
                    },
                    isCancelled = { cancelled.get() }
                ).run()
                MediaStoreUtils.finalizePending(app.contentResolver, out)
                "Saved $name\n${r.width}x${r.height}${if (r.halfWidth) " (half SBS)" else ""}, ${r.frames} frames, " +
                    "${DateUtils.formatElapsedTime(r.millis / 1000)}\nscale ${"%.3f".format(r.scale)}, " +
                    "rotation ${"%.2f".format(r.rotationDeg)} deg, ${r.inliers} matched points"
            } catch (e: StereoVideoComposer.CancelledException) {
                out?.let { MediaStoreUtils.delete(app.contentResolver, it) }; out = null
                "Stopped."
            } catch (e: Throwable) {
                out?.let { MediaStoreUtils.delete(app.contentResolver, it) }; out = null
                "Could not make the 3D video: ${e.message ?: e.javaClass.simpleName}"
            }
            val done = out
            main.post {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                dialog.dismiss()
                if (isFinishing) return@post
                val b = AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null)
                if (done != null) b.setNeutralButton("Play") { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(done, "video/mp4")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } catch (_: Exception) {
                        Toast.makeText(this, "No video player found", Toast.LENGTH_SHORT).show()
                    }
                }
                b.show()
            }
        }.apply { name = "Make3dVideo" }.start()
    }
}
