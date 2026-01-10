package com.example.duallens3dcamera.encoding

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * Thin synchronized wrapper around MediaMuxer.
 * - Holds one MP4 file (one muxer) for one stream (wide OR ultrawide).
 * - Accepts both video and audio samples.
 * - Starts only after BOTH tracks are added.
 */
class Mp4Muxer(
    private val tag: String,
    private val pfd: ParcelFileDescriptor,
    orientationHint: Int
) {
    private val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
        setOrientationHint(orientationHint)
    }

    private val lock = Any()
    private var started = false
    private var released = false

    private var videoTrack = -1
    private var audioTrack = -1

    private data class PendingSample(
        val isAudio: Boolean,
        val data: ByteArray,
        val info: MediaCodec.BufferInfo
    )

    // Small queue for the brief time between first samples and muxer.start().
    private val pending = ArrayDeque<PendingSample>(64)

    fun setVideoFormat(format: MediaFormat) {
        synchronized(lock) {
            if (released) return
            if (videoTrack != -1) return
            videoTrack = muxer.addTrack(format)
            Log.i(tag, "Video track added idx=$videoTrack format=$format")
            maybeStartLocked()
        }
    }

    fun setAudioFormat(format: MediaFormat) {
        synchronized(lock) {
            if (released) return
            if (audioTrack != -1) return
            audioTrack = muxer.addTrack(format)
            Log.i(tag, "Audio track added idx=$audioTrack format=$format")
            maybeStartLocked()
        }
    }

    fun writeVideoSample(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        writeSample(isAudio = false, data = data, info = info)
    }

    fun writeAudioSample(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        writeSample(isAudio = true, data = data, info = info)
    }

    private fun writeSample(isAudio: Boolean, data: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (released) return

            val track = if (isAudio) audioTrack else videoTrack
            if (!started || track == -1) {
                // Muxer not started yet -> copy into pending queue (codec buffer will be reused).
                val dup = data.duplicate()
                dup.position(info.offset)
                dup.limit(info.offset + info.size)
                val bytes = ByteArray(info.size)
                dup.get(bytes)

                val copyInfo = MediaCodec.BufferInfo().apply {
                    set(0, info.size, info.presentationTimeUs, info.flags)
                }
                pending.addLast(PendingSample(isAudio, bytes, copyInfo))
                return
            }

            // Muxer started -> write directly (no copy).
            val dup = data.duplicate()
            dup.position(info.offset)
            dup.limit(info.offset + info.size)
            muxer.writeSampleData(track, dup, info)
        }
    }

    private fun maybeStartLocked() {
        if (started) return
        if (videoTrack == -1 || audioTrack == -1) return

        muxer.start()
        started = true
        Log.i(tag, "Muxer started (video=$videoTrack audio=$audioTrack)")

        // Flush any pending samples.
        while (pending.isNotEmpty()) {
            val s = pending.removeFirst()
            val track = if (s.isAudio) audioTrack else videoTrack
            val buf = ByteBuffer.wrap(s.data)
            muxer.writeSampleData(track, buf, s.info)
        }
    }

    fun stopAndRelease() {
        synchronized(lock) {
            if (released) return
            released = true

            try {
                if (started) muxer.stop()
            } catch (e: Exception) {
                Log.w(tag, "muxer.stop failed: ${e.message}")
            }

            try {
                muxer.release()
            } catch (e: Exception) {
                Log.w(tag, "muxer.release failed: ${e.message}")
            }

            try {
                pfd.close()
            } catch (_: Exception) {
            }
        }
        Log.i(tag, "Muxer released")
    }
}
