@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.audiosync

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput
import com.nuvio.app.features.player.RewrappableExtractor
import java.io.EOFException

/** Receives compressed audio samples as the player downloads and demuxes them, ahead of playback. */
internal interface AudioSampleSink {
    /** Cheap check made for every chunk of audio data; false leaves the track untouched. */
    fun wantsSamples(format: Format): Boolean

    /** [data] is only valid during the call. */
    fun onSample(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int)

    /** The extractor was repositioned (seek or reload); sample times jump. */
    fun onDiscontinuity()
}

/**
 * Wraps every extractor so audio samples are copied to [sink] while being forwarded unchanged to
 * the player. Nothing is downloaded or decoded twice by ExoPlayer; the copy happens at load time,
 * so the sink sees audio as far ahead as the player buffers.
 */
internal class AudioSyncExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val sink: AudioSampleSink,
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = wrap(delegate.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(delegate.createExtractors(uri, responseHeaders))

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
        Array(extractors.size) { index -> AudioTapExtractor(extractors[index], sink) }
}

private class AudioTapExtractor(
    private val delegate: Extractor,
    private val sink: AudioSampleSink,
) : RewrappableExtractor {
    override val wrappedExtractor: Extractor get() = delegate
    override fun rewrap(inner: Extractor): Extractor = AudioTapExtractor(inner, sink)

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.sniffFailureDetails

    override fun init(output: ExtractorOutput) = delegate.init(AudioTapExtractorOutput(output, sink))

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        sink.onDiscontinuity()
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}

private class AudioTapExtractorOutput(
    private val delegate: ExtractorOutput,
    private val sink: AudioSampleSink,
) : ExtractorOutput {
    private val audioOutputs = HashMap<Int, TrackOutput>()

    override fun track(id: Int, type: Int): TrackOutput {
        val output = delegate.track(id, type)
        if (type != C.TRACK_TYPE_AUDIO) return output
        return audioOutputs.getOrPut(id) { AudioTapTrackOutput(output, sink) }
    }

    override fun endTracks() = delegate.endTracks()

    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

/**
 * Tracks the bytes written for each sample. Extractors may write a sample in several parts and
 * report its metadata later (with [offset] bytes of following samples already written), so the
 * pending bytes are kept until [sampleMetadata] identifies the sample.
 */
private class AudioTapTrackOutput(
    private val delegate: TrackOutput,
    private val sink: AudioSampleSink,
) : TrackOutput {
    private var format: Format? = null
    private var capturing = false
    private var pending = ByteArray(0)
    private var start = 0
    private var end = 0
    private var scratch = ByteArray(0)
    private val scratchReader = ParsableByteArray()

    override fun format(format: Format) {
        this.format = format
        delegate.format(format)
    }

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (!updateCapturing()) {
            return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }
        if (scratch.size < length) scratch = ByteArray(maxOf(length, 4_096))
        val read = input.read(scratch, 0, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        append(scratch, 0, read)
        scratchReader.reset(scratch, read)
        delegate.sampleData(scratchReader, read, sampleDataPart)
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (updateCapturing()) append(data.data, data.position, length)
        delegate.sampleData(data, length, sampleDataPart)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        if (capturing) {
            val sampleStart = end - offset - size
            val currentFormat = format
            val usable = currentFormat != null && size > 0 && sampleStart >= start && timeUs != C.TIME_UNSET &&
                cryptoData == null && flags and UNSUPPORTED_FLAGS == 0
            if (usable) {
                try {
                    sink.onSample(currentFormat!!, timeUs, pending, sampleStart, size)
                } catch (_: Throwable) {
                    // Never let sync analysis disturb playback.
                }
            }
            start = maxOf(start, end - offset)
            if (start == end) {
                start = 0
                end = 0
            }
        }
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    private fun updateCapturing(): Boolean {
        val currentFormat = format
        val wanted = currentFormat != null && runCatching { sink.wantsSamples(currentFormat) }.getOrDefault(false)
        if (wanted != capturing) {
            capturing = wanted
            start = 0
            end = 0
        }
        return wanted
    }

    private fun append(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        if (pending.size - end < length) {
            val retained = end - start
            if (start > 0) {
                pending.copyInto(pending, 0, start, end)
                start = 0
                end = retained
            }
            if (pending.size - end < length) {
                val required = end + length
                if (required > MAX_PENDING_BYTES) {
                    // A pathological sample layout; give up on this batch rather than grow without bound.
                    start = 0
                    end = 0
                    return
                }
                pending = pending.copyOf(maxOf(required, pending.size * 2, 16_384))
            }
        }
        data.copyInto(pending, end, offset, offset + length)
        end += length
    }

    companion object {
        private const val UNSUPPORTED_FLAGS = C.BUFFER_FLAG_ENCRYPTED or C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA
        private const val MAX_PENDING_BYTES = 8 * 1024 * 1024
    }
}
