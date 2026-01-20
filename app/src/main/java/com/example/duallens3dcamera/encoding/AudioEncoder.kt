package com.example.duallens3dcamera.encoding

import android.annotation.SuppressLint
import android.media.*
import android.util.Log
import java.nio.ByteBuffer
import android.os.SystemClock

/**
 * One microphone capture -> one AAC encoder -> write the SAME audio samples into BOTH muxers.
 *
 * This is intentionally simple:
 * - AudioRecord (PCM 16-bit) -> AAC LC MediaCodec -> mux into both MP4 files.
 */
class AudioEncoder(
    private val tag: String,
    private val muxers: List<Mp4Muxer>,
    private val sampleRate: Int = 48_000,
    private val channelCount: Int = 1,
    private val bitrateBps: Int = 128_000
) {
    companion object {
        private const val MIME_AAC = "audio/mp4a-latm"
        private const val TIMEOUT_US = 10_000L
    }

    private var codec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null

    private var feedThread: Thread? = null
    private var drainThread: Thread? = null

    private val bufferInfo = MediaCodec.BufferInfo()

    @Volatile private var feedStopRequested = false
    @Volatile private var eosQueued = false
    @Volatile private var started = false
    @Volatile private var capturing = false

    private var totalSamplesSubmitted: Long = 0L

    // PCM_16BIT => 2 bytes per sample * channelCount
    private val bytesPerFrame: Int
        get() = 2 * channelCount

    private var tempBuffer: ByteArray = ByteArray(4096)

    /**
     * For logging
     * Called on the audio encoder thread for every encoded AAC sample we pass to the muxers.
     * Note: we write the *same* AAC frames into both MP4s.
     */
    var onEncodedSample: ((
        codecPtsUs: Long,
        muxerPtsUs: Long,
        sizeBytes: Int,
        flags: Int,
        writeDurationNs: Long
    ) -> Unit)? = null

    /**
     * Starts the AAC encoder and prepares AudioRecord (but does NOT start recording yet).
     * We start AudioRecord in startCapturing() so we can align audio start closer to video start.
     */
    @SuppressLint("MissingPermission") // Activity already requests/ensures RECORD_AUDIO at runtime.
    fun start() {
        if (started) return

        val format = MediaFormat.createAudioFormat(MIME_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }

        codec = MediaCodec.createEncoderByType(MIME_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        val channelConfig = if (channelCount == 1) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AudioFormat.CHANNEL_IN_STEREO
        }

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            throw IllegalStateException("AudioRecord.getMinBufferSize failed: $minBuf")
        }

        // Make it comfortably larger than min to reduce overruns.
        val bufSize = (minBuf.coerceAtLeast(4096)) * 2

        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (se: SecurityException) {
            throw IllegalStateException("RECORD_AUDIO permission missing (AudioRecord ctor)", se)
        }.also {
            if (it.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord init failed (state=${it.state})")
            }
        }

        // Feed chunks up to 16KB.
        tempBuffer = ByteArray(minOf(bufSize, 16 * 1024))

        // Drain thread starts now so we quickly catch INFO_OUTPUT_FORMAT_CHANGED once input arrives.
        drainThread = Thread({ drainLoop() }, "$tag-Drain").also { it.start() }

        started = true
        Log.i(tag, "AudioEncoder started (codec+AudioRecord prepared). sr=$sampleRate ch=$channelCount br=$bitrateBps")
    }

    /**
     * Starts AudioRecord and begins feeding PCM into the AAC encoder.
     * Call this right after camera recording repeating request starts.
     */
    @SuppressLint("MissingPermission") // Activity already requests/ensures RECORD_AUDIO at runtime.
    fun startCapturing() {
        if (!started) throw IllegalStateException("Call start() first")
        if (capturing) return

        capturing = true
        feedStopRequested = false
        eosQueued = false
        totalSamplesSubmitted = 0L

        val record = requireNotNull(audioRecord)
        val codecLocal = requireNotNull(codec)

        feedThread = Thread({
            try {
                record.startRecording()
            } catch (se: SecurityException) {
                Log.e(tag, "AudioRecord.startRecording SecurityException: ${se.message}")
                feedStopRequested = true
            } catch (e: Exception) {
                Log.e(tag, "AudioRecord.startRecording failed: ${e.message}")
                feedStopRequested = true
            }

            while (!feedStopRequested) {
                val inIndex = codecLocal.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex < 0) continue

                val inBuf = codecLocal.getInputBuffer(inIndex)
                if (inBuf == null) {
                    codecLocal.queueInputBuffer(inIndex, 0, 0, 0, 0)
                    continue
                }

                inBuf.clear()

                val toRead = minOf(tempBuffer.size, inBuf.capacity())
                val readBytes = try {
                    record.read(tempBuffer, 0, toRead)
                } catch (e: Exception) {
                    Log.w(tag, "AudioRecord.read failed: ${e.message}")
                    AudioRecord.ERROR_INVALID_OPERATION
                }

                val ptsUs = (totalSamplesSubmitted * 1_000_000L) / sampleRate.toLong()

                if (readBytes > 0) {
                    inBuf.put(tempBuffer, 0, readBytes)
                    codecLocal.queueInputBuffer(inIndex, 0, readBytes, ptsUs, 0)

                    val frames = readBytes / bytesPerFrame
                    totalSamplesSubmitted += frames.toLong()
                } else {
                    // Keep codec moving; don't advance timestamp.
                    codecLocal.queueInputBuffer(inIndex, 0, 0, ptsUs, 0)
                }
            }

            try {
                record.stop()
            } catch (_: Exception) {
            }

            queueEos()
        }, "$tag-Feed").also { it.start() }

        Log.i(tag, "Audio capture started")
    }

    fun stopAndRelease() {
        feedStopRequested = true

        // Unblock any pending read().
        try { audioRecord?.stop() } catch (_: Exception) {}

        try { feedThread?.join(3_000) } catch (_: Exception) {}
        feedThread = null

        // Ensure EOS even if feed thread didn’t manage to queue it.
        queueEos()

        try { drainThread?.join(5_000) } catch (_: Exception) {}
        drainThread = null

        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null

        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        capturing = false
        started = false

        Log.i(tag, "AudioEncoder stopped/released")
    }

    @Synchronized
    private fun queueEos() {
        if (eosQueued) return
        eosQueued = true

        val codecLocal = codec ?: return
        val ptsUs = (totalSamplesSubmitted * 1_000_000L) / sampleRate.toLong()

        // Try a few times to get an input buffer slot for EOS.
        repeat(20) {
            val inIndex = codecLocal.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                codecLocal.queueInputBuffer(
                    inIndex,
                    0,
                    0,
                    ptsUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                Log.i(tag, "Queued audio EOS")
                return
            }
            try { Thread.sleep(5) } catch (_: Exception) {}
        }

        Log.w(tag, "Failed to queue audio EOS (no input buffer available)")
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
                    val fmt = codecLocal.outputFormat
                    muxers.forEach { it.setAudioFormat(fmt) }
                    Log.i(tag, "Audio output format: $fmt")
                }

                outIndex >= 0 -> {
                    val outBuf: ByteBuffer? = codecLocal.getOutputBuffer(outIndex)
                    if (outBuf == null) {
                        codecLocal.releaseOutputBuffer(outIndex, false)
                        continue
                    }

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }

