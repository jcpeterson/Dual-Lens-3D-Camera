package com.example.duallens3dcamera.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the Kotlin port against tools/stereo_fix (Python + OpenCV) on a real Redmi Note 12 Pro
 * wide/ultrawide pair. Fixture produced by tools/stereo_fix/export_fixture.py.
 */
class StereoAlignMathTest {

    private val fx: Map<String, DoubleArray> by lazy {
        val text = requireNotNull(javaClass.classLoader?.getResource("stereo_align_fixture.txt")) {
            "fixture missing"
        }.readText()
        text.lines().filter { it.isNotBlank() }.associate { line ->
            val parts = line.trim().split(' ')
            parts[0] to DoubleArray(parts.size - 1) { parts[it + 1].toDouble() }
        }
    }

    private fun v(key: String) = requireNotNull(fx[key]) { "missing $key" }
    private fun s(key: String) = v(key)[0]

    @Test
    fun refineMatchesPythonReference() {
        val fit = StereoAlignMath.refine(
            v("ref_x"), v("ref_y"), v("mov_x"), v("mov_y"),
            BooleanArray(v("base_inliers").size) { v("base_inliers")[it] > 0.5 },
            s("mov_w").toInt(), s("mov_h").toInt(), fitDistortion = true
        )
        val m = fit.model
        assertEquals(s("expected.k"), m.k, 1e-9)
        assertEquals(s("expected.a"), m.a, 1e-6)
        assertEquals(s("expected.b"), m.b, 1e-6)
        assertEquals(s("expected.ty"), m.ty, 1e-3)
        assertEquals(s("expected.inliers").toInt(), fit.inlierCount)

        val d = StereoAlignMath.horizontalOffsets(v("ref_x"), fit.corrX, fit.corrY, m.a, m.b)
        val dIn = d.filterIndexed { i, _ -> fit.inliers[i] }.toDoubleArray()
        assertEquals(s("expected.tx_wide_left_98"), StereoAlignMath.windowShift(dIn, wideIsLeft = true), 1e-3)
        assertEquals(s("expected.tx_wide_right_98"), StereoAlignMath.windowShift(dIn, wideIsLeft = false), 1e-3)
    }

    @Test
    fun symmetricPlanMatchesPython() {
        val fit = StereoAlignMath.refine(
            v("ref_x"), v("ref_y"), v("mov_x"), v("mov_y"),
            BooleanArray(v("base_inliers").size) { v("base_inliers")[it] > 0.5 },
            s("mov_w").toInt(), s("mov_h").toInt(), fitDistortion = true
        )
        val plan = StereoAlignMath.symmetricPlan(fit.model, s("expected.tx_wide_left_98"),
            s("sym.wide_w").toInt(), s("sym.wide_h").toInt())
        assertEquals(s("sym.size").toInt(), plan.width)
        assertEquals(v("sym.size")[1].toInt(), plan.height)
        val hw = v("sym.hw"); val nk = v("sym.newk")
        for (i in 0 until 6) assertEquals("hw[$i]", hw[i], plan.wideAffine[i], 1e-3)
        for (i in 0 until 9) assertEquals("newK[$i]", nk[i], plan.ultraNewK[i], 1e-2)
    }

    @Test
    fun averageTargetLutsMatchNumpy() {
        val (a, b) = StereoAlignMath.averageTargetLuts(v("hist.src"), v("hist.ref"))
        val ea = v("hist.avg_a"); val eb = v("hist.avg_b")
        for (i in 0 until 256) {
            assertTrue("a[$i]", kotlin.math.abs(a[i] - ea[i].toInt()) <= 1)
            assertTrue("b[$i]", kotlin.math.abs(b[i] - eb[i].toInt()) <= 1)
        }
    }

    @Test
    fun histogramLutMatchesNumpy() {
        val lut = StereoAlignMath.histogramMatchLut(v("hist.src"), v("hist.ref"))
        val expected = v("hist.lut").map { it.toInt() }
        // numpy's sum() is pairwise while cumsum() is sequential, so its last CDF value can land a
        // hair below 1.0 and truncate one level lower; allow +-1.
        for (i in 0 until 256) {
            assertTrue("lut[$i] ${lut[i]} vs ${expected[i]}", kotlin.math.abs(lut[i] - expected[i]) <= 1)
        }
        assertEquals(expected.count { true }, lut.size)
    }

    @Test
    fun validRectMatchesPython() {
        val w = s("rect.w").toInt(); val h = s("rect.h").toInt()
        val mask = BooleanArray(w * h) { v("rect.mask")[it] > 0.5 }
        val rect = StereoAlignMath.largestValidRect(mask, w, h)
        assertArrayEquals(v("rect.expected").map { it.toInt() }.toIntArray(), rect)
    }

    @Test
    fun undistortInvertsTheK1Model() {
        val k = 0.1; val cx = 1632.0; val cy = 1224.0; val r = 2040.0
        // Forward model on corrected points, then invert.
        val ux = doubleArrayOf(100.0, 1632.0, 3000.0, 2500.0)
        val uy = doubleArrayOf(80.0, 1224.0, 2300.0, 400.0)
        val dx = DoubleArray(4); val dy = DoubleArray(4)
        for (i in 0 until 4) {
            val nx = (ux[i] - cx) / r; val ny = (uy[i] - cy) / r
            val f = 1 + k * (nx * nx + ny * ny)
            dx[i] = cx + nx * f * r; dy[i] = cy + ny * f * r
        }
        val (bx, by) = StereoAlignMath.undistort(dx, dy, k, cx, cy, r)
        for (i in 0 until 4) {
            assertTrue("x[$i]", kotlin.math.abs(bx[i] - ux[i]) < 0.05)
            assertTrue("y[$i]", kotlin.math.abs(by[i] - uy[i]) < 0.05)
        }
    }

    @Test
    fun percentileMatchesNumpyLinear() {
        val vals = doubleArrayOf(5.0, 1.0, 3.0, 2.0, 4.0)
        assertEquals(4.92, StereoAlignMath.percentile(vals, 98.0), 1e-9)
        assertEquals(3.0, StereoAlignMath.percentile(vals, 50.0), 1e-9)
    }
}
