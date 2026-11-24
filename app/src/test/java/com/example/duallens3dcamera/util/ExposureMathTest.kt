package com.example.duallens3dcamera.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExposureMathTest {

    private val isoRange = 100..2000
    private val expRange = 100_000L..800_000_000L
    private val s = ExposureMath.NS_PER_S

    @Test
    fun bothAutoMeansOff() {
        assertNull(ExposureMath.resolve(null, null, 400, s / 50, isoRange, expRange))
    }

    @Test
    fun fixedIsoKeepsBrightness() {
        // Metered ISO 400 at 1/50 s; user picks ISO 100 -> 4x longer: 1/12.5 s.
        val e = ExposureMath.resolve(100, null, 400, s / 50, isoRange, expRange)!!
        assertEquals(100, e.iso)
        assertEquals(s * 4 / 50, e.exposureNs)
    }

    @Test
    fun fixedShutterKeepsBrightness() {
        // Metered ISO 400 at 1/50 s; user picks 1/100 s -> ISO 800.
        val e = ExposureMath.resolve(null, s / 100, 400, s / 50, isoRange, expRange)!!
        assertEquals(800, e.iso)
        assertEquals(s / 100, e.exposureNs)
    }

    @Test
    fun derivedIsoIsClampedToBothLenses() {
        // Needs ISO 8000, but the ultrawide stops at 2000.
        val e = ExposureMath.resolve(null, s / 2000, 400, s / 100, isoRange, expRange)!!
        assertEquals(2000, e.iso)
    }

    @Test
    fun videoCapsShutterAtFramePeriod() {
        val e = ExposureMath.resolve(100, s / 4, 400, s / 50, isoRange, expRange, maxExposureNs = s / 30)!!
        assertEquals(s / 30, e.exposureNs)
    }

    @Test
    fun bothManualUsedAsIs() {
        val e = ExposureMath.resolve(200, s / 125, null, null, isoRange, expRange)!!
        assertEquals(200, e.iso)
        assertEquals(s / 125, e.exposureNs)
    }

    @Test
    fun labels() {
        assertEquals("1/125", ExposureMath.shutterLabel(s / 125))
        assertEquals("1/4000", ExposureMath.shutterLabel(s / 4000))
        assertEquals("0.8s", ExposureMath.shutterLabel(800_000_000L))
        assertEquals("1/2", ExposureMath.shutterLabel(s / 2))
    }

    @Test
    fun rangeIntersection() {
        assertEquals(100..2000, ExposureMath.intersect(50..6400, 100..2000))
        assertNull(ExposureMath.intersect(10..20, 30..40))
    }
}
