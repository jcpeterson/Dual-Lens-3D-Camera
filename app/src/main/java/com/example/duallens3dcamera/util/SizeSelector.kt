package com.example.duallens3dcamera.util

import android.util.Log
import android.util.Size
import android.hardware.camera2.params.StreamConfigurationMap
import android.graphics.ImageFormat
import kotlin.math.abs

object SizeSelector {
    private const val TAG = "SizeSelector"

    fun isFourByThree(size: Size, tolerance: Float = 0.01f): Boolean {
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        if (h == 0f) return false
        val r = w / h
        return abs(r - (4f / 3f)) <= tolerance
    }

    fun chooseLargestJpegFourByThree(map: StreamConfigurationMap): Size {
        val sizes = (map.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()).toList()
        if (sizes.isEmpty()) {
            // Safe fallback; should not happen on Pixel 7.
            return Size(1920, 1440)
        }

        val fourByThree = sizes.filter { isFourByThree(it) }
        val candidates = if (fourByThree.isNotEmpty()) fourByThree else sizes

        return candidates.maxBy { it.width.toLong() * it.height.toLong() }
    }


    fun chooseCommonFourByThreeSizeAt30FpsPrivate(
        wideMap: StreamConfigurationMap,
        ultraMap: StreamConfigurationMap,
        preferred: Size,
        maxW: Int,
        maxH: Int,
        targetFps: Int
    ): Pair<Size, String?> {
        val wideSizes = (wideMap.getOutputSizes(ImageFormat.PRIVATE) ?: emptyArray())
            .filter { isFourByThree(it) }
            .filter { it.width <= maxW && it.height <= maxH }
            .filter { supportsFps(wideMap, ImageFormat.PRIVATE, it, targetFps) }

        val ultraSizes = (ultraMap.getOutputSizes(ImageFormat.PRIVATE) ?: emptyArray())
            .filter { isFourByThree(it) }
            .filter { it.width <= maxW && it.height <= maxH }
            .filter { supportsFps(ultraMap, ImageFormat.PRIVATE, it, targetFps) }

        val ultraSet = ultraSizes.map { it.width to it.height }.toSet()
        val common = wideSizes.filter { ultraSet.contains(it.width to it.height) }

        if (common.isEmpty()) {
            Log.w(TAG, "No common PRIVATE 4:3 sizes found. Falling back.")
            val fallback = wideSizes.firstOrNull() ?: preferred
            return fallback to "No common PRIVATE 4:3 size; using ${fallback.width}x${fallback.height}."
        }

        val exact = common.firstOrNull { it.width == preferred.width && it.height == preferred.height }
        if (exact != null) return exact to null

        val best = common.maxBy { it.width.toLong() * it.height.toLong() }
        return best to "1920x1440@30 not common; fallback to ${best.width}x${best.height}@30."
    }

    fun choosePreviewFourByThree(
        wideMap: StreamConfigurationMap,
        maxW: Int,
        maxH: Int
    ): Size {
        val sizes = (wideMap.getOutputSizes(android.graphics.SurfaceTexture::class.java) ?: emptyArray())
            .filter { isFourByThree(it) }
            .filter { it.width <= maxW && it.height <= maxH }

        return sizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: Size(1280, 960)
    }

    fun chooseCommonJpegFourByThree(
        wideMap: StreamConfigurationMap,
        ultraMap: StreamConfigurationMap,
        maxW: Int,
        maxH: Int,
        preferred: Size?
    ): Pair<Size, String?> {
        val wideSizes = (wideMap.getOutputSizes(ImageFormat.JPEG) ?: emptyArray())
            .filter { isFourByThree(it) }
            .filter { it.width <= maxW && it.height <= maxH }

        val ultraSizes = (ultraMap.getOutputSizes(ImageFormat.JPEG) ?: emptyArray())
            .filter { isFourByThree(it) }
            .filter { it.width <= maxW && it.height <= maxH }

        val ultraSet = ultraSizes.map { it.width to it.height }.toSet()
        val common = wideSizes.filter { ultraSet.contains(it.width to it.height) }

        if (common.isEmpty()) {
            val fallback = preferred ?: wideSizes.firstOrNull() ?: Size(1920, 1440)
            return fallback to "No common JPEG 4:3 size; using ${fallback.width}x${fallback.height}."
        }

        if (preferred != null) {
            val exact = common.firstOrNull { it.width == preferred.width && it.height == preferred.height }
            if (exact != null) return exact to null
        }

        val best = common.maxBy { it.width.toLong() * it.height.toLong() }
        return best to null
    }

    fun chooseLargestRaw(map: StreamConfigurationMap): Size? {
        val sizes = map.getOutputSizes(ImageFormat.RAW_SENSOR) ?: return null
        return sizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
    }

    fun chooseCommonPrivateSizeAtFps(
        wideMap: StreamConfigurationMap,
        ultraMap: StreamConfigurationMap,
        preferred: Size,
        maxW: Int,
        maxH: Int,
        targetFps: Int
    ): Pair<Size, String?> {
        val wideSizes = (wideMap.getOutputSizes(ImageFormat.PRIVATE) ?: emptyArray())
            .filter { it.width <= maxW && it.height <= maxH }
            .filter { supportsFps(wideMap, ImageFormat.PRIVATE, it, targetFps) }

        val ultraSizes = (ultraMap.getOutputSizes(ImageFormat.PRIVATE) ?: emptyArray())
            .filter { it.width <= maxW && it.height <= maxH }
            .filter { supportsFps(ultraMap, ImageFormat.PRIVATE, it, targetFps) }

        val ultraSet = ultraSizes.map { it.width to it.height }.toSet()
        val common = wideSizes.filter { ultraSet.contains(it.width to it.height) }

        if (common.isEmpty()) {
            Log.w(TAG, "No common PRIVATE sizes found at ${targetFps}fps. Falling back.")
            val fallback = wideSizes.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: preferred
            return fallback to "No common PRIVATE size at ${targetFps}fps; using ${fallback.width}x${fallback.height}."
        }

        val exact = common.firstOrNull { it.width == preferred.width && it.height == preferred.height }
        if (exact != null) return exact to null

        val sameAspect = common.filter { approxSameAspect(it, preferred) }
        val candidates = if (sameAspect.isNotEmpty()) sameAspect else common

        val best = candidates.maxBy { it.width.toLong() * it.height.toLong() }
        return best to "Requested ${preferred.width}x${preferred.height}@${targetFps} not available; using ${best.width}x${best.height}@${targetFps}."
    }

    private fun aspectRatio(size: Size): Float {
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        if (h == 0f) return 0f
        return w / h
    }

    private fun approxSameAspect(a: Size, b: Size, tolerance: Float = 0.02f): Boolean {
        val arA = aspectRatio(a)
        val arB = aspectRatio(b)
        if (arA == 0f || arB == 0f) return false
        return abs(arA - arB) <= tolerance
    }

    private fun supportsFps(map: StreamConfigurationMap, format: Int, size: Size, fps: Int): Boolean {
        val minFrameDurationNs = try {
            map.getOutputMinFrameDuration(format, size)
        } catch (_: Exception) {
            0L
        }
        if (minFrameDurationNs == 0L) return true
        val maxFps = 1_000_000_000.0 / minFrameDurationNs.toDouble()
        return maxFps + 0.5 >= fps.toDouble()
    }
}
