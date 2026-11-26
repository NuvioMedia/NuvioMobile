@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.audiosync

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Receives the player's own decoded audio (mono) as it is handed to the audio output. */
internal interface PlaybackPcmListener {
    /** Cheap check made per buffer; [mediaTimeUs] is where the buffer starts on the media timeline. */
    fun wantsPlaybackPcm(mediaTimeUs: Long, durationUs: Long): Boolean

    /** [mono] is only valid during the call. */
    fun onPlaybackPcm(mono: FloatArray, frames: Int, sampleRate: Int, mediaTimeUs: Long)
}

/**
 * Fallback audio source: listens to the PCM that ExoPlayer's own decoder produced, just before it
 * reaches the AudioTrack. It works for every codec the device can play (including vendor-only AC3,
 * E-AC3 or DTS decoders and HLS/DASH streams) but only in real time, without look-ahead. Nothing is
 * modified; passthrough/offloaded (still encoded) audio is ignored.
 */
internal class PlaybackAudioTap(
    sink: AudioSink,
    private val listener: PlaybackPcmListener,
) : ForwardingAudioSink(sink) {
    private var pcmFormat: Format? = null
    private var outputStreamOffsetUs = 0L
    private var pendingBuffer: ByteBuffer? = null
    private val pcmClock = PcmTimestampTracker()
    private var mono = FloatArray(0)

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        pcmFormat = inputFormat.takeIf {
            it.sampleMimeType == MimeTypes.AUDIO_RAW &&
                (it.pcmEncoding == C.ENCODING_PCM_16BIT || it.pcmEncoding == C.ENCODING_PCM_FLOAT) &&
                it.channelCount > 0 && it.sampleRate > 0
        }
        pendingBuffer = null
        pcmClock.reset()
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        this.outputStreamOffsetUs = outputStreamOffsetUs
        pcmClock.reset()
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val format = pcmFormat
        // Retry the same partially consumed buffer only once. Different decoded buffers can
        // legitimately share a timestamp (e.g. laced Opus), including a reused codec buffer.
        if (format != null && buffer !== pendingBuffer) {
            pendingBuffer = buffer
            try {
                tap(buffer, format, presentationTimeUs - outputStreamOffsetUs)
            } catch (_: Throwable) {
                // Never let sync analysis disturb playback.
            }
        }
        val consumed = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (consumed) pendingBuffer = null
        return consumed
    }

    override fun flush() {
        pendingBuffer = null
        pcmClock.reset()
        super.flush()
    }

    override fun handleDiscontinuity() {
        pendingBuffer = null
        pcmClock.reset()
        super.handleDiscontinuity()
    }

    private fun tap(buffer: ByteBuffer, format: Format, reportedMediaTimeUs: Long) {
        val channels = format.channelCount
        val isFloat = format.pcmEncoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val data = buffer.duplicate().order(ByteOrder.nativeOrder())
        val frames = data.remaining() / (bytesPerSample * channels)
        if (frames <= 0) return
        val mediaTimeUs = pcmClock.resolve(reportedMediaTimeUs, frames, format.sampleRate)
        val durationUs = frames * 1_000_000L / format.sampleRate
        if (!listener.wantsPlaybackPcm(mediaTimeUs, durationUs)) return
        if (mono.size < frames) mono = FloatArray(frames)
        PcmDownmix.toMono(
            buffer = data,
            startByte = data.position(),
            frames = frames,
            channels = channels,
            isFloat = isFloat,
            centerIndex = if (channels >= 3) 2 else -1,
            out = mono,
        )
        listener.onPlaybackPcm(mono, frames, format.sampleRate, mediaTimeUs)
    }
}

/** Mono downmix that favours the centre channel, where film dialogue normally lives. */
internal object PcmDownmix {
    private const val CENTER_WEIGHT = 0.7f
    private const val SIDE_WEIGHT = 0.15f

    fun toMono(
        buffer: ByteBuffer,
        startByte: Int,
        frames: Int,
        channels: Int,
        isFloat: Boolean,
        centerIndex: Int,
        out: FloatArray,
    ) {
        val bytesPerSample = if (isFloat) 4 else 2
        for (frame in 0 until frames) {
            val base = startByte + frame * channels * bytesPerSample
            if (centerIndex in 0 until channels && channels >= 3) {
                val left = sample(buffer, base, isFloat)
                val right = sample(buffer, base + bytesPerSample, isFloat)
                val center = sample(buffer, base + centerIndex * bytesPerSample, isFloat)
                out[frame] = CENTER_WEIGHT * center + SIDE_WEIGHT * (left + right)
            } else {
                var sum = 0f
                for (c in 0 until channels) sum += sample(buffer, base + c * bytesPerSample, isFloat)
                out[frame] = sum / channels
            }
        }
    }

    private fun sample(buffer: ByteBuffer, byteIndex: Int, isFloat: Boolean): Float =
        if (isFloat) buffer.getFloat(byteIndex) else buffer.getShort(byteIndex) / 32_768f
}
