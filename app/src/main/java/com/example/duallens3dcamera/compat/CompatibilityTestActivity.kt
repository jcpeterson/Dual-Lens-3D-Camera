package com.example.duallens3dcamera.compat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * "Compatibility test": runs [CompatibilityTester] and shows a verdict for stereo photos and stereo
 * video, with a shareable text report for testing other phones.
 */
class CompatibilityTestActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var runButton: Button
    private lateinit var shareButton: Button
    private val report = StringBuilder()
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Compatibility test"
        val pad = (16 * resources.displayMetrics.density).toInt()
        output = TextView(this).apply {
            setPadding(pad, pad, pad, pad)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            text = "Checks whether this phone can take stereo photos and videos with two of its rear " +
                "lenses. The camera is used for a few seconds; keep the phone still and pointed at " +
                "something with detail.\n"
        }
        scroll = ScrollView(this).apply { addView(output) }
        runButton = Button(this).apply { text = "Run test"; setOnClickListener { start() } }
        shareButton = Button(this).apply {
            text = "Share report"; isEnabled = false
            setOnClickListener {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Dual Lens 3D Camera compatibility report")
                    putExtra(Intent.EXTRA_TEXT, report.toString())
                }, "Share report"))
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(runButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(shareButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    private fun append(line: String) = runOnUiThread {
        report.append(line).append('\n')
        output.append(line + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun start() {
        if (running) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        running = true
        runButton.isEnabled = false
        shareButton.isEnabled = false
        report.clear()
        output.text = ""
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            val v = CompatibilityTester(applicationContext) { append(it) }.run()
            fun mark(s: CompatibilityTester.Support) = when (s) {
                CompatibilityTester.Support.YES -> "YES"
                CompatibilityTester.Support.YES_WITH_WORKAROUND -> "YES (with workaround)"
                CompatibilityTester.Support.NO -> "NO"
                CompatibilityTester.Support.NOT_TESTED -> "not tested"
            }
            append("==== Result ====")
            append("3D photos:        ${mark(v.photos)}")
            append("3D video:         ${mark(v.video)}")
            v.notes.forEach { append("Note: $it") }
            runOnUiThread {
                running = false
                runButton.isEnabled = true
                runButton.text = "Run again"
                shareButton.isEnabled = true
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }.apply { name = "CompatTestRunner" }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) start()
    }
}
