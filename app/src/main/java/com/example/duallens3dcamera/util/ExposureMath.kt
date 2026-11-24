package com.example.duallens3dcamera.util

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Manual exposure helpers for Pro mode. Pure Kotlin so they can be unit-tested.
 *
 * Camera2 has no "shutter priority" or "ISO priority": once auto-exposure is off, both ISO and
 * exposure time must be given. When the user fixes only one of them, the other is derived from the
 * last auto-exposure reading so the picture keeps the same brightness (ISO x time is constant).
 */
object ExposureMath {

    const val NS_PER_S = 1_000_000_000L

    /** Shutter choices offered in Pro mode, fastest first (nanoseconds). */
    val SHUTTER_CHOICES_NS: List<Long> = listOf(
        NS_PER_S / 4000, NS_PER_S / 2000, NS_PER_S / 1000, NS_PER_S / 500, NS_PER_S / 250,
        NS_PER_S / 125, NS_PER_S / 60, NS_PER_S / 30, NS_PER_S / 15, NS_PER_S / 8,
        NS_PER_S / 4, NS_PER_S / 2, 800_000_000L
    )

    val ISO_CHOICES: List<Int> = listOf(100, 200, 400, 800, 1600)

    data class Exposure(val iso: Int, val exposureNs: Long)

    /**
     * @param manualIso / manualExposureNs the user's fixed values (null = derive from metering).
     * @param meteredIso / meteredExposureNs last auto-exposure reading (null if none yet).
     * @param isoRange / exposureRangeNs what BOTH lenses support.
     * @param maxExposureNs extra cap, e.g. one frame period while recording video.
     * Returns null when both manual values are null (Pro mode effectively off).
     */
    fun resolve(
        manualIso: Int?,
        manualExposureNs: Long?,
        meteredIso: Int?,
        meteredExposureNs: Long?,
        isoRange: IntRange,
        exposureRangeNs: LongRange,
        maxExposureNs: Long? = null
    ): Exposure? {
        if (manualIso == null && manualExposureNs == null) return null
        val expHi = minOf(exposureRangeNs.last, maxExposureNs ?: Long.MAX_VALUE).coerceAtLeast(exposureRangeNs.first)
        val expLo = exposureRangeNs.first
        // Light the scene needs, as ISO x seconds (falls back to a typical indoor reading).
        val ev = if (meteredIso != null && meteredExposureNs != null && meteredIso > 0 && meteredExposureNs > 0)
            meteredIso.toDouble() * meteredExposureNs else 400.0 * (NS_PER_S / 30)

        var iso = manualIso ?: (ev / (manualExposureNs ?: 1L)).roundToInt()
        iso = iso.coerceIn(isoRange.first, isoRange.last)
        var exp = manualExposureNs ?: (ev / iso).roundToLong()
        exp = exp.coerceIn(expLo, expHi)
        // If the user fixed the shutter but it got capped (video), keep ISO as chosen/derived.
        return Exposure(iso, exp)
    }

    fun shutterLabel(ns: Long): String {
        if (ns >= 700_000_000L) {
            val s = ns.toDouble() / NS_PER_S
            return if (s == s.roundToLong().toDouble()) "${s.roundToLong()}s" else String.format(java.util.Locale.US, "%.1fs", s)
        }
        val denom = (NS_PER_S.toDouble() / ns).roundToLong()
        return "1/$denom"
    }

    /** Intersection of two ranges, or null if they don't overlap. */
    fun intersect(a: IntRange?, b: IntRange?): IntRange? {
        if (a == null) return b
        if (b == null) return a
        val lo = maxOf(a.first, b.first)
        val hi = minOf(a.last, b.last)
        return if (lo <= hi) lo..hi else null
    }

    fun intersect(a: LongRange?, b: LongRange?): LongRange? {
        if (a == null) return b
        if (b == null) return a
        val lo = maxOf(a.first, b.first)
        val hi = minOf(a.last, b.last)
        return if (lo <= hi) lo..hi else null
    }
}
