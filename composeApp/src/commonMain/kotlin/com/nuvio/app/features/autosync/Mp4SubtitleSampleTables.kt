package com.nuvio.app.features.autosync

/** Sample codec of an indexed MP4 subtitle track. */
internal enum class Mp4SubtitleCodec {
    /** 3GPP timed text (`tx3g`); an empty cue sample is 2 bytes. */
    TX3G,

    /** WebVTT in MP4 (`wvtt`); an empty cue sample is an 8-byte `vtte` box. */
    WVTT,
}

/** Sample timing of one MP4 subtitle track, after its edit list is applied. */
internal class Mp4SubtitleSampleTable(
    val trackId: Int,
    val codec: Mp4SubtitleCodec,
    /** ISO 639-2/T code from `mdhd`, as stored (for example "eng"), or null. */
    val language: String?,
    val timestampsUs: LongArray,
    val sizes: IntArray,
    val durationUs: Long,
) {
    val sampleCount: Int get() = timestampsUs.size
}

/** Text tracks of one `moov`. [hasTextTracks] is true even when none of them can be indexed. */
internal class Mp4SubtitleIndex(
    val hasTextTracks: Boolean,
    val tables: List<Mp4SubtitleSampleTable>,
)

/**
 * Reads subtitle sample tables from an MP4 `moov` box without Media3, so every platform can index
 * MP4 subtitles. It follows Media3's BoxParser (1.8.0) for text tracks: the same sample timing,
 * ctts offsets, sync samples, truncated chunk data and edit lists, and the same rounding.
 */
internal object Mp4SubtitleSampleTables {
    private const val HEADER_SIZE = 8
    private const val FULL_HEADER_SIZE = 12
    private const val INDEX_UNSET = -1

    private const val TYPE_moov = 0x6D6F6F76
    private const val TYPE_mvhd = 0x6D766864
    private const val TYPE_mvex = 0x6D766578
    private const val TYPE_trak = 0x7472616B
    private const val TYPE_tkhd = 0x746B6864
    private const val TYPE_edts = 0x65647473
    private const val TYPE_elst = 0x656C7374
    private const val TYPE_mdia = 0x6D646961
    private const val TYPE_mdhd = 0x6D646864
    private const val TYPE_hdlr = 0x68646C72
    private const val TYPE_minf = 0x6D696E66
    private const val TYPE_stbl = 0x7374626C
    private const val TYPE_stsd = 0x73747364
    private const val TYPE_stts = 0x73747473
    private const val TYPE_ctts = 0x63747473
    private const val TYPE_stss = 0x73747373
    private const val TYPE_stsc = 0x73747363
    private const val TYPE_stsz = 0x7374737A
    private const val TYPE_stz2 = 0x73747A32
    private const val TYPE_stco = 0x7374636F
    private const val TYPE_co64 = 0x636F3634

    // Text sample entries Media3 recognises; only tx3g and wvtt can be indexed.
    private const val TYPE_TTML = 0x54544D4C
    private const val TYPE_tx3g = 0x74783367
    private const val TYPE_wvtt = 0x77767474
    private const val TYPE_stpp = 0x73747070
    private const val TYPE_c608 = 0x63363038
    private const val TYPE_mp4s = 0x6D703473

    // Handler types of text tracks.
    private const val HANDLER_text = 0x74657874
    private const val HANDLER_sbtl = 0x7362746C
    private const val HANDLER_subt = 0x73756274
    private const val HANDLER_clcp = 0x636C6370
    private const val HANDLER_subp = 0x73756270

    private const val MICROS_PER_SECOND = 1_000_000L

    /**
     * Parses the text tracks of [moov], a complete `moov` box. Returns null when the box or one of
     * its text tracks is malformed, or when the file is fragmented (`mvex`).
     */
    fun parse(moov: ByteArray): Mp4SubtitleIndex? = try {
        parseOrThrow(moov)
    } catch (_: Exception) {
        null
    }

    private class Mp4ParseException(message: String) : Exception(message)

    private class Box(val type: Int, val start: Int, val end: Int, val headerSize: Int) {
        val dataStart: Int get() = start + headerSize
    }

