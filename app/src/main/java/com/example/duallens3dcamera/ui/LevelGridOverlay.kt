package com.example.duallens3dcamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.duallens3dcamera.util.LevelMath
import kotlin.math.abs
import kotlin.math.min

/**
 * Draws over the viewfinder (never into photos): an optional 3x3 grid and an optional spirit level.
 * The level shows the true horizon as a line; it turns green when the phone is within 1 degree of
 * upright (portrait or landscape).
 */
class LevelGridOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var showGrid = false
        set(v) { field = v; invalidate() }
    var showLevel = false
        set(v) { field = v; invalidate() }

    /** Latest level reading, or null when the phone is flat / no sensor. */
    var level: LevelMath.Level? = null
        set(v) { field = v; if (showLevel) invalidate() }

    private val density = resources.displayMetrics.density

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255)
        strokeWidth = 1f * density
    }
    private val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16f * density
        textAlign = Paint.Align.CENTER
        setShadowLayer(3f * density, 0f, 0f, Color.BLACK)
    }

    private val green = Color.rgb(0, 230, 118)
    private val amber = Color.rgb(255, 193, 7)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (showGrid) {
            for (i in 1..2) {
                val x = w * i / 3f
                val y = h * i / 3f
                canvas.drawLine(x, 0f, x, h, gridPaint)
                canvas.drawLine(0f, y, w, y, gridPaint)
            }
        }

        if (showLevel) {
            val cx = w / 2f
            val cy = h / 2f
            val l = level
            if (l == null) {
                textPaint.color = Color.WHITE
                canvas.drawText("Level: hold the phone upright", cx, cy - 12f * density, textPaint)
                return
            }
            val ok = LevelMath.isLevel(l)
            val half = min(w, h) * 0.32f
            val gap = 18f * density

            // Fixed reference ticks: where the horizon should be when the phone is level.
            canvas.save()
            canvas.rotate(l.referenceDeg, cx, cy)
            canvas.drawLine(cx - half - 28f * density, cy, cx - half - 6f * density, cy, refPaint)
            canvas.drawLine(cx + half + 6f * density, cy, cx + half + 28f * density, cy, refPaint)
            canvas.restore()

            // Live horizon line (gap in the middle so the subject stays visible).
            horizonPaint.color = if (ok) green else amber
            canvas.save()
            canvas.rotate(l.horizonDeg, cx, cy)
            canvas.drawLine(cx - half, cy, cx - gap, cy, horizonPaint)
            canvas.drawLine(cx + gap, cy, cx + half, cy, horizonPaint)
            canvas.restore()

            // Angle text below the line in the phone's current orientation, so it reads upright
            // whether the phone is held in portrait or landscape.
            textPaint.color = if (ok) green else amber
            val dev = abs(l.deviationDeg)
            canvas.save()
            canvas.rotate(l.referenceDeg, cx, cy)
            canvas.drawText(String.format(java.util.Locale.US, "%.1f°", dev), cx, cy + 34f * density, textPaint)
            canvas.restore()
        }
    }
}
