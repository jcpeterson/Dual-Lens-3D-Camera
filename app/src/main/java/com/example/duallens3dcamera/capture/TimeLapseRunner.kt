package com.example.duallens3dcamera.capture

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Takes [totalFrames] stereo photos, one every [intervalMs], on the main thread.
 *
 * Frame i is planned at start + i * interval, so timing does not drift. If the camera is still busy
 * with the previous photo when a frame is due, that frame waits (polling every 250 ms) and the
 * following frames keep their original schedule.
 */
class TimeLapseRunner(
    private val intervalMs: Long,
    private val totalFrames: Int,
    /** Start one capture for frame [index] (1-based). Return false if the camera is busy right now. */
    private val tryCapture: (index: Int) -> Boolean,
    /** Called about once a second: frames requested so far and ms until the next frame (null when done). */
    private val onProgress: (requested: Int, total: Int, msToNext: Long?) -> Unit,
    private val onFinished: (requested: Int, total: Int, cancelled: Boolean) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var startMs = 0L
    private var requested = 0
    var running = false
        private set

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val due = startMs + requested * intervalMs
            if (now >= due) {
                if (tryCapture(requested + 1)) {
                    requested++
                    if (requested >= totalFrames) {
                        finish(cancelled = false)
                        return
                    }
                } else {
                    // Camera busy: retry this frame shortly.
                    onProgress(requested, totalFrames, 0L)
                    handler.postDelayed(this, 250)
                    return
                }
            }
            val next = startMs + requested * intervalMs
            val msToNext = (next - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            onProgress(requested, totalFrames, msToNext)
            handler.postDelayed(this, msToNext.coerceIn(50L, 1000L))
        }
    }

    fun start() {
        if (running) return
        running = true
        requested = 0
        startMs = SystemClock.elapsedRealtime()
        handler.post(tick)
    }

    fun cancel() {
        if (running) finish(cancelled = true)
    }

    private fun finish(cancelled: Boolean) {
        running = false
        handler.removeCallbacks(tick)
        onProgress(requested, totalFrames, null)
        onFinished(requested, totalFrames, cancelled)
    }

    companion object {
        /** Shortest allowed interval. 3D correction takes ~4 s per photo in the background. */
        fun minIntervalSec(correctionOn: Boolean): Int = if (correctionOn) 5 else 2

        fun formatDuration(totalSec: Long): String {
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return when {
                h > 0 -> "${h} h ${m} min"
                m > 0 -> if (s > 0) "${m} min ${s} s" else "${m} min"
                else -> "${s} s"
            }
        }
    }
}