    private fun parseOrThrow(bytes: ByteArray): Mp4SubtitleIndex {
        val root = readBoxAt(bytes, 0, bytes.size) ?: throw Mp4ParseException("no moov")
        if (root.type != TYPE_moov || root.end != bytes.size) throw Mp4ParseException("not moov")
        val children = children(bytes, root)
        if (children.any { it.type == TYPE_mvex }) throw Mp4ParseException("fragmented")
        val mvhd = children.firstOrNull { it.type == TYPE_mvhd }

        val tables = ArrayList<Mp4SubtitleSampleTable>()
        var hasTextTracks = false
        for (trak in children) {
            if (trak.type != TYPE_trak) continue
            // Tracks whose handler cannot be read are not text tracks; skip them like other media.
            val mdia = textTrackMdia(bytes, trak) ?: continue
            hasTextTracks = true
            if (mvhd == null) throw Mp4ParseException("no mvhd")
            tables += parseTextTrack(bytes, trak, mdia, mvhd) ?: continue
        }
        return Mp4SubtitleIndex(hasTextTracks, tables)
    }

    /** The `mdia` of [trak] when its handler is a text handler, otherwise null. */
    private fun textTrackMdia(bytes: ByteArray, trak: Box): Box? = try {
        val mdia = child(bytes, trak, TYPE_mdia)
        val hdlr = mdia?.let { child(bytes, it, TYPE_hdlr) }
        if (mdia == null || hdlr == null) {
            null
        } else {
            val handler = Reader(bytes, hdlr).apply { position = FULL_HEADER_SIZE + 4 }.readInt()
            mdia.takeIf { isTextHandler(handler) }
        }
    } catch (_: Exception) {
        null
    }

    private fun isTextHandler(handler: Int): Boolean =
        handler == HANDLER_text || handler == HANDLER_sbtl || handler == HANDLER_subt ||
            handler == HANDLER_clcp || handler == HANDLER_subp

    private fun parseTextTrack(bytes: ByteArray, trak: Box, mdia: Box, mvhd: Box): Mp4SubtitleSampleTable? {
        val trackId = parseTkhdTrackId(Reader(bytes, child(bytes, trak, TYPE_tkhd) ?: throw Mp4ParseException("no tkhd")))
        val movieTimescale = parseMvhdTimescale(Reader(bytes, mvhd))
        val minf = child(bytes, mdia, TYPE_minf) ?: throw Mp4ParseException("no minf")
        val stbl = child(bytes, minf, TYPE_stbl) ?: throw Mp4ParseException("no stbl")
        val mdhd = parseMdhd(Reader(bytes, child(bytes, mdia, TYPE_mdhd) ?: throw Mp4ParseException("no mdhd")))
        val stsd = child(bytes, stbl, TYPE_stsd) ?: throw Mp4ParseException("no stsd")
        val codec = parseStsdCodec(Reader(bytes, stsd))
        val edits = child(bytes, trak, TYPE_edts)?.let { edts ->
            child(bytes, edts, TYPE_elst)?.let { parseElst(Reader(bytes, it)) }
        }
        // Media3 keeps the last recognised text entry; only tx3g and wvtt are indexed.
        codec ?: return null
        return parseStbl(bytes, stbl, trackId, codec, mdhd, movieTimescale, edits)
    }

    private class Mdhd(val timescale: Long, val language: String?)

    private class EditList(val durations: LongArray, val mediaTimes: LongArray)

    private fun parseTkhdTrackId(tkhd: Reader): Int {
        tkhd.position = HEADER_SIZE
        val version = fullBoxVersion(tkhd.readInt())
        tkhd.skip(if (version == 0) 8 else 16)
        return tkhd.readInt()
    }

    private fun parseMvhdTimescale(mvhd: Reader): Long {
        mvhd.position = HEADER_SIZE
        val version = fullBoxVersion(mvhd.readInt())
        mvhd.skip(if (version == 0) 8 else 16)
        return mvhd.readUnsignedInt()
    }

