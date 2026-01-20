package com.example.duallens3dcamera.encoding

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import android.os.SystemClock

class VideoEncoder(
    private val tag: String,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrateBps: Int,
    private val iFrameIntervalSec: Int,
    private val muxer: Mp4Muxer
) {
    companion object {
        private const val MIME_AVC = "video/avc"
        private const val TIMEOUT_US = 10_000L
    }

    private var codec: MediaCodec? = null
    private var drainThread: Thread? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    private var firstPtsUs: Long = -1L
    @Volatile private var stopRequested: Boolean = false

    private var _inputSurface: Surface? = null
    val inputSurface: Surface
        get() = requireNotNull(_inputSurface) { "Encoder not started" }

    /**
     * For logging
     * Called on the encoder thread for every encoded video sample that we pass to the muxer.
     *
     * codecPtsUs = MediaCodec.BufferInfo.presentationTimeUs from the encoder output.
     * muxerPtsUs = the PTS we wrote to MediaMuxer (in this app we normalize to start at 0 per file).
     */
    var onEncodedSample: ((
        codecPtsUs: Long,
        muxerPtsUs: Long,
        sizeBytes: Int,
        flags: Int,
        writeDurationNs: Long
    ) -> Unit)? = null

    fun start() {
        val format = MediaFormat.createVideoFormat(MIME_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSec)

            // Prefer CBR if supported.
            val encoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(this)
            if (encoderName != null) {
                val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name == encoderName }
                val caps = info?.getCapabilitiesForType(MIME_AVC)?.encoderCapabilities
                if (caps != null && caps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    Log.i(tag, "Using CBR bitrate mode")
                }
            }
        }

        val encoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
        codec = if (encoderName != null) MediaCodec.createByCodecName(encoderName) else MediaCodec.createEncoderByType(MIME_AVC)

        codec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        _inputSurface = codec!!.createInputSurface()
        codec!!.start()

        stopRequested = false
        drainThread = Thread({ drainLoop() }, "$tag-Drain").also { it.start() }

        Log.i(tag, "VideoEncoder started ${width}x${height}@${fps} bitrate=$bitrateBps i=$iFrameIntervalSec")
    }

    fun signalEndOfInputStream() {
        try {
            codec?.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.w(tag, "signalEndOfInputStream failed: ${e.message}")
        }
    }

    fun stopAndRelease() {
        stopRequested = true
        signalEndOfInputStream()

        try {
            drainThread?.join(5_000)
        } catch (_: Exception) {
        }
        drainThread = null

        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null

        try {
            _inputSurface?.release()
        } catch (_: Exception) {
        }
        _inputSurface = null

        Log.i(tag, "VideoEncoder stopped/released")
    }

    private fun drainLoop() {
        val codecLocal = codec ?: return

        while (true) {
            val outIndex = codecLocal.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Keep looping until EOS.
                }

                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Add video track. (Muxer starts once audio track also exists.)
                    val newFormat = codecLocal.outputFormat
                    muxer.setVideoFormat(newFormat)
                    Log.i(tag, "Video output format: $newFormat")
                }

                outIndex >= 0 -> {
                    val encodedData: ByteBuffer? = codecLocal.getOutputBuffer(outIndex)
                    if (encodedData == null) {
                        codecLocal.releaseOutputBuffer(outIndex, false)
                        continue
                    }

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0) {
                        // Absolute codec PTS as produced by MediaCodec (derived from input surface timestamps).
                        val codecPtsUs = bufferInfo.presentationTimeUs

                        // Normalize so each MP4 starts at t=0.
                        if (firstPtsUs < 0) firstPtsUs = codecPtsUs
                        val muxerPtsUs = codecPtsUs - firstPtsUs

                        val writeInfo = MediaCodec.BufferInfo().apply {
                            set(bufferInfo.offset, bufferInfo.size, muxerPtsUs, bufferInfo.flags)
                        }

                        // If logging is disabled, avoid timing + callback overhead.
                        val cb = onEncodedSample
                        if (cb != null) {
                            val t0 = SystemClock.elapsedRealtimeNanos()
                            muxer.writeVideoSample(encodedData, writeInfo)
                            val t1 = SystemClock.elapsedRealtimeNanos()

                            cb.invoke(
                                /* codecPtsUs */ codecPtsUs,
                                /* muxerPtsUs */ writeInfo.presentationTimeUs,
                                /* sizeBytes  */ writeInfo.size,
                                /* flags      */ writeInfo.flags,
                                /* writeNs    */ (t1 - t0)
                            )
                        } else {
                            muxer.writeVideoSample(encodedData, writeInfo)
                        }
                    }

                    val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codecLocal.releaseOutputBuffer(outIndex, false)

                    if (eos) {
                        Log.i(tag, "Video EOS reached")
                        break
                    }
                }
            }
        }
    }
}
