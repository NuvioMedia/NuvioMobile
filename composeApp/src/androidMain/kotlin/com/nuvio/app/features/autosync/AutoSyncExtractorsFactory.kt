@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.autosync

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueDecoder
import com.nuvio.app.features.player.RewrappableExtractor
import com.nuvio.app.features.player.SubtitleSyncCue
import java.io.EOFException
import kotlin.math.max

/** Observes embedded text timestamps while forwarding the extractor output unchanged to Media3. */
internal class AutoSyncExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val sourceKey: String,
) : ExtractorsFactory {
    init {
        EmbeddedSubtitleCueStore.reset(sourceKey)
    }

    override fun createExtractors(): Array<Extractor> = wrap(delegate.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(delegate.createExtractors(uri, responseHeaders))

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
        Array<Extractor>(extractors.size) { index ->
            ObservingExtractor(extractors[index], sourceKey)
        }
}

private class ObservingExtractor(
    private val delegate: Extractor,
    private val sourceKey: String,
) : RewrappableExtractor {
    override val wrappedExtractor: Extractor get() = delegate
    override fun rewrap(inner: Extractor): Extractor = ObservingExtractor(inner, sourceKey)
    override fun sniff(input: ExtractorInput) = delegate.sniff(input)
    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.getSniffFailureDetails()
    override fun init(output: ExtractorOutput) = delegate.init(ObservingExtractorOutput(output, sourceKey))
    override fun read(input: ExtractorInput, seekPosition: PositionHolder) = delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        EmbeddedSubtitleCueStore.beginNewGeneration(
            sourceKey = sourceKey,
            targetTimeMs = timeUs.takeIf { it != C.TIME_UNSET }?.div(1_000L),
        )
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()
    override fun getUnderlyingImplementation(): Extractor = delegate.getUnderlyingImplementation()
}

private class ObservingExtractorOutput(
    private val delegate: ExtractorOutput,
    private val sourceKey: String,
) : ExtractorOutput {
    private val textTracks = mutableMapOf<Int, TrackOutput>()

    override fun track(id: Int, type: Int): TrackOutput {
        val output = delegate.track(id, type)
        return if (type == C.TRACK_TYPE_TEXT) {
            textTracks.getOrPut(id) { ObservingTextTrackOutput(output, sourceKey, id) }
        } else output
    }

    override fun endTracks() = delegate.endTracks()
    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

private class ObservingTextTrackOutput(
    private val delegate: TrackOutput,
    private val sourceKey: String,
    private val trackId: Int,
) : TrackOutput {
    private val cueDecoder = CueDecoder()
    private var format: Format? = null
    private var buffered = ByteArray(0)
    private var start = 0
    private var end = 0

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun format(format: Format) {
        this.format = format
        delegate.format(format)
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }
        val bytes = ByteArray(length)
        val read = input.read(bytes, 0, length)
        if (read == C.RESULT_END_OF_INPUT) {
            return if (allowEndOfInput) C.RESULT_END_OF_INPUT else throw EOFException()
        }
        if (read > 0) {
            append(bytes, 0, read)
            delegate.sampleData(ParsableByteArray(bytes, read), read, sampleDataPart)
        }
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) append(data.data, data.position, length)
        delegate.sampleData(data, length, sampleDataPart)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        observe(timeUs, size, offset)
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        start = (end - offset).coerceIn(start, end)
        if (start == end) {
            start = 0
            end = 0
        }
    }

    private fun observe(timeUs: Long, size: Int, offset: Int) {
        if (timeUs == C.TIME_UNSET || size <= 0) return
        val sampleStart = end - offset - size
        val currentFormat = format
        if (currentFormat?.sampleMimeType.equals("application/pgs", ignoreCase = true)) {
            return
        }
        if (currentFormat?.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES &&
            sampleStart >= start && sampleStart + size <= end
        ) {
            val decoded = runCatching { cueDecoder.decode(timeUs, buffered, sampleStart, size) }.getOrNull()
            if (decoded != null) {
                val startUs = decoded.startTimeUs.takeIf { it != C.TIME_UNSET } ?: timeUs
                val endUs = when {
                    decoded.endTimeUs != C.TIME_UNSET -> decoded.endTimeUs
                    decoded.durationUs != C.TIME_UNSET -> startUs + decoded.durationUs
                    else -> C.TIME_UNSET
                }
                val text = decoded.cues.mapNotNull { it.text?.toString() }.joinToString("\n")
                record(startUs, endUs, text)
                return
            }
        }
        record(timeUs, C.TIME_UNSET, "")
    }

    private fun record(startUs: Long, endUs: Long, text: String) {
        val startMs = startUs / 1_000L
        val endMs = if (endUs == C.TIME_UNSET || endUs <= startUs) startMs + 5_000L else max(startMs + 1, endUs / 1_000L)
        val currentFormat = format
        EmbeddedSubtitleCueStore.record(
            sourceKey = sourceKey,
            trackKey = "media3:$trackId",
            language = currentFormat?.language,
            label = currentFormat?.label,
            selectionFlags = currentFormat?.selectionFlags ?: 0,
            roleFlags = currentFormat?.roleFlags ?: 0,
            cue = SubtitleSyncCue(startTimeMs = startMs, endTimeMs = endMs, text = text),
        )
    }

    private fun append(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val retained = end - start
        val required = retained + length
        if (buffered.size - end < length) {
            buffered.copyInto(buffered, 0, start, end)
            start = 0
            end = retained
            if (buffered.size < required) buffered = buffered.copyOf(max(required, max(buffered.size * 2, 256)))
        }
        data.copyInto(buffered, end, offset, offset + length)
        end += length
    }
}