    private fun parseMdhd(mdhd: Reader): Mdhd {
        mdhd.position = HEADER_SIZE
        val version = fullBoxVersion(mdhd.readInt())
        mdhd.skip(if (version == 0) 8 else 16)
        val timescale = mdhd.readUnsignedInt()
        mdhd.skip(if (version == 0) 4 else 8)
        val code = mdhd.readUnsignedShort()
        val chars = charArrayOf(
            (((code shr 10) and 0x1F) + 0x60).toChar(),
            (((code shr 5) and 0x1F) + 0x60).toChar(),
            ((code and 0x1F) + 0x60).toChar(),
        )
        val language = if (chars.all { it in 'a'..'z' }) chars.concatToString() else null
        return Mdhd(timescale, language)
    }

    /** The codec of the last text sample entry, or null when it cannot be indexed. */
    private fun parseStsdCodec(stsd: Reader): Mp4SubtitleCodec? {
        stsd.position = FULL_HEADER_SIZE
        val entryCount = stsd.readInt()
        var lastTextType = 0
        for (i in 0 until entryCount) {
            val entryStart = stsd.position
            val entrySize = stsd.readInt()
            if (entrySize <= 0) throw Mp4ParseException("childAtomSize must be positive")
            val entryType = stsd.readInt()
            if (entryType == TYPE_TTML || entryType == TYPE_tx3g || entryType == TYPE_wvtt ||
                entryType == TYPE_stpp || entryType == TYPE_c608 || entryType == TYPE_mp4s
            ) {
                lastTextType = entryType
            }
            stsd.position = entryStart + entrySize
        }
        return when (lastTextType) {
            TYPE_tx3g -> Mp4SubtitleCodec.TX3G
            TYPE_wvtt -> Mp4SubtitleCodec.WVTT
            else -> null
        }
    }

    private fun parseElst(elst: Reader): EditList {
        elst.position = HEADER_SIZE
        val version = fullBoxVersion(elst.readInt())
        val entryCount = elst.readUnsignedIntToInt()
        val durations = LongArray(entryCount)
        val mediaTimes = LongArray(entryCount)
        for (i in 0 until entryCount) {
            durations[i] = if (version == 1) elst.readUnsignedLongToLong() else elst.readUnsignedInt()
            mediaTimes[i] = if (version == 1) elst.readLong() else elst.readInt().toLong()
            val mediaRateInteger = elst.readShort()
            // Media3 does not handle dwell edits and rejects the whole file.
            require(mediaRateInteger == 1) { "Unsupported media rate." }
            elst.skip(2)
        }
        return EditList(durations, mediaTimes)
    }