//                    if (bufferInfo.size > 0) {
//                        val writeInfo = MediaCodec.BufferInfo().apply {
//                            set(bufferInfo.offset, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
//                        }
//                        // Duplicate audio into both MP4s.
//                        //muxers.forEach { it.writeAudioSample(outBuf, writeInfo) }
//                        // NEW: logging version
//                        val codecPtsUs = bufferInfo.presentationTimeUs
//                        val t0 = SystemClock.elapsedRealtimeNanos()
//                        for (muxer in muxers) {
//                            muxer.writeAudioSample(outBuf, writeInfo)
//                        }
//                        val t1 = SystemClock.elapsedRealtimeNanos()
//
//                        onEncodedSample?.invoke(
//                            codecPtsUs,
//                            writeInfo.presentationTimeUs,
//                            writeInfo.size,
//                            writeInfo.flags,
//                            (t1 - t0)
//                        )
//
//                    }
                    if (bufferInfo.size > 0) {
                        val codecPtsUs = bufferInfo.presentationTimeUs

                        val writeInfo = MediaCodec.BufferInfo().apply {
                            set(
                                bufferInfo.offset,
                                bufferInfo.size,
                                bufferInfo.presentationTimeUs,
                                bufferInfo.flags
                            )
                        }

                        val cb = onEncodedSample
                        if (cb != null) {
                            val t0 = SystemClock.elapsedRealtimeNanos()
//                            for (muxer in muxers) {
//                                muxer.writeAudioSample(outBuf, writeInfo)
//                            }
                            for (muxer in muxers) {
                                muxer.writeAudioSample(outBuf.duplicate(), writeInfo)
                            }
                            val t1 = SystemClock.elapsedRealtimeNanos()

                            cb.invoke(
                                codecPtsUs,
                                writeInfo.presentationTimeUs,
                                writeInfo.size,
                                writeInfo.flags,
                                (t1 - t0)
                            )
                        } else {
//                            for (muxer in muxers) {
//                                muxer.writeAudioSample(outBuf, writeInfo)
//                            }
                            for (muxer in muxers) {
                                muxer.writeAudioSample(outBuf.duplicate(), writeInfo)
                            }
                        }
                    }

                    val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codecLocal.releaseOutputBuffer(outIndex, false)

                    if (eos) {
                        Log.i(tag, "Audio EOS reached")
                        break
                    }
                }
            }
        }
    }
}
