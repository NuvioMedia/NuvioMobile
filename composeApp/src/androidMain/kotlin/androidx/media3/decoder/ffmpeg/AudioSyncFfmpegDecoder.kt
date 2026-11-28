@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package androidx.media3.decoder.ffmpeg

import androidx.media3.common.C
import androidx.media3.common.Format

/**
 * Software FFmpeg audio decoder for Nuvio's audio subtitle sync, used when the device has no
 * MediaCodec decoder that can safely run next to the player's (Dolby/DTS on many phones).
 * Lives in this package because [FfmpegAudioDecoder] is package-private. Outputs float PCM in
 * FFmpeg's channel order (FL, FR, FC, LFE, ...).
 */
internal class AudioSyncFfmpegDecoder(format: Format) {
    private val decoder = FfmpegAudioDecoder(
        format,
        BUFFER_COUNT,
        BUFFER_COUNT,
        format.maxInputSize.takeIf { it > 0 } ?: DEFAULT_INPUT_SIZE,
        0,
        null,
        C.ENCODING_PCM_FLOAT,
    )

    val name: String get() = decoder.name
    val channelCount: Int get() = decoder.channelCount
    val sampleRate: Int get() = decoder.sampleRate

    /** Queues one access unit. Returns false when every input buffer is busy (drain, then retry). */
    fun queue(data: ByteArray, timeUs: Long): Boolean {
        val input = decoder.dequeueInputBuffer() ?: return false
        input.clear()
        input.ensureSpaceForWrite(data.size)
        input.data!!.put(data)
        input.timeUs = timeUs
        input.flip()
        decoder.queueInputBuffer(input)
        return true
    }

    /** Hands every decoded buffer to [onPcm] (float PCM, interleaved) and returns them. */
    fun drain(onPcm: (java.nio.ByteBuffer, Long) -> Unit) {
        while (true) {
            val output = decoder.dequeueOutputBuffer() ?: return
            try {
                val data = output.data
                if (!output.isEndOfStream && data != null && data.hasRemaining()) onPcm(data, output.timeUs)
            } finally {
                output.release()
            }
        }
    }

    fun flush() = decoder.flush()

    fun release() = decoder.release()

    companion object {
        private const val BUFFER_COUNT = 8
        private const val DEFAULT_INPUT_SIZE = 5_760

        fun supports(mimeType: String): Boolean =
            runCatching { FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(mimeType) }.getOrDefault(false)
    }
}
