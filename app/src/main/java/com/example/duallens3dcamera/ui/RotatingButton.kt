package com.example.duallens3dcamera.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import com.google.android.material.button.MaterialButton
import com.example.duallens3dcamera.util.LevelMath

/**
 * A MaterialButton whose label and icon can turn (e.g. upright when the phone is held in landscape)
 * while the button itself, its background and its touch area stay where they are.
 *
 * The background is drawn by View.draw() before onDraw(); TextView.onDraw() draws the text and the
 * icon, so rotating the canvas only inside onDraw() turns just the content.
 */
class RotatingButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    var contentRotation = 0f
        set(v) { field = v; invalidate() }

    private var animator: ValueAnimator? = null

    /** Turn the content to [target] degrees along the shortest way. */
    fun animateContentRotation(target: Float, durationMs: Long = 220) {
        animator?.cancel()
        val start = contentRotation
        val end = start + LevelMath.wrap180(target - start)
        if (start == end) return
        animator = ValueAnimator.ofFloat(start, end).apply {
            duration = durationMs
            addUpdateListener { contentRotation = it.animatedValue as Float }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (contentRotation == 0f) {
            super.onDraw(canvas)
            return
        }
        canvas.save()
        canvas.rotate(contentRotation, width / 2f, height / 2f)
        super.onDraw(canvas)
        canvas.restore()
    }
}