    private fun parseStbl(
        bytes: ByteArray,
        stbl: Box,
        trackId: Int,
        codec: Mp4SubtitleCodec,
        mdhd: Mdhd,
        movieTimescale: Long,
        edits: EditList?,
    ): Mp4SubtitleSampleTable {
        val sampleSizes = child(bytes, stbl, TYPE_stsz)?.let { StszSizes(Reader(bytes, it)) }
            ?: child(bytes, stbl, TYPE_stz2)?.let { Stz2Sizes(Reader(bytes, it)) }
            ?: throw Mp4ParseException("Track has no sample table size information")
        var sampleCount = sampleSizes.sampleCount
        if (sampleCount == 0) {
            return Mp4SubtitleSampleTable(trackId, codec, mdhd.language, LongArray(0), IntArray(0), 0L)
        }

        val stcoBox = child(bytes, stbl, TYPE_stco)
        val chunkOffsetsAreLongs = stcoBox == null
        val chunkOffsets = Reader(bytes, stcoBox ?: child(bytes, stbl, TYPE_co64) ?: throw Mp4ParseException("no stco"))
        val stsc = Reader(bytes, child(bytes, stbl, TYPE_stsc) ?: throw Mp4ParseException("no stsc"))
        val stts = Reader(bytes, child(bytes, stbl, TYPE_stts) ?: throw Mp4ParseException("no stts"))
        var stss = child(bytes, stbl, TYPE_stss)?.let { Reader(bytes, it) }
        val ctts = child(bytes, stbl, TYPE_ctts)?.let { Reader(bytes, it) }

        val chunkIterator = ChunkIterator(stsc, chunkOffsets, chunkOffsetsAreLongs)

        stts.position = FULL_HEADER_SIZE
        var remainingTimestampDeltaChanges = stts.readUnsignedIntToInt() - 1
        var remainingSamplesAtTimestampDelta = stts.readUnsignedIntToInt()
        var timestampDeltaInTimeUnits = stts.readUnsignedIntToInt()

        var remainingSamplesAtTimestampOffset = 0
        var remainingTimestampOffsetChanges = 0
        var timestampOffset = 0
        if (ctts != null) {
            ctts.position = FULL_HEADER_SIZE
            remainingTimestampOffsetChanges = ctts.readUnsignedIntToInt()
        }

        var nextSynchronizationSampleIndex = INDEX_UNSET
        var remainingSynchronizationSamples = 0
        if (stss != null) {
            stss.position = FULL_HEADER_SIZE
            remainingSynchronizationSamples = stss.readUnsignedIntToInt()
            if (remainingSynchronizationSamples > 0) {
                nextSynchronizationSampleIndex = stss.readUnsignedIntToInt() - 1
            } else {
                // An empty stss makes every sample a sync sample.
                stss = null
            }
        }

        var sizes = IntArray(sampleCount)
        var timestamps = LongArray(sampleCount)
        var keyFrames = BooleanArray(sampleCount)
        var timestampTimeUnits = 0L
        var remainingSamplesInChunk = 0

        for (i in 0 until sampleCount) {
            var chunkDataComplete = true
            while (remainingSamplesInChunk == 0) {
                chunkDataComplete = chunkIterator.moveNext()
                if (!chunkDataComplete) break
                remainingSamplesInChunk = chunkIterator.numSamples
            }
            if (!chunkDataComplete) {
                // Unexpected end of chunk data: keep the samples read so far.
                sampleCount = i
                sizes = sizes.copyOf(sampleCount)
                timestamps = timestamps.copyOf(sampleCount)
                keyFrames = keyFrames.copyOf(sampleCount)
                break
            }

            if (ctts != null) {
                while (remainingSamplesAtTimestampOffset == 0 && remainingTimestampOffsetChanges > 0) {
                    remainingSamplesAtTimestampOffset = ctts.readUnsignedIntToInt()
                    // Read as signed, like Media3: some streams store negative offsets in version 0.
                    timestampOffset = ctts.readInt()
                    remainingTimestampOffsetChanges--
                }
                remainingSamplesAtTimestampOffset--
            }

            sizes[i] = sampleSizes.readNextSampleSize()
            timestamps[i] = timestampTimeUnits + timestampOffset

            keyFrames[i] = stss == null
            if (i == nextSynchronizationSampleIndex) {
                keyFrames[i] = true
                remainingSynchronizationSamples--
                if (remainingSynchronizationSamples > 0) {
                    nextSynchronizationSampleIndex = stss!!.readUnsignedIntToInt() - 1
                }
            }

            timestampTimeUnits += timestampDeltaInTimeUnits
            remainingSamplesAtTimestampDelta--
            if (remainingSamplesAtTimestampDelta == 0 && remainingTimestampDeltaChanges > 0) {
                remainingSamplesAtTimestampDelta = stts.readUnsignedIntToInt()
                // Read as signed, like Media3: some streams store negative deltas.
                timestampDeltaInTimeUnits = stts.readInt()
                remainingTimestampDeltaChanges--
            }
            remainingSamplesInChunk--
        }
        val duration = timestampTimeUnits + timestampOffset
        val timescale = mdhd.timescale
        var durationUs = scaleLargeTimestamp(duration, MICROS_PER_SECOND, timescale)

        if (edits == null) {
            for (i in timestamps.indices) {
                timestamps[i] = scaleLargeTimestamp(timestamps[i], MICROS_PER_SECOND, timescale)
            }
            return Mp4SubtitleSampleTable(trackId, codec, mdhd.language, timestamps, sizes, durationUs)
        }

        if (edits.durations.size == 1 && edits.durations[0] == 0L) {
            // An edit with zero duration includes every sample, shifted by its media time.
            val editStartTime = edits.mediaTimes[0]
            for (i in timestamps.indices) {
                timestamps[i] = scaleLargeTimestamp(timestamps[i] - editStartTime, MICROS_PER_SECOND, timescale)
            }
            durationUs = scaleLargeTimestamp(duration - editStartTime, MICROS_PER_SECOND, timescale)
            return Mp4SubtitleSampleTable(trackId, codec, mdhd.language, timestamps, sizes, durationUs)
        }

        val editCount = edits.durations.size
        var editedSampleCount = 0
        val startIndices = IntArray(editCount)
        val endIndices = IntArray(editCount)
        for (i in 0 until editCount) {
            val editMediaTime = edits.mediaTimes[i]
            if (editMediaTime != -1L) {
                val editDuration = scaleLargeTimestamp(edits.durations[i], timescale, movieTimescale)
                startIndices[i] = binarySearchFloor(timestamps, editMediaTime, inclusive = true, stayInBounds = true)
                // Text keeps a sample that starts exactly at the end of an edit.
                endIndices[i] = binarySearchCeil(timestamps, editMediaTime + editDuration, inclusive = false, stayInBounds = false)

                val initialStartIndex = startIndices[i]
                while (startIndices[i] >= 0 && !keyFrames[startIndices[i]]) {
                    startIndices[i]--
                }
                if (startIndices[i] < 0) {
                    startIndices[i] = initialStartIndex
                    while (startIndices[i] < endIndices[i] && !keyFrames[startIndices[i]]) {
                        startIndices[i]++
                    }
                }
                editedSampleCount += endIndices[i] - startIndices[i]
            }
        }

        val editedSizes = IntArray(editedSampleCount)
        val editedTimestamps = LongArray(editedSampleCount)
        var pts = 0L
        var sampleIndex = 0
        for (i in 0 until editCount) {
            val editMediaTime = edits.mediaTimes[i]
            // An empty edit (media time -1) keeps start = end = 0, so it only advances pts.
            for (j in startIndices[i] until endIndices[i]) {
                val ptsUs = scaleLargeTimestamp(pts, MICROS_PER_SECOND, movieTimescale)
                val timeInSegmentUs = scaleLargeTimestamp(timestamps[j] - editMediaTime, MICROS_PER_SECOND, timescale)
                editedTimestamps[sampleIndex] = ptsUs + timeInSegmentUs
                editedSizes[sampleIndex] = sizes[j]
                sampleIndex++
            }
            pts += edits.durations[i]
        }
        val editedDurationUs = scaleLargeTimestamp(pts, MICROS_PER_SECOND, movieTimescale)
        return Mp4SubtitleSampleTable(trackId, codec, mdhd.language, editedTimestamps, editedSizes, editedDurationUs)
    }

