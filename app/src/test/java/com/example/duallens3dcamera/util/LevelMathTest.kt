package com.example.duallens3dcamera.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class LevelMathTest {

    private fun gravityForClockwiseTurn(deg: Double, g: Double = 9.81): Triple<Float, Float, Float> {
        val t = Math.toRadians(deg)
        // World "up" seen from a phone turned clockwise by t points up-left in device coordinates.
        return Triple((-sin(t) * g).toFloat(), (cos(t) * g).toFloat(), 0f)
    }

    @Test
    fun uprightPortraitIsLevel() {
        val l = LevelMath.fromGravity(0f, 9.81f, 0f)
        assertNotNull(l)
        assertEquals(0f, l!!.horizonDeg, 1e-4f)
        assertEquals(0f, l.referenceDeg, 0f)
        assertTrue(LevelMath.isLevel(l))
    }

    @Test
    fun clockwiseTiltShowsHorizonAnticlockwise() {
        val (x, y, z) = gravityForClockwiseTurn(5.0)
        val l = LevelMath.fromGravity(x, y, z)!!
        assertEquals(-5f, l.horizonDeg, 1e-3f)   // canvas angle: negative = anticlockwise
        assertEquals(-5f, l.deviationDeg, 1e-3f)
        assertFalse(LevelMath.isLevel(l))
    }

    @Test
    fun landscapeSnapsToRightAngle() {
        val (x, y, z) = gravityForClockwiseTurn(90.5)
        val l = LevelMath.fromGravity(x, y, z)!!
        assertEquals(-90f, l.referenceDeg, 0f)
        assertEquals(-0.5f, l.deviationDeg, 1e-3f)
        assertTrue(LevelMath.isLevel(l))
    }

    @Test
    fun upsideDownUses180() {
        val (x, y, z) = gravityForClockwiseTurn(179.0)
        val l = LevelMath.fromGravity(x, y, z)!!
        assertEquals(180f, l.referenceDeg, 0f)
        assertEquals(1f, kotlin.math.abs(l.deviationDeg), 1e-3f)
    }

    @Test
    fun flatPhoneHasNoLevel() {
        assertNull(LevelMath.fromGravity(0.5f, 0.5f, 9.8f))
    }

    @Test
    fun uiRotationHasDeadZone() {
        fun lvl(turn: Double) = gravityForClockwiseTurn(turn).let { (x, y, z) -> LevelMath.fromGravity(x, y, z) }
        // Portrait, turning clockwise: stays portrait at 50 deg (inside the dead zone)...
        assertEquals(0f, LevelMath.uiRotation(0f, lvl(50.0)), 0f)
        // ...and switches to landscape (labels turned anticlockwise) at 60 deg.
        assertEquals(-90f, LevelMath.uiRotation(0f, lvl(60.0)), 0f)
        // Coming back from landscape: stays landscape at 40 deg, returns to portrait at 30 deg.
        assertEquals(-90f, LevelMath.uiRotation(-90f, lvl(40.0)), 0f)
        assertEquals(0f, LevelMath.uiRotation(-90f, lvl(30.0)), 0f)
        // Other landscape direction.
        assertEquals(90f, LevelMath.uiRotation(0f, lvl(-80.0)), 0f)
        // Flat phone keeps the current value.
        assertEquals(-90f, LevelMath.uiRotation(-90f, null), 0f)
    }

    @Test
    fun wrapKeepsRange() {
        assertEquals(-170f, LevelMath.wrap180(190f), 1e-4f)
        assertEquals(180f, LevelMath.wrap180(-180f), 1e-4f)
    }
}
