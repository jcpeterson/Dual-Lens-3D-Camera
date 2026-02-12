package com.example.duallens3dcamera.media

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.net.Uri
import android.util.Log

object DngWriter {
    private const val TAG = "DngWriter"

    /**
     * Writes a DNG image to [uri].
     *
     * If [orientation] is provided, it is passed to [DngCreator.setOrientation]. This avoids using
     * [androidx.exifinterface.media.ExifInterface], which would rewrite the entire DNG.
     */
    fun writeDng(
        context: Context,
        uri: Uri,
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        image: Image,
        orientation: Int? = null
    ) {
        val dngCreator = DngCreator(characteristics, result)

        if (orientation != null) {
            try {
                dngCreator.setOrientation(orientation)
            } catch (e: Exception) {
                Log.w(TAG, "DngCreator.setOrientation failed: ${e.message}")
            }
        }

        context.contentResolver.openOutputStream(uri)?.use { os ->
            dngCreator.writeImage(os, image)
            os.flush()
        } ?: throw IllegalStateException("openOutputStream failed for $uri")
    }
}