    private interface SampleSizes {
        val sampleCount: Int
        fun readNextSampleSize(): Int
    }

    private class StszSizes(private val data: Reader) : SampleSizes {
        private val fixedSampleSize: Int
        override val sampleCount: Int

        init {
            data.position = FULL_HEADER_SIZE
            fixedSampleSize = data.readUnsignedIntToInt()
            sampleCount = data.readUnsignedIntToInt()
        }

        override fun readNextSampleSize(): Int =
            if (fixedSampleSize == 0) data.readUnsignedIntToInt() else fixedSampleSize
    }

    private class Stz2Sizes(private val data: Reader) : SampleSizes {
        private val fieldSize: Int
        override val sampleCount: Int
        private var sampleIndex = 0
        private var currentByte = 0

        init {
            data.position = FULL_HEADER_SIZE
            fieldSize = data.readUnsignedIntToInt() and 0xFF
            sampleCount = data.readUnsignedIntToInt()
        }

        override fun readNextSampleSize(): Int = when (fieldSize) {
            8 -> data.readUnsignedByte()
            16 -> data.readUnsignedShort()
            else -> if (sampleIndex++ % 2 == 0) {
                currentByte = data.readUnsignedByte()
                (currentByte and 0xF0) shr 4
            } else {
                currentByte and 0x0F
            }
        }
    }

