package com.example.duallens3dcamera.util

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Spirit-level maths for the viewfinder overlay. Pure Kotlin so it can be unit-tested.
 */
object LevelMath {

    /**
     * @property horizonDeg  canvas rotation (degrees, clockwise-positive like android.graphics.Canvas)
     *                       at which the true horizon appears on a portrait screen.
     * @property referenceDeg the nearest upright orientation (0, 90, -90 or 180) — portrait or landscape.
     * @property deviationDeg how far the phone is tilted from that orientation (signed).
     */
    data class Level(val horizonDeg: Float, val referenceDeg: Float, val deviationDeg: Float)

    /**
     * Phone roll from a gravity / accelerometer sample in device coordinates (x right, y up, z out
     * of the screen). Returns null when the phone lies nearly flat, where roll is meaningless.
     */
    fun fromGravity(gx: Float, gy: Float, gz: Float, flatRatio: Float = 0.35f): Level? {
        val inPlane = sqrt(gx * gx + gy * gy)
        val total = sqrt(gx * gx + gy * gy + gz * gz)
        if (total <= 0f || inPlane < flatRatio * total) return null
        // Turning the phone clockwise by t makes the world (and its horizon) appear rotated
        // anticlockwise by t on screen; atan2(gx, gy) is exactly -t, which is the canvas angle.
        val horizon = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()
        val reference = nearestRightAngle(horizon)
        return Level(horizon, reference, wrap180(horizon - reference))
    }

    fun nearestRightAngle(deg: Float): Float {
        val r = (wrap180(deg) / 90f).roundToInt() * 90f
        return if (r == -180f) 180f else r
    }

    fun wrap180(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    fun isLevel(level: Level, toleranceDeg: Float = 1f): Boolean = abs(level.deviationDeg) <= toleranceDeg

    /**
     * Which way on-screen labels should face: 0 (portrait), -90 / 90 (landscape) or 180.
     * The value only changes once the phone is clearly in the new orientation (within
     * 45 - [deadZoneDeg] degrees of it), so labels don't flip back and forth near 45 degrees.
     * A flat phone (null) keeps the current value.
     */
    fun uiRotation(current: Float, level: Level?, deadZoneDeg: Float = 10f): Float {
        if (level == null) return current
        val candidate = level.referenceDeg
        if (candidate == current) return current
        return if (abs(wrap180(level.horizonDeg - candidate)) <= 45f - deadZoneDeg) candidate else current
    }
}
