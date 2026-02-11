package com.example.duallens3dcamera.media

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface

object ImageWriters {
    private const val TAG = "ImageWriters"

    /**
     * Writes JPEG bytes to [uri], then sets basic EXIF metadata.
     *
     * NOTE: The bytes are written first, then EXIF is updated in-place.
     */
    fun writeJpegWithExif(
        context: Context,
        uri: Uri,
        bytes: ByteArray,
        exifDateTimeOriginal: String,
        exifOrientation: Int? = null
    ) {
        context.contentResolver.openOutputStream(uri)?.use { os ->
            os.write(bytes)
            os.flush()
        } ?: throw IllegalStateException("openOutputStream failed for $uri")

        // DateTimeOriginal (+ optionally orientation)
        setExifDateTimeOriginal(context, uri, exifDateTimeOriginal, exifOrientation)
    }

    /**
     * Sets common EXIF time fields and (optionally) the orientation tag.
     */
    fun setExifDateTimeOriginal(
        context: Context,
        uri: Uri,
        exifDateTimeOriginal: String,
        exifOrientation: Int? = null
    ) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)

                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDateTimeOriginal)
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exifDateTimeOriginal)
                exif.setAttribute(ExifInterface.TAG_DATETIME, exifDateTimeOriginal)

                // StereoCameraController rotates pixels before encoding, so this is usually NORMAL.
                if (exifOrientation != null) {
                    exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
                }

                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set EXIF attributes: ${e.message}")
        }
    }
}