    private class ChunkIterator(
        private val stsc: Reader,
        private val chunkOffsets: Reader,
        private val chunkOffsetsAreLongs: Boolean,
    ) {
        val length: Int
        var index: Int = -1
        var numSamples: Int = 0
        private var nextSamplesPerChunkChangeIndex: Int
        private var remainingSamplesPerChunkChanges: Int

        init {
            chunkOffsets.position = FULL_HEADER_SIZE
            length = chunkOffsets.readUnsignedIntToInt()
            stsc.position = FULL_HEADER_SIZE
            remainingSamplesPerChunkChanges = stsc.readUnsignedIntToInt()
            if (stsc.readInt() != 1) throw Mp4ParseException("first_chunk must be 1")
            nextSamplesPerChunkChangeIndex = 0
        }

        fun moveNext(): Boolean {
            if (++index == length) return false
            if (chunkOffsetsAreLongs) chunkOffsets.readUnsignedLongToLong() else chunkOffsets.readUnsignedInt()
            if (index == nextSamplesPerChunkChangeIndex) {
                numSamples = stsc.readUnsignedIntToInt()
                stsc.skip(4) // sample_description_index
                nextSamplesPerChunkChangeIndex =
                    if (--remainingSamplesPerChunkChanges > 0) stsc.readUnsignedIntToInt() - 1 else INDEX_UNSET
            }
            return true
        }
    }

    /** Big-endian reader over one box; [position] is relative to the box start, like Media3's leaf data. */
    private class Reader(private val bytes: ByteArray, box: Box) {
        private val start = box.start
        private val end = box.end
        var position: Int = 0

        private fun take(count: Int): Int {
            val at = start + position
            if (count < 0 || at < start || at + count > end) throw IndexOutOfBoundsException("box overrun")
            position += count
            return at
        }

        fun skip(count: Int) {
            take(count)
        }

        fun readUnsignedByte(): Int = bytes[take(1)].toInt() and 0xFF

        fun readUnsignedShort(): Int {
            val at = take(2)
            return ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
        }

        fun readShort(): Int = readUnsignedShort().toShort().toInt()

        fun readInt(): Int {
            val at = take(4)
            return ((bytes[at].toInt() and 0xFF) shl 24) or
                ((bytes[at + 1].toInt() and 0xFF) shl 16) or
                ((bytes[at + 2].toInt() and 0xFF) shl 8) or
                (bytes[at + 3].toInt() and 0xFF)
        }

        fun readUnsignedInt(): Long = readInt().toLong() and 0xFFFFFFFFL

        fun readUnsignedIntToInt(): Int {
            val value = readInt()
            check(value >= 0) { "Top bit not zero: $value" }
            return value
        }

        fun readLong(): Long = (readUnsignedInt() shl 32) or readUnsignedInt()

        fun readUnsignedLongToLong(): Long {
            val value = readLong()
            check(value >= 0) { "Top bit not zero: $value" }
            return value
        }
    }

    private fun fullBoxVersion(fullBoxInt: Int): Int = (fullBoxInt ushr 24) and 0xFF

    /** Reads the box header at [offset], or null when it does not fit before [limit]. */
    private fun readBoxAt(bytes: ByteArray, offset: Int, limit: Int): Box? {
        if (offset + HEADER_SIZE > limit) return null
        val size32 = readUInt32(bytes, offset)
        val type = readUInt32(bytes, offset + 4).toInt()
        val (size, headerSize) = when (size32) {
            0L -> (limit - offset).toLong() to HEADER_SIZE
            1L -> {
                if (offset + 16 > limit) return null
                val high = readUInt32(bytes, offset + 8)
                val low = readUInt32(bytes, offset + 12)
                ((high shl 32) or low) to 16
            }
            else -> size32 to HEADER_SIZE
        }
        if (size < headerSize || offset + size > limit) return null
        return Box(type, offset, (offset + size).toInt(), headerSize)
    }

