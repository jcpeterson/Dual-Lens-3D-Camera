package com.example.duallens3dcamera.media

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

object MediaStoreUtils {
    // Base folder: Pictures/StereoCapture
    private val RELATIVE_DIR = "${Environment.DIRECTORY_PICTURES}/StereoCapture"

    // Output subfolders:
    //   Pictures/StereoCapture/videos
    //   Pictures/StereoCapture/jpg
    //   Pictures/StereoCapture/raw
    //   Pictures/StereoCapture/sbs
    private val RELATIVE_DIR_VIDEOS = "$RELATIVE_DIR/videos"
    private val RELATIVE_DIR_JPG = "$RELATIVE_DIR/jpg"
    private val RELATIVE_DIR_RAW = "$RELATIVE_DIR/raw"
    private val RELATIVE_DIR_SBS = "$RELATIVE_DIR/sbs"

    // to store logs
    private val RELATIVE_DIR_JSON_DOCS = "${Environment.DIRECTORY_DOCUMENTS}/StereoCapture"
    // fallback
    private val RELATIVE_DIR_JSON_DL = "${Environment.DIRECTORY_DOWNLOADS}/StereoCapture"

    private fun relativeDirForImage(displayName: String, mimeType: String): String {
        return when {
            // RAW (.dng)
            mimeType.equals("image/x-adobe-dng", ignoreCase = true) ||
                    displayName.endsWith(".dng", ignoreCase = true) ->
                RELATIVE_DIR_RAW

            // SBS (your app names it *_sbs.jpg)
            displayName.contains("_sbs", ignoreCase = true) ->
                RELATIVE_DIR_SBS

            // Default: normal JPGs (wide/ultrawide)
            else ->
                RELATIVE_DIR_JPG
        }
    }

    fun createPendingVideo(context: Context, displayName: String, timestampMillis: Long): Uri {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_TAKEN, timestampMillis)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR_VIDEOS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        return resolver.insert(collection, values)
            ?: throw IllegalStateException("Failed to insert video into MediaStore")
    }

    fun createPendingImage(context: Context, displayName: String, mimeType: String, timestampMillis: Long): Uri {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.DATE_TAKEN, timestampMillis)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDirForImage(displayName, mimeType))
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        return resolver.insert(collection, values)
            ?: throw IllegalStateException("Failed to insert image into MediaStore")
    }

    // for recording log data to analyze sync etc
//    fun createPendingJson(context: Context, displayName: String, timestampMs: Long): Uri {
//        val resolver = context.contentResolver
//        val values = ContentValues().apply {
//            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
//            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
//            // Only supported on Android 10+ (API 29+)
//            if (Build.VERSION.SDK_INT >= 29) {
//                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
//                put(MediaStore.MediaColumns.IS_PENDING, 1)
//            }
//            put(MediaStore.MediaColumns.DATE_ADDED, timestampMs / 1000)
//            put(MediaStore.MediaColumns.DATE_MODIFIED, timestampMs / 1000)
//        }
//        val collection = MediaStore.Files.getContentUri("external")
//        return resolver.insert(collection, values)
//            ?: throw IllegalStateException("Failed to create MediaStore JSON entry")
//    }
    // for recording log data to analyze sync etc
    fun createPendingJson(context: Context, displayName: String, timestampMs: Long): Uri {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")

        fun tryInsert(relativePath: String): Uri? {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")

                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                // Not required, but helps sorting in file managers
                put(MediaStore.MediaColumns.DATE_ADDED, timestampMs / 1000)
                put(MediaStore.MediaColumns.DATE_MODIFIED, timestampMs / 1000)
            }
            return resolver.insert(collection, values)
        }

        // Some Android builds disallow non-media (json) under Pictures/. Use Documents/ instead.
        return try {
            tryInsert(RELATIVE_DIR_JSON_DOCS) ?: throw IllegalStateException("Failed to create MediaStore JSON entry")
        } catch (e: IllegalArgumentException) {
            // If a device is strict about Documents too, fall back to Download.
            tryInsert(RELATIVE_DIR_JSON_DL) ?: throw e
        }
    }

    fun finalizePending(resolver: ContentResolver, uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, values, null, null)
        }
    }

    fun delete(resolver: ContentResolver, uri: Uri) {
        try {
            resolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }
}