    private fun children(bytes: ByteArray, parent: Box): List<Box> {
        val result = ArrayList<Box>()
        var position = parent.dataStart
        while (position < parent.end) {
            val box = readBoxAt(bytes, position, parent.end) ?: throw Mp4ParseException("bad child box")
            result += box
            position = box.end
        }
        return result
    }

    private fun child(bytes: ByteArray, parent: Box, type: Int): Box? =
        children(bytes, parent).firstOrNull { it.type == type }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    // Media3 Util.scaleLargeTimestamp: RoundingMode.DOWN (truncate toward zero).
    internal fun scaleLargeTimestamp(value: Long, multiplier: Long, divisor: Long): Long {
        if (value == 0L || multiplier == 0L) return 0L
        return when {
            divisor >= multiplier && divisor % multiplier == 0L -> value / (divisor / multiplier)
            divisor < multiplier && multiplier % divisor == 0L -> saturatedMultiply(value, multiplier / divisor)
            divisor >= value && value != 0L && divisor % value == 0L -> multiplier / (divisor / value)
            divisor < value && value % divisor == 0L -> saturatedMultiply(multiplier, value / divisor)
            else -> scaleFallback(value, multiplier, divisor)
        }
    }

    private fun scaleFallback(value: Long, multiplier: Long, divisor: Long): Long {
        val numerator = saturatedMultiply(value, multiplier)
        if (numerator != Long.MAX_VALUE && numerator != Long.MIN_VALUE) return numerator / divisor
        val gcdMd = gcd(kotlin.math.abs(multiplier), kotlin.math.abs(divisor))
        val simplifiedMultiplier = multiplier / gcdMd
        var simplifiedDivisor = divisor / gcdMd
        val gcdVd = gcd(kotlin.math.abs(value), kotlin.math.abs(simplifiedDivisor))
        val simplifiedValue = value / gcdVd
        simplifiedDivisor /= gcdVd
        val simplifiedNumerator = saturatedMultiply(simplifiedValue, simplifiedMultiplier)
        if (simplifiedNumerator != Long.MAX_VALUE && simplifiedNumerator != Long.MIN_VALUE) {
            return simplifiedNumerator / simplifiedDivisor
        }
        val factor = simplifiedMultiplier.toDouble() / simplifiedDivisor
        return (simplifiedValue * factor).toLong()
    }

    private fun gcd(a: Long, b: Long): Long {
        var x = a
        var y = b
        while (y != 0L) {
            val t = x % y
            x = y
            y = t
        }
        return if (x == 0L) 1L else x
    }

    private fun saturatedMultiply(a: Long, b: Long): Long {
        if (a == 0L || b == 0L) return 0L
        val result = a * b
        if (result / b != a || (a == -1L && b == Long.MIN_VALUE) || (b == -1L && a == Long.MIN_VALUE)) {
            return if ((a < 0) == (b < 0)) Long.MAX_VALUE else Long.MIN_VALUE
        }
        return result
    }

    /** java.util.Arrays.binarySearch: the index of [value], or -(insertion point) - 1. */
    private fun binarySearch(array: LongArray, value: Long): Int {
        var low = 0
        var high = array.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val midValue = array[mid]
            when {
                midValue < value -> low = mid + 1
                midValue > value -> high = mid - 1
                else -> return mid
            }
        }
        return -(low + 1)
    }

    // Media3 Util.binarySearchFloor / binarySearchCeil over a long array.
    private fun binarySearchFloor(array: LongArray, value: Long, inclusive: Boolean, stayInBounds: Boolean): Int {
        var index = binarySearch(array, value)
        if (index < 0) {
            index = -(index + 2)
        } else {
            while (--index >= 0 && array[index] == value) Unit
            if (inclusive) index++
        }
        return if (stayInBounds) maxOf(0, index) else index
    }

    private fun binarySearchCeil(array: LongArray, value: Long, inclusive: Boolean, stayInBounds: Boolean): Int {
        var index = binarySearch(array, value)
        if (index < 0) {
            index = index.inv()
        } else {
            while (++index < array.size && array[index] == value) Unit
            if (inclusive) index--
        }
        return if (stayInBounds) minOf(array.size - 1, index) else index
    }
}
