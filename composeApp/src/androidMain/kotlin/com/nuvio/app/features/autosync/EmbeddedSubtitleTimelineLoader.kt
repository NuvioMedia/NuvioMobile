package com.nuvio.app.features.autosync

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4Box
import androidx.media3.extractor.GaplessInfoHolder
import androidx.media3.extractor.mp4.BoxParser
import androidx.media3.extractor.mp4.TrackSampleTable
import com.nuvio.app.features.player.SubtitleSyncCue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Player-independent embedded subtitle timeline loader.
 *
 * For seekable Matroska/WebM HTTP sources this reads the container's EBML metadata and Cues index
 * with HTTP range requests. Matroska recommends indexing every subtitle frame in Cues, so a normal
 * remux can expose the whole embedded subtitle timing timeline without playing or seeking ExoPlayer.
 *
 * This is deliberately best-effort. If range requests, Tracks, or subtitle Cues are unavailable,
 * AutoSync falls back to the existing live Media3 observation path.
 */
@OptIn(UnstableApi::class)
internal object EmbeddedSubtitleTimelineLoader {
    private const val TOTAL_TIMEOUT_MS = 7_000L
    private const val INITIAL_PROBE_BYTES = 512 * 1024
    private const val HEADER_PROBE_BYTES = 64
    private const val TAIL_PROBE_BYTES = 4 * 1024 * 1024
    private const val MAX_SEEK_HEAD_BYTES = 2 * 1024 * 1024
    private const val MAX_INFO_BYTES = 512 * 1024
    private const val MAX_TRACKS_BYTES = 4 * 1024 * 1024
    private const val MAX_CUES_BYTES = 8 * 1024 * 1024
    private const val MAX_TOTAL_DOWNLOAD_BYTES = 24L * 1024L * 1024L
    private const val MAX_RANGE_REQUESTS = 16
    private const val MAX_SEEK_HEAD_HOPS = 4
    private const val DEFAULT_TIMESTAMP_SCALE_NS = 1_000_000L
    private const val DEFAULT_CUE_DURATION_MS = 5_000L
    private const val LAST_MKV_CUE_ESTIMATED_DURATION_MS = 2_000L
    private const val MAX_MKV_INTER_CUE_ESTIMATED_DURATION_MS = 4_000L
    private const val MATROSKA_PGS_CODEC_ID = "S_HDMV/PGS"
    private const val MIN_INDEXED_CUES = 8
    private const val MIN_INDEXED_SPAN_MS = 30_000L
    private const val MAX_CACHE_ENTRIES = 2
    private const val NEGATIVE_CACHE_TTL_MS = 120_000L
    private const val MAX_MP4_MOOV_BYTES = 24 * 1024 * 1024
    private const val MAX_MP4_TOP_LEVEL_BOXES = 64
    private const val MP4_BOX_HEADER_BYTES = 16

    // Top-level Matroska/EBML IDs.
    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEK_HEAD = 0x114D9B74L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_CUES = 0x1C53BB6BL
    private const val ID_CLUSTER = 0x1F43B675L

    // SeekHead.
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEK_ID = 0x53ABL
    private const val ID_SEEK_POSITION = 0x53ACL

    // Info.
    private const val ID_TIMESTAMP_SCALE = 0x2AD7B1L

    // Tracks.
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_FLAG_DEFAULT = 0x88L
    private const val ID_FLAG_FORCED = 0x55AAL
    private const val ID_FLAG_HEARING_IMPAIRED = 0x55ABL
    private const val ID_FLAG_VISUAL_IMPAIRED = 0x55ACL
    private const val ID_FLAG_TEXT_DESCRIPTIONS = 0x55ADL
    private const val ID_FLAG_COMMENTARY = 0x55AFL
    private const val ID_NAME = 0x536EL
    private const val ID_LANGUAGE = 0x22B59CL
    private const val ID_LANGUAGE_IETF = 0x22B59DL
    private const val ID_CODEC_ID = 0x86L
    private const val TRACK_TYPE_SUBTITLE = 17L

    // Cues.
    private const val ID_CUE_POINT = 0xBBL
    private const val ID_CUE_TIME = 0xB3L
    private const val ID_CUE_TRACK_POSITIONS = 0xB7L
    private const val ID_CUE_TRACK = 0xF7L
    private const val ID_CUE_DURATION = 0xB2L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, CachedLoadResult>(
        MAX_CACHE_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, CachedLoadResult>?,
        ): Boolean = size > MAX_CACHE_ENTRIES
    }
    private val inFlight = mutableMapOf<String, CompletableDeferred<IndexedEmbeddedTimeline?>>()

    /**
     * Starts loading the index in [scope] so a later [load] finds it cached or joins the
     * in-flight download instead of starting from zero when AutoSync runs.
     */
    fun prefetch(
        scope: CoroutineScope,
        sourceUrl: String,
        sourceHeaders: Map<String, String> = emptyMap(),
    ) {
        scope.launch { load(sourceUrl, sourceHeaders) }
    }

    suspend fun load(
        sourceUrl: String,
        sourceHeaders: Map<String, String> = emptyMap(),
    ): IndexedEmbeddedTimeline? {
        if (!sourceUrl.startsWith("http://", ignoreCase = true) &&
            !sourceUrl.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }

        val cacheKey = "$sourceUrl#${sourceHeaders.hashCode()}"
        val nowNs = System.nanoTime()
        val ownedLoad = CompletableDeferred<IndexedEmbeddedTimeline?>()
        val activeLoad = synchronized(cacheLock) {
            val cached = cache[cacheKey]
            if (cached != null) {
                if (cached.timeline != null) return cached.timeline
                val ageMs = (nowNs - cached.createdAtNs).coerceAtLeast(0L) / 1_000_000L
                if (ageMs < NEGATIVE_CACHE_TTL_MS) return null
                cache.remove(cacheKey)
            }
            inFlight.getOrPut(cacheKey) { ownedLoad }
        }

        if (activeLoad !== ownedLoad) {
            return try {
                activeLoad.await()
            } catch (cancel: CancellationException) {
                // The owning load was cancelled (e.g. its player closed). Load for this caller
                // unless this caller itself is the one being cancelled.
                currentCoroutineContext().ensureActive()
                load(sourceUrl, sourceHeaders)
            }
        }

        try {
            return loadAndCache(cacheKey, sourceUrl, sourceHeaders)
                .also(ownedLoad::complete)
        } finally {
            ownedLoad.cancel()
            synchronized(cacheLock) {
                if (inFlight[cacheKey] === ownedLoad) inFlight.remove(cacheKey)
            }
        }
    }

    private suspend fun loadAndCache(
        cacheKey: String,
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
    ): IndexedEmbeddedTimeline? {
        return try {
            val loaded = try {
                withTimeout(TOTAL_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        loadMatroskaCueIndex(sourceUrl, sourceHeaders)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                // A transient deadline is not evidence that the container is unsupported.
                // Do not publish a negative cache entry for timed-out work.
                return null
            }

            synchronized(cacheLock) {
                cache[cacheKey] = CachedLoadResult(
                    timeline = loaded,
                    createdAtNs = System.nanoTime(),
                )
            }
            loaded
        } catch (cancel: CancellationException) {
            // External cancellation must remain observable and must never publish cache state.
            throw cancel
        } catch (_: Exception) {
            synchronized(cacheLock) {
                cache[cacheKey] = CachedLoadResult(
                    timeline = null,
                    createdAtNs = System.nanoTime(),
                )
            }
            null
        }
    }

    private suspend fun loadMatroskaCueIndex(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
    ): IndexedEmbeddedTimeline? {
        val startedAtNs = System.nanoTime()
        val stats = RangeStats(
            deadlineNs = System.nanoTime() + TOTAL_TIMEOUT_MS * 1_000_000L,
            maxBytes = MAX_TOTAL_DOWNLOAD_BYTES,
            maxRequests = MAX_RANGE_REQUESTS,
        )
        val initial = fetchRange(
            sourceUrl = sourceUrl,
            sourceHeaders = sourceHeaders,
            start = 0L,
            length = INITIAL_PROBE_BYTES,
            requirePartialContent = false,
            stats = stats,
        ) ?: return null

        val segment = findSegment(initial.bytes)
        if (segment == null) {
            return loadMp4SampleTableIndex(
                sourceUrl = sourceUrl,
                sourceHeaders = sourceHeaders,
                initial = initial,
                stats = stats,
                startedAtNs = startedAtNs,
            )
        }

        val initialMetadata = InitialMetadata(
            segmentDataStart = segment.dataStart.toLong(),
            directPositions = findInitialTopLevelPositions(
                bytes = initial.bytes,
                segment = segment,
            ),
            totalLength = initial.totalLength,
            initialBytes = initial.bytes,
        )
        val segmentDataStart = initialMetadata.segmentDataStart
        val directPositions = initialMetadata.directPositions

        // Resolve SeekHead chains. SeekPosition is relative to Segment payload start.
        val resolvedPositions = mutableMapOf<Long, Long>()
        directPositions.forEach { (id, position) -> resolvedPositions.putIfAbsent(id, position) }

        val seekHeadQueue = ArrayDeque<Long>()
        directPositions[ID_SEEK_HEAD]?.let(seekHeadQueue::addLast)
        val visitedSeekHeads = mutableSetOf<Long>()
        var seekHeadHops = 0

        while (seekHeadQueue.isNotEmpty() && seekHeadHops < MAX_SEEK_HEAD_HOPS) {
            val seekHeadPosition = seekHeadQueue.removeFirst()
            if (!visitedSeekHeads.add(seekHeadPosition)) continue
            seekHeadHops++

            val seekHeadBytes = extractElementFromInitialProbe(
                initialBytes = initialMetadata.initialBytes,
                absolutePosition = seekHeadPosition,
                expectedId = ID_SEEK_HEAD,
                maxElementBytes = MAX_SEEK_HEAD_BYTES,
            ) ?: fetchElementAt(
                sourceUrl = sourceUrl,
                sourceHeaders = sourceHeaders,
                absolutePosition = seekHeadPosition,
                expectedId = ID_SEEK_HEAD,
                maxElementBytes = MAX_SEEK_HEAD_BYTES,
                stats = stats,
            ) ?: continue

            parseSeekHead(seekHeadBytes).forEach { (id, relativePosition) ->
                val absolute = segmentDataStart + relativePosition
                if (resolvedPositions.putIfAbsent(id, absolute) == null && id == ID_SEEK_HEAD) {
                    seekHeadQueue.addLast(absolute)
                } else if (id == ID_SEEK_HEAD && absolute !in visitedSeekHeads) {
                    seekHeadQueue.addLast(absolute)
                }
            }
        }

        val infoPosition = resolvedPositions[ID_INFO] ?: directPositions[ID_INFO]
        val timestampScaleNs = infoPosition
            ?.let { position ->
                extractElementFromInitialProbe(
                    initialBytes = initialMetadata.initialBytes,
                    absolutePosition = position,
                    expectedId = ID_INFO,
                    maxElementBytes = MAX_INFO_BYTES,
                ) ?: fetchElementAt(
                    sourceUrl = sourceUrl,
                    sourceHeaders = sourceHeaders,
                    absolutePosition = position,
                    expectedId = ID_INFO,
                    maxElementBytes = MAX_INFO_BYTES,
                    stats = stats,
                )
            }
            ?.let(::parseTimestampScaleNs)
            ?: DEFAULT_TIMESTAMP_SCALE_NS

        val tracksPosition = resolvedPositions[ID_TRACKS] ?: directPositions[ID_TRACKS]
            ?: return null
        val subtitleTracks = (
            extractElementFromInitialProbe(
                initialBytes = initialMetadata.initialBytes,
                absolutePosition = tracksPosition,
                expectedId = ID_TRACKS,
                maxElementBytes = MAX_TRACKS_BYTES,
            ) ?: fetchElementAt(
                sourceUrl = sourceUrl,
                sourceHeaders = sourceHeaders,
                absolutePosition = tracksPosition,
                expectedId = ID_TRACKS,
                maxElementBytes = MAX_TRACKS_BYTES,
                stats = stats,
            )
            )?.let(::parseSubtitleTracks).orEmpty()
        if (subtitleTracks.isEmpty()) {
            return IndexedEmbeddedTimeline(
                tracks = emptyList(),
                source = "matroska-no-subtitle-tracks",
                bytesDownloaded = stats.bytesDownloaded,
                rangeRequests = stats.requests,
                loadMs = (System.nanoTime() - startedAtNs) / 1_000_000L,
                skipLiveFallbackWait = true,
                noSubtitleTracks = true,
            )
        }

        val cuesPosition = resolvedPositions[ID_CUES] ?: directPositions[ID_CUES]
        val parsedCues = if (cuesPosition != null) {
            (
                extractElementFromInitialProbe(
                    initialBytes = initialMetadata.initialBytes,
                    absolutePosition = cuesPosition,
                    expectedId = ID_CUES,
                    maxElementBytes = MAX_CUES_BYTES,
                ) ?: fetchElementAt(
                    sourceUrl = sourceUrl,
                    sourceHeaders = sourceHeaders,
                    absolutePosition = cuesPosition,
                    expectedId = ID_CUES,
                    maxElementBytes = MAX_CUES_BYTES,
                    stats = stats,
                )
                )?.let { cuesBytes ->
                parseSubtitleCueTimelines(
                    cuesElement = cuesBytes,
                    subtitleTracks = subtitleTracks,
                    timestampScaleNs = timestampScaleNs,
                )
            }
        } else {
            null
        } ?: findAndParseCuesNearFileEnd(
            sourceUrl = sourceUrl,
            sourceHeaders = sourceHeaders,
            totalLength = initialMetadata.totalLength,
            subtitleTracks = subtitleTracks,
            timestampScaleNs = timestampScaleNs,
            stats = stats,
        ) ?: return null

        if (subtitleTracks.all { track -> parsedCues[track.number]?.cues.orEmpty().isEmpty() }) {
            return IndexedEmbeddedTimeline(
                tracks = emptyList(),
                source = "matroska-cues-no-subtitle-entries",
                bytesDownloaded = stats.bytesDownloaded,
                rangeRequests = stats.requests,
                loadMs = (System.nanoTime() - startedAtNs) / 1_000_000L,
                skipLiveFallbackWait = true,
            )
        }

        val referenceTracks = subtitleTracks.mapNotNull { track ->
            val parsedTimeline = parsedCues[track.number] ?: return@mapNotNull null
            val cues = parsedTimeline.cues
                .sortedBy { it.startTimeMs }
                .distinctBy { it.startTimeMs }
            if (cues.size < MIN_INDEXED_CUES) return@mapNotNull null
            val spanMs = cues.last().startTimeMs - cues.first().startTimeMs
            if (spanMs < MIN_INDEXED_SPAN_MS) return@mapNotNull null

            var selectionFlags = 0
            if (track.forced) selectionFlags = selectionFlags or C.SELECTION_FLAG_FORCED

            var roleFlags = 0
            if (track.commentary) roleFlags = roleFlags or C.ROLE_FLAG_COMMENTARY
            if (track.hearingImpaired) {
                roleFlags = roleFlags or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND
            }
            if (track.visualImpaired || track.textDescriptions) {
                roleFlags = roleFlags or C.ROLE_FLAG_DESCRIBES_VIDEO
            }

            ReferenceTrack(
                key = "mkv-cues:${track.number}",
                language = track.languageIetf?.takeIf { it.isNotBlank() }
                    ?: track.language?.takeIf { it.isNotBlank() },
                cues = cues,
                label = track.name?.takeIf { it.isNotBlank() }
                    ?: buildFallbackTrackLabel(track),
                selectionFlags = selectionFlags,
                roleFlags = roleFlags,
                generation = -1L,
                estimatedEndStartsMs = parsedTimeline.estimatedEndStartsMs,
            )
        }

        if (referenceTracks.isEmpty()) {
            return null
        }

        return IndexedEmbeddedTimeline(
            tracks = referenceTracks,
            source = "matroska-cues",
            bytesDownloaded = stats.bytesDownloaded,
            rangeRequests = stats.requests,
            loadMs = (System.nanoTime() - startedAtNs) / 1_000_000L,
        )
    }

    /**
     * MP4/MOV equivalent of the Matroska Cues path. The complete subtitle timing lives in moov,
     * so this never scans mdat or decodes media samples.
     */
    private suspend fun loadMp4SampleTableIndex(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        initial: RangeResponse,
        stats: RangeStats,
        startedAtNs: Long,
    ): IndexedEmbeddedTimeline? {
        val moovLocation = findMp4Moov(
            sourceUrl = sourceUrl,
            sourceHeaders = sourceHeaders,
            initial = initial,
            stats = stats,
        ) ?: return null

        if (moovLocation.size <= 0L || moovLocation.size > MAX_MP4_MOOV_BYTES.toLong()) {
            return null
        }
        if (moovLocation.size > Int.MAX_VALUE.toLong()) {
            return null
        }
        if (moovLocation.position > Long.MAX_VALUE - moovLocation.size) {
            return null
        }

        val moovEnd = moovLocation.position + moovLocation.size
        val moovBytes =
            if (moovEnd <= initial.bytes.size.toLong()) {
                initial.bytes.copyOfRange(moovLocation.position.toInt(), moovEnd.toInt())
            } else {
                fetchRange(
                    sourceUrl = sourceUrl,
                    sourceHeaders = sourceHeaders,
                    start = moovLocation.position,
                    length = moovLocation.size.toInt(),
                    requirePartialContent = moovLocation.position > 0L,
                    stats = stats,
                    requireExactLength = true,
                )?.bytes ?: return null
            }

        val moov = parseMp4MoovTextTracks(moovBytes) ?: return null
        if (moov.containerChildren.isEmpty()) {
            return IndexedEmbeddedTimeline(
                tracks = emptyList(),
                source = "mp4-no-text-tracks",
                bytesDownloaded = stats.bytesDownloaded,
                rangeRequests = stats.requests,
                loadMs = (System.nanoTime() - startedAtNs) / 1_000_000L,
                skipLiveFallbackWait = true,
                noSubtitleTracks = true,
            )
        }

        val sampleTables = try {
            BoxParser.parseTraks(
                moov,
                GaplessInfoHolder(),
                C.TIME_UNSET,
                null,
                false,
                isQuickTimeContainer(initial.bytes, sourceUrl),
            ) { track ->
                track?.takeIf {
                    it.type == C.TRACK_TYPE_TEXT &&
                        isSupportedIndexedMp4SubtitleMime(it.format.sampleMimeType)
                }
            }
        } catch (error: Exception) {
            return null
        }

        val referenceTracks = sampleTables.mapNotNull(::buildMp4ReferenceTrack)
        if (referenceTracks.isEmpty()) {
            return null
        }

        return IndexedEmbeddedTimeline(
            tracks = referenceTracks,
            source = "mp4-sample-table",
            bytesDownloaded = stats.bytesDownloaded,
            rangeRequests = stats.requests,
            loadMs = (System.nanoTime() - startedAtNs) / 1_000_000L,
        )
    }

    /** Jump over top-level boxes by declared size; a huge mdat costs only its header. */
    private suspend fun findMp4Moov(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        initial: RangeResponse,
        stats: RangeStats,
    ): Mp4BoxLocation? {
        var position = 0L
        var boxCount = 0
        var firstBox = true

        while (boxCount++ < MAX_MP4_TOP_LEVEL_BOXES) {
            val totalLength = initial.totalLength
            if (totalLength != null && position >= totalLength) return null

            val inInitial =
                position >= 0L &&
                    position <= Int.MAX_VALUE.toLong() &&
                    position + MP4_BOX_HEADER_BYTES <= initial.bytes.size.toLong()

            val headerBytes: ByteArray
            val headerOffset: Int
            if (inInitial) {
                headerBytes = initial.bytes
                headerOffset = position.toInt()
            } else {
                headerBytes = fetchRange(
                    sourceUrl = sourceUrl,
                    sourceHeaders = sourceHeaders,
                    start = position,
                    length = MP4_BOX_HEADER_BYTES,
                    requirePartialContent = position > 0L,
                    stats = stats,
                )?.bytes ?: return null
                headerOffset = 0
            }

            val header = readMp4BoxHeader(
                bytes = headerBytes,
                offset = headerOffset,
                limit = headerBytes.size,
                extendsToEndSize = totalLength?.minus(position)?.takeIf { it > 0L }
                    ?: Long.MAX_VALUE,
            ) ?: return null

            if (firstBox) {
                if (!isPlausibleMp4TopLevelType(header.type)) return null
                firstBox = false
            }

            if (header.type == Mp4Box.TYPE_moov) {
                return Mp4BoxLocation(position = position, size = header.size)
            }
            if (header.size <= 0L || position > Long.MAX_VALUE - header.size) return null
            position += header.size
        }
        return null
    }

    /**
     * Build only Media3's required moov tree and retain only text trak boxes. This avoids copying
     * large video/audio sample tables a second time on memory-constrained TV hardware.
     */
    private fun parseMp4MoovTextTracks(bytes: ByteArray): Mp4Box.ContainerBox? {
        val root = readMp4BoxHeader(
            bytes = bytes,
            offset = 0,
            limit = bytes.size,
            extendsToEndSize = bytes.size.toLong(),
        ) ?: return null
        if (root.type != Mp4Box.TYPE_moov || root.size != bytes.size.toLong()) {
            return null
        }

        val rootEnd = root.size.toInt()
        if (hasDirectMp4Child(bytes, root.headerSize, rootEnd, Mp4Box.TYPE_mvex)) {
            // Fragmented MP4 needs moof/trun parsing; leave it to the existing live fallback.
            return null
        }

        val moov = Mp4Box.ContainerBox(Mp4Box.TYPE_moov, rootEnd.toLong())
        var position = root.headerSize
        while (position < rootEnd) {
            val child = readMp4BoxHeader(
                bytes = bytes,
                offset = position,
                limit = rootEnd,
                extendsToEndSize = (rootEnd - position).toLong(),
            ) ?: return null
            val childEnd = mp4BoxEnd(position, child, rootEnd) ?: return null

            when (child.type) {
                Mp4Box.TYPE_mvhd -> addMp4Leaf(moov, bytes, position, childEnd, child.type)
                Mp4Box.TYPE_trak -> {
                    if (isMp4TextTrack(bytes, child.dataStart(position), childEnd)) {
                        val parsed = parseMp4Container(bytes, position, childEnd, child)
                            ?: return null
                        moov.add(parsed)
                    }
                }
            }
            position = childEnd
        }
        return moov
    }

    private fun parseMp4Container(
        bytes: ByteArray,
        boxStart: Int,
        boxEnd: Int,
        header: Mp4BoxHeader,
    ): Mp4Box.ContainerBox? {
        val container = Mp4Box.ContainerBox(header.type, boxEnd.toLong())
        var position = header.dataStart(boxStart)

        while (position < boxEnd) {
            val child = readMp4BoxHeader(
                bytes = bytes,
                offset = position,
                limit = boxEnd,
                extendsToEndSize = (boxEnd - position).toLong(),
            ) ?: return null
            val childEnd = mp4BoxEnd(position, child, boxEnd) ?: return null

            if (isNeededMp4ContainerType(child.type)) {
                val parsed = parseMp4Container(bytes, position, childEnd, child) ?: return null
                container.add(parsed)
            } else if (isNeededMp4LeafType(child.type)) {
                addMp4Leaf(container, bytes, position, childEnd, child.type)
            }
            position = childEnd
        }
        return container
    }

    private fun addMp4Leaf(
        parent: Mp4Box.ContainerBox,
        bytes: ByteArray,
        start: Int,
        end: Int,
        type: Int,
    ) {
        parent.add(Mp4Box.LeafBox(type, ParsableByteArray(bytes.copyOfRange(start, end))))
    }

    private fun isMp4TextTrack(bytes: ByteArray, trakDataStart: Int, trakEnd: Int): Boolean {
        val mdia = findDirectMp4Child(bytes, trakDataStart, trakEnd, Mp4Box.TYPE_mdia)
            ?: return false
        val hdlr = findDirectMp4Child(bytes, mdia.dataStart, mdia.end, Mp4Box.TYPE_hdlr)
            ?: return false

        // hdlr = header + version/flags + pre_defined + handler_type.
        val handlerOffset = hdlr.start + hdlr.headerSize + 8
        if (handlerOffset + 4 > hdlr.end) return false
        val handlerType = readMp4Int(bytes, handlerOffset)
        return handlerType == 0x74657874 || // text
            handlerType == 0x7362746c || // sbtl
            handlerType == 0x73756274 || // subt
            handlerType == 0x636c6370 || // clcp
            handlerType == 0x73756270 // subp
    }

    private fun buildMp4ReferenceTrack(table: TrackSampleTable): ReferenceTrack? {
        val format = table.track.format
        val mimeType = format.sampleMimeType ?: return null
        if (!isSupportedIndexedMp4SubtitleMime(mimeType)) return null

        val cues = ArrayList<SubtitleSyncCue>(table.sampleCount)
        for (index in 0 until table.sampleCount) {
            if (isEmptyMp4SubtitleSample(mimeType, table.sizes[index])) continue

            val startUs = table.timestampsUs[index]
            if (startUs < 0L) continue
            val nextUs = if (index + 1 < table.sampleCount) {
                table.timestampsUs[index + 1]
            } else {
                table.durationUs
            }
            val endUs = if (nextUs == C.TIME_UNSET || nextUs <= startUs) {
                startUs + DEFAULT_CUE_DURATION_MS * 1_000L
            } else {
                nextUs
            }

            val startMs = startUs / 1_000L
            cues += SubtitleSyncCue(
                startTimeMs = startMs,
                endTimeMs = max(startMs + 1L, endUs / 1_000L),
                text = "",
            )
        }

        val normalized = cues.sortedBy { it.startTimeMs }.distinctBy { it.startTimeMs }
        if (normalized.size < MIN_INDEXED_CUES) return null
        if (normalized.last().startTimeMs - normalized.first().startTimeMs < MIN_INDEXED_SPAN_MS) {
            return null
        }

        val language = format.language?.takeIf { it.isNotBlank() }
        return ReferenceTrack(
            key = "mp4-samples:" + table.track.id,
            language = language,
            cues = normalized,
            label = format.label?.takeIf { it.isNotBlank() }
                ?: ((language ?: "Subtitle") + " [Full]"),
            selectionFlags = format.selectionFlags,
            roleFlags = format.roleFlags,
            generation = -1L,
        )
    }

    private fun isSupportedIndexedMp4SubtitleMime(mimeType: String?): Boolean =
        mimeType == MimeTypes.APPLICATION_TX3G || mimeType == MimeTypes.APPLICATION_MP4VTT

    /** Keep gap samples as timing boundaries, but don't emit them as dialogue cues. */
    private fun isEmptyMp4SubtitleSample(mimeType: String, sampleSize: Int): Boolean =
        when (mimeType) {
            MimeTypes.APPLICATION_TX3G -> sampleSize <= 2
            MimeTypes.APPLICATION_MP4VTT -> sampleSize <= 8
            else -> true
        }

    private fun isQuickTimeContainer(initialBytes: ByteArray, sourceUrl: String): Boolean {
        var position = 0
        while (position + 8 <= initialBytes.size && position < 4 * 1024) {
            val header = readMp4BoxHeader(
                bytes = initialBytes,
                offset = position,
                limit = initialBytes.size,
                extendsToEndSize = (initialBytes.size - position).toLong(),
            ) ?: break
            val end = mp4BoxEnd(position, header, initialBytes.size) ?: break
            if (header.type == 0x66747970) { // ftyp
                var brandOffset = header.dataStart(position)
                while (brandOffset + 4 <= end) {
                    if (readMp4Int(initialBytes, brandOffset) == 0x71742020) return true // "qt  "
                    brandOffset += 4
                }
                return false
            }
            position = end
        }

        val path = sourceUrl.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".mov") || path.contains(".mov/")
    }

    private fun hasDirectMp4Child(bytes: ByteArray, start: Int, end: Int, type: Int): Boolean =
        findDirectMp4Child(bytes, start, end, type) != null

    private fun findDirectMp4Child(
        bytes: ByteArray,
        start: Int,
        end: Int,
        type: Int,
    ): Mp4ChildRange? {
        var position = start
        while (position < end) {
            val header = readMp4BoxHeader(
                bytes = bytes,
                offset = position,
                limit = end,
                extendsToEndSize = (end - position).toLong(),
            ) ?: return null
            val childEnd = mp4BoxEnd(position, header, end) ?: return null
            if (header.type == type) {
                return Mp4ChildRange(
                    start = position,
                    dataStart = header.dataStart(position),
                    end = childEnd,
                    headerSize = header.headerSize,
                )
            }
            position = childEnd
        }
        return null
    }

    private fun isNeededMp4ContainerType(type: Int): Boolean =
        type == Mp4Box.TYPE_mdia ||
            type == Mp4Box.TYPE_minf ||
            type == Mp4Box.TYPE_stbl ||
            type == Mp4Box.TYPE_edts

    private fun isNeededMp4LeafType(type: Int): Boolean =
        type == Mp4Box.TYPE_tkhd ||
            type == Mp4Box.TYPE_mdhd ||
            type == Mp4Box.TYPE_hdlr ||
            type == Mp4Box.TYPE_stsd ||
            type == Mp4Box.TYPE_stts ||
            type == Mp4Box.TYPE_ctts ||
            type == Mp4Box.TYPE_stsc ||
            type == Mp4Box.TYPE_stsz ||
            type == Mp4Box.TYPE_stz2 ||
            type == Mp4Box.TYPE_stco ||
            type == Mp4Box.TYPE_co64 ||
            type == Mp4Box.TYPE_stss ||
            type == Mp4Box.TYPE_elst

    private fun isPlausibleMp4TopLevelType(type: Int): Boolean =
        type == 0x66747970 || // ftyp
            type == Mp4Box.TYPE_moov ||
            type == 0x6d646174 || // mdat
            type == 0x66726565 || // free
            type == 0x736b6970 || // skip
            type == 0x77696465 || // wide
            type == 0x706e6f74 || // pnot (older QuickTime)
            type == 0x75756964 || // uuid
            type == 0x7064696e || // pdin
            type == 0x6d6f6f66 || // moof
            type == 0x73696478 || // sidx
            type == 0x73747970 // styp

    private fun readMp4BoxHeader(
        bytes: ByteArray,
        offset: Int,
        limit: Int,
        extendsToEndSize: Long,
    ): Mp4BoxHeader? {
        if (offset < 0 || limit > bytes.size || offset + 8 > limit) return null

        val size32 = readMp4UnsignedInt(bytes, offset)
        val type = readMp4Int(bytes, offset + 4)
        var headerSize = 8
        val size = when (size32) {
            0L -> extendsToEndSize
            1L -> {
                if (offset + 16 > limit) return null
                headerSize = 16
                readMp4UnsignedLong(bytes, offset + 8) ?: return null
            }
            else -> size32
        }
        if (size < headerSize.toLong()) return null
        return Mp4BoxHeader(type = type, size = size, headerSize = headerSize)
    }

    private fun mp4BoxEnd(start: Int, header: Mp4BoxHeader, limit: Int): Int? {
        if (header.size > Int.MAX_VALUE.toLong()) return null
        val end = start.toLong() + header.size
        if (end <= start.toLong() || end > limit.toLong()) return null
        return end.toInt()
    }

    private fun readMp4UnsignedInt(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)

    private fun readMp4Int(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readMp4UnsignedLong(bytes: ByteArray, offset: Int): Long? {
        if ((bytes[offset].toInt() and 0x80) != 0) return null
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFFL)
        }
        return value
    }

    private suspend fun findAndParseCuesNearFileEnd(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        totalLength: Long?,
        subtitleTracks: List<MatroskaSubtitleTrack>,
        timestampScaleNs: Long,
        stats: RangeStats,
    ): Map<Int, IndexedSubtitleTimeline>? {
        val fileLength = totalLength?.takeIf { it > 0L } ?: return null
        val start = max(0L, fileLength - TAIL_PROBE_BYTES)
        if (start == 0L) return null
        val tailLength = (fileLength - start).coerceAtMost(TAIL_PROBE_BYTES.toLong()).toInt()
        val candidates = fetchRange(
            sourceUrl = sourceUrl,
            sourceHeaders = sourceHeaders,
            start = start,
            length = tailLength,
            requirePartialContent = true,
            stats = stats,
        )?.let { tail ->
            findElementIdOffsets(tail.bytes, ID_CUES).asReversed()
        } ?: return null
        for (relativeOffset in candidates) {
            val absolute = start + relativeOffset
            val cuesBytes = fetchElementAt(
                sourceUrl = sourceUrl,
                sourceHeaders = sourceHeaders,
                absolutePosition = absolute,
                expectedId = ID_CUES,
                maxElementBytes = MAX_CUES_BYTES,
                stats = stats,
            ) ?: continue
            val parsed = parseSubtitleCueTimelines(
                cuesElement = cuesBytes,
                subtitleTracks = subtitleTracks,
                timestampScaleNs = timestampScaleNs,
            )
            if (parsed.values.any { timeline -> timeline.cues.size >= MIN_INDEXED_CUES }) return parsed
        }
        return null
    }

    private fun findSegment(bytes: ByteArray): EbmlElement? {
        var position = 0
        var elementCount = 0
        while (position < bytes.size && elementCount++ < 32) {
            val element = readElement(bytes, position, bytes.size) ?: return null
            if (element.id == ID_SEGMENT) return element
            if (element.id == ID_EBML) {
                val end = element.endWithin(bytes.size) ?: return null
                position = end
                continue
            }
            val end = element.endWithin(bytes.size) ?: return null
            position = end
        }
        return null
    }

    private fun findInitialTopLevelPositions(
        bytes: ByteArray,
        segment: EbmlElement,
    ): Map<Long, Long> {
        val result = mutableMapOf<Long, Long>()
        var position = segment.dataStart
        var elementCount = 0
        while (position < bytes.size && elementCount++ < 128) {
            val element = readElement(bytes, position, bytes.size) ?: break
            when (element.id) {
                ID_SEEK_HEAD, ID_INFO, ID_TRACKS, ID_CUES ->
                    result.putIfAbsent(element.id, position.toLong())
                ID_CLUSTER -> break
            }
            val end = element.endWithin(bytes.size) ?: break
            position = end
        }
        return result
    }

    private fun parseSeekHead(seekHeadElement: ByteArray): Map<Long, Long> {
        val root = readElement(seekHeadElement, 0, seekHeadElement.size) ?: return emptyMap()
        if (root.id != ID_SEEK_HEAD) return emptyMap()
        val rootEnd = root.endWithin(seekHeadElement.size) ?: return emptyMap()
        val result = mutableMapOf<Long, Long>()

        forEachChild(seekHeadElement, root.dataStart, rootEnd) { seek ->
            if (seek.id != ID_SEEK) return@forEachChild
            val seekEnd = seek.endWithin(rootEnd) ?: return@forEachChild
            var targetId: Long? = null
            var position: Long? = null
            forEachChild(seekHeadElement, seek.dataStart, seekEnd) { child ->
                when (child.id) {
                    ID_SEEK_ID -> targetId = readBinaryId(seekHeadElement, child)
                    ID_SEEK_POSITION -> position = readUnsigned(seekHeadElement, child)
                }
            }
            val id = targetId
            val relativePosition = position
            if (id != null && relativePosition != null) {
                result.putIfAbsent(id, relativePosition)
            }
        }
        return result
    }

    private fun parseTimestampScaleNs(infoElement: ByteArray): Long {
        val root = readElement(infoElement, 0, infoElement.size) ?: return DEFAULT_TIMESTAMP_SCALE_NS
        if (root.id != ID_INFO) return DEFAULT_TIMESTAMP_SCALE_NS
        val rootEnd = root.endWithin(infoElement.size) ?: return DEFAULT_TIMESTAMP_SCALE_NS
        var scale = DEFAULT_TIMESTAMP_SCALE_NS
        forEachChild(infoElement, root.dataStart, rootEnd) { child ->
            if (child.id == ID_TIMESTAMP_SCALE) {
                readUnsigned(infoElement, child)?.takeIf { it > 0L }?.let { scale = it }
            }
        }
        return scale
    }

    private fun parseSubtitleTracks(tracksElement: ByteArray): List<MatroskaSubtitleTrack> {
        val root = readElement(tracksElement, 0, tracksElement.size) ?: return emptyList()
        if (root.id != ID_TRACKS) return emptyList()
        val rootEnd = root.endWithin(tracksElement.size) ?: return emptyList()
        val result = mutableListOf<MatroskaSubtitleTrack>()

        forEachChild(tracksElement, root.dataStart, rootEnd) { entry ->
            if (entry.id != ID_TRACK_ENTRY) return@forEachChild
            val entryEnd = entry.endWithin(rootEnd) ?: return@forEachChild

            var number: Int? = null
            var type: Long? = null
            var name: String? = null
            var language: String? = null
            var languageIetf: String? = null
            var codecId: String? = null
            var isDefault = true
            var forced = false
            var hearingImpaired = false
            var visualImpaired = false
            var textDescriptions = false
            var commentary = false

            forEachChild(tracksElement, entry.dataStart, entryEnd) { child ->
                when (child.id) {
                    ID_TRACK_NUMBER -> number = readUnsigned(tracksElement, child)?.toInt()
                    ID_TRACK_TYPE -> type = readUnsigned(tracksElement, child)
                    ID_NAME -> name = readUtf8(tracksElement, child)
                    ID_LANGUAGE -> language = readUtf8(tracksElement, child)
                    ID_LANGUAGE_IETF -> languageIetf = readUtf8(tracksElement, child)
                    ID_CODEC_ID -> codecId = readUtf8(tracksElement, child)
                    ID_FLAG_DEFAULT -> isDefault = readUnsigned(tracksElement, child) != 0L
                    ID_FLAG_FORCED -> forced = readUnsigned(tracksElement, child) == 1L
                    ID_FLAG_HEARING_IMPAIRED -> hearingImpaired = readUnsigned(tracksElement, child) == 1L
                    ID_FLAG_VISUAL_IMPAIRED -> visualImpaired = readUnsigned(tracksElement, child) == 1L
                    ID_FLAG_TEXT_DESCRIPTIONS -> textDescriptions = readUnsigned(tracksElement, child) == 1L
                    ID_FLAG_COMMENTARY -> commentary = readUnsigned(tracksElement, child) == 1L
                }
            }

            val trackNumber = number
            if (trackNumber != null && type == TRACK_TYPE_SUBTITLE) {
                result += MatroskaSubtitleTrack(
                    number = trackNumber,
                    name = name,
                    language = language,
                    languageIetf = languageIetf,
                    codecId = codecId,
                    isDefault = isDefault,
                    forced = forced,
                    hearingImpaired = hearingImpaired,
                    visualImpaired = visualImpaired,
                    textDescriptions = textDescriptions,
                    commentary = commentary,
                )
            }
        }
        return result
    }

    private fun parseSubtitleCueTimelines(
        cuesElement: ByteArray,
        subtitleTracks: List<MatroskaSubtitleTrack>,
        timestampScaleNs: Long,
    ): Map<Int, IndexedSubtitleTimeline> {
        val root = readElement(cuesElement, 0, cuesElement.size) ?: return emptyMap()
        if (root.id != ID_CUES) return emptyMap()
        val rootEnd = root.endWithin(cuesElement.size) ?: return emptyMap()
        val subtitleTrackNumbers = subtitleTracks.mapTo(mutableSetOf()) { it.number }
        val pendingByTrack = subtitleTrackNumbers.associateWith { mutableListOf<PendingIndexedCue>() }
            .toMutableMap()

        forEachChild(cuesElement, root.dataStart, rootEnd) { cuePoint ->
            if (cuePoint.id != ID_CUE_POINT) return@forEachChild
            val pointEnd = cuePoint.endWithin(rootEnd) ?: return@forEachChild
            var cueTimeTicks: Long? = null
            val positions = mutableListOf<CueTrackPosition>()

            forEachChild(cuesElement, cuePoint.dataStart, pointEnd) { child ->
                when (child.id) {
                    ID_CUE_TIME -> cueTimeTicks = readUnsigned(cuesElement, child)
                    ID_CUE_TRACK_POSITIONS -> {
                        val positionEnd = child.endWithin(pointEnd)
                        if (positionEnd != null) {
                            var trackNumber: Int? = null
                            var durationTicks: Long? = null
                            forEachChild(cuesElement, child.dataStart, positionEnd) { positionChild ->
                                when (positionChild.id) {
                                    ID_CUE_TRACK -> trackNumber = readUnsigned(cuesElement, positionChild)?.toInt()
                                    ID_CUE_DURATION -> durationTicks = readUnsigned(cuesElement, positionChild)
                                }
                            }
                            trackNumber?.let { positions += CueTrackPosition(it, durationTicks) }
                        }
                    }
                }
            }

            val timeTicks = cueTimeTicks ?: return@forEachChild
            val startMs = ticksToMs(timeTicks, timestampScaleNs) ?: return@forEachChild
            positions.forEach { position ->
                if (position.trackNumber !in subtitleTrackNumbers) return@forEach
                val durationMs = position.durationTicks
                    ?.let { ticksToMs(it, timestampScaleNs) }
                    ?.takeIf { it > 0L }
                pendingByTrack[position.trackNumber]?.add(
                    PendingIndexedCue(
                        startTimeMs = startMs,
                        explicitDurationMs = durationMs,
                    ),
                )
            }
        }

        val tracksByNumber = subtitleTracks.associateBy { it.number }
        val peerDialogueCueCounts = subtitleTracks
            .asSequence()
            .filter { track ->
                !track.codecId.equals(MATROSKA_PGS_CODEC_ID, ignoreCase = true) &&
                    !track.forced &&
                    !track.commentary
            }
            .mapNotNull { track ->
                pendingByTrack[track.number]
                    ?.size
                    ?.takeIf { it >= MIN_INDEXED_CUES }
            }
            .sorted()
            .toList()
        val peerDialogueCueCount = peerDialogueCueCounts
            .takeIf { it.isNotEmpty() }
            ?.let { counts -> counts[counts.size / 2] }

        return pendingByTrack.mapValues { (trackNumber, pending) ->
            val sorted = pending
                .sortedBy { it.startTimeMs }
                .distinctBy { it.startTimeMs }

            if (tracksByNumber[trackNumber]?.codecId.equals(MATROSKA_PGS_CODEC_ID, ignoreCase = true)) {
                buildPgsIndexedTimeline(
                    sorted = sorted,
                    peerDialogueCueCount = peerDialogueCueCount,
                )
            } else {
                buildDefaultIndexedTimeline(sorted)
            }
        }
    }

    /**
     * PGS commonly indexes both a visible display and the later clear display set. AutoSync only
     * needs the resulting visibility interval, not the bitmap. Pair those events only when that
     * interpretation is supported by peer text-track density (when available); otherwise preserve
     * the original cue timeline. This keeps the fast O(n), zero-extra-I/O path conservative.
     */
    private fun buildPgsIndexedTimeline(
        sorted: List<PendingIndexedCue>,
        peerDialogueCueCount: Int?,
    ): IndexedSubtitleTimeline {
        if (sorted.size < 2) {
            return IndexedSubtitleTimeline(
                cues = emptyList(),
                estimatedEndStartsMs = emptySet(),
            )
        }

        val pairedCount = sorted.size / 2
        val explicitDurationCount = sorted.count { (it.explicitDurationMs ?: 0L) > 0L }
        val shouldPair = if (peerDialogueCueCount != null) {
            val rawDelta = if (sorted.size >= peerDialogueCueCount) {
                sorted.size - peerDialogueCueCount
            } else {
                peerDialogueCueCount - sorted.size
            }
            val pairedDelta = if (pairedCount >= peerDialogueCueCount) {
                pairedCount - peerDialogueCueCount
            } else {
                peerDialogueCueCount - pairedCount
            }

            // Require a material improvement, not a marginal count coincidence.
            pairedDelta.toLong() * 3L <= rawDelta.toLong() * 2L
        } else {
            // With no peer text track, explicit durations on most entries are evidence that the
            // index already represents visible cues rather than alternating display/clear events.
            explicitDurationCount.toLong() * 4L < sorted.size.toLong() * 3L
        }

        if (!shouldPair) {
            return buildDefaultIndexedTimeline(sorted)
        }

        val cues = ArrayList<SubtitleSyncCue>(pairedCount)
        var index = 0
        while (index + 1 < sorted.size) {
            val display = sorted[index]
            val clear = sorted[index + 1]
            if (clear.startTimeMs > display.startTimeMs) {
                val explicitEndMs = display.explicitDurationMs
                    ?.takeIf { duration ->
                        duration > 0L && display.startTimeMs <= Long.MAX_VALUE - duration
                    }
                    ?.let { duration -> display.startTimeMs + duration }
                val endTimeMs = explicitEndMs
                    ?.coerceAtMost(clear.startTimeMs)
                    ?: clear.startTimeMs

                if (endTimeMs > display.startTimeMs) {
                    cues += SubtitleSyncCue(
                        startTimeMs = display.startTimeMs,
                        endTimeMs = endTimeMs,
                        text = "",
                    )
                }
            }
            index += 2
        }

        return IndexedSubtitleTimeline(
            cues = cues,
            estimatedEndStartsMs = emptySet(),
        )
    }

    private fun buildDefaultIndexedTimeline(
        sorted: List<PendingIndexedCue>,
    ): IndexedSubtitleTimeline {
        val estimatedEndStartsMs = HashSet<Long>()

        val cues = sorted.mapIndexed { index, cue ->
            val durationMs = cue.explicitDurationMs ?: run {
                estimatedEndStartsMs += cue.startTimeMs
                val nextStartMs = sorted.getOrNull(index + 1)?.startTimeMs
                if (nextStartMs != null && nextStartMs > cue.startTimeMs) {
                    (nextStartMs - cue.startTimeMs)
                        .coerceAtMost(MAX_MKV_INTER_CUE_ESTIMATED_DURATION_MS)
                        .coerceAtLeast(1L)
                } else {
                    LAST_MKV_CUE_ESTIMATED_DURATION_MS
                }
            }
            SubtitleSyncCue(
                startTimeMs = cue.startTimeMs,
                endTimeMs = cue.startTimeMs + durationMs,
                text = "",
            )
        }

        return IndexedSubtitleTimeline(
            cues = cues,
            estimatedEndStartsMs = estimatedEndStartsMs,
        )
    }

    private fun buildFallbackTrackLabel(track: MatroskaSubtitleTrack): String {
        val language = track.languageIetf?.takeIf { it.isNotBlank() }
            ?: track.language?.takeIf { it.isNotBlank() }
            ?: "Subtitle"
        val suffix = when {
            track.commentary -> " [Commentary]"
            track.forced -> " [Forced]"
            track.hearingImpaired -> " [SDH]"
            else -> " [Full]"
        }
        return language + suffix
    }

    private fun ticksToMs(ticks: Long, timestampScaleNs: Long): Long? {
        if (ticks < 0L || timestampScaleNs <= 0L) return null
        if (ticks > Long.MAX_VALUE / timestampScaleNs) {
            return ((ticks.toDouble() * timestampScaleNs.toDouble()) / 1_000_000.0)
                .takeIf { it.isFinite() && it >= 0.0 && it <= Long.MAX_VALUE.toDouble() }
                ?.toLong()
        }
        return ticks * timestampScaleNs / 1_000_000L
    }

    /**
     * Reuse the first 512 KiB probe whenever it already contains a complete metadata element.
     * Matroska SeekHead/Info/Tracks are commonly near the beginning of the file, so this removes
     * whole network round-trips without changing parsing or range-request fallback behavior.
     */
    private fun extractElementFromInitialProbe(
        initialBytes: ByteArray,
        absolutePosition: Long,
        expectedId: Long,
        maxElementBytes: Int,
    ): ByteArray? {
        if (absolutePosition < 0L || absolutePosition > Int.MAX_VALUE.toLong()) return null
        val start = absolutePosition.toInt()
        if (start < 0 || start >= initialBytes.size) return null

        val header = readElement(initialBytes, start, initialBytes.size) ?: return null
        if (header.id != expectedId || header.size == null) return null
        val totalSize = header.headerSize.toLong() + header.size
        if (totalSize <= 0L || totalSize > maxElementBytes.toLong()) return null

        val end = start.toLong() + totalSize
        if (end > initialBytes.size.toLong() || end > Int.MAX_VALUE.toLong()) return null
        return initialBytes.copyOfRange(start, end.toInt())
    }

    private suspend fun fetchElementAt(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        absolutePosition: Long,
        expectedId: Long,
        maxElementBytes: Int,
        stats: RangeStats,
    ): ByteArray? {
        if (absolutePosition < 0L) return null
        val headerProbe = fetchRange(
            sourceUrl = sourceUrl,
            sourceHeaders = sourceHeaders,
            start = absolutePosition,
            length = HEADER_PROBE_BYTES,
            requirePartialContent = absolutePosition > 0L,
            stats = stats,
        ) ?: return null
        val header = readElement(headerProbe.bytes, 0, headerProbe.bytes.size) ?: return null
        if (header.id != expectedId || header.size == null) return null
        val totalSize = header.headerSize.toLong() + header.size
        if (totalSize <= 0L) return null
        if (totalSize > maxElementBytes.toLong()) {
            return null
        }
        if (totalSize > stats.remainingByteBudget()) {
            return null
        }
        if (totalSize <= headerProbe.bytes.size) {
            return if (totalSize == headerProbe.bytes.size.toLong()) {
                headerProbe.bytes
            } else {
                // At most HEADER_PROBE_BYTES (64 B), so this tiny trim is intentionally harmless.
                headerProbe.bytes.copyOf(totalSize.toInt())
            }
        }

        // The large element is read directly into one exact-size array. Avoid ByteArrayOutputStream
        // and a second copy, which matters for a multi-megabyte Cues index.
        return fetchRange(
            sourceUrl = sourceUrl,
            sourceHeaders = sourceHeaders,
            start = absolutePosition,
            length = totalSize.toInt(),
            requirePartialContent = absolutePosition > 0L,
            stats = stats,
            requireExactLength = true,
        )?.bytes
    }

    private suspend fun fetchRange(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        start: Long,
        length: Int,
        requirePartialContent: Boolean,
        stats: RangeStats,
        requireExactLength: Boolean = false,
    ): RangeResponse? {
        if (length <= 0 || start < 0L) return null
        if (!stats.canRequest(length)) return null
        val end = start + length - 1L
        if (end < start) return null

        val requestBuilder = Request.Builder()
            .url(sourceUrl)
            .header("Range", "bytes=$start-$end")
            .header("Accept-Encoding", "identity")

        sourceHeaders.forEach { (name, value) ->
            if (!name.equals("Range", ignoreCase = true) &&
                !name.equals("Accept-Encoding", ignoreCase = true) &&
                !name.equals("Content-Length", ignoreCase = true) &&
                !name.equals("Host", ignoreCase = true)
            ) {
                requestBuilder.header(name, value)
            }
        }

        val remainingBudgetMs = stats.remainingBudgetMs()
        if (remainingBudgetMs <= 0L) return null

        stats.requests++
        val call = httpClient.newCall(requestBuilder.build())
        call.timeout().timeout(
            minOf(remainingBudgetMs.coerceAtLeast(1L), 5_000L),
            TimeUnit.MILLISECONDS,
        )

        return suspendCancellableCoroutine { continuation ->
            // OkHttp's async API lets structured coroutine cancellation interrupt DNS/connect/
            // headers/body reads immediately instead of waiting for blocking execute() to return.
            continuation.invokeOnCancellation {
                call.cancel()
            }

            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: java.io.IOException) {
                        if (!continuation.isActive) return
                        if (call.isCanceled()) {
                            continuation.resumeWith(
                                Result.failure(
                                    CancellationException(
                                        "Cancelled embedded index HTTP request",
                                    ).also { it.initCause(error) },
                                ),
                            )
                        } else {
                            continuation.resumeWith(Result.failure(error))
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (!continuation.isActive) {
                            response.close()
                            return
                        }

                        try {
                            val result = response.use { current ->
                                if (!current.isSuccessful) return@use null
                                if (requirePartialContent && current.code != 206) return@use null
                                if (start > 0L && current.code != 206) return@use null

                                val contentRange = parseContentRange(
                                    current.header("Content-Range"),
                                )
                                if (current.code == 206) {
                                    val parsedRange = contentRange ?: return@use null
                                    if (parsedRange.start != start) return@use null
                                }

                                val body = current.body ?: return@use null
                                val input = body.byteStream()
                                val bytes = ByteArray(length)
                                var offset = 0
                                while (offset < length) {
                                    if (
                                        stats.remainingBudgetMs() <= 0L ||
                                        stats.remainingByteBudget() <= 0L
                                    ) {
                                        return@use null
                                    }
                                    val allowedRead = minOf(
                                        length - offset,
                                        stats.remainingByteBudget()
                                            .coerceAtMost(Int.MAX_VALUE.toLong())
                                            .toInt(),
                                    )
                                    if (allowedRead <= 0) return@use null
                                    val read = input.read(bytes, offset, allowedRead)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    offset += read
                                    stats.bytesDownloaded += read.toLong()
                                }
                                if (offset == 0) return@use null
                                if (requireExactLength && offset != length) return@use null

                                val returnedBytes =
                                    if (offset == length) bytes else bytes.copyOf(offset)
                                val totalLength = contentRange?.total
                                    ?: if (current.code == 200) {
                                        current.header("Content-Length")?.toLongOrNull()
                                    } else {
                                        null
                                    }
                                RangeResponse(
                                    bytes = returnedBytes,
                                    totalLength = totalLength,
                                )
                            }

                            if (continuation.isActive) {
                                continuation.resumeWith(Result.success(result))
                            }
                        } catch (error: Throwable) {
                            if (!continuation.isActive) return
                            if (call.isCanceled() && error !is CancellationException) {
                                continuation.resumeWith(
                                    Result.failure(
                                        CancellationException(
                                            "Cancelled embedded index HTTP request",
                                        ).also { it.initCause(error) },
                                    ),
                                )
                            } else {
                                continuation.resumeWith(Result.failure(error))
                            }
                        }
                    }
                },
            )
        }
    }

    private fun parseContentRange(value: String?): ContentRange? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        if (!trimmed.startsWith("bytes ", ignoreCase = true)) return null
        val rangeAndTotal = trimmed.substringAfter(' ').split('/', limit = 2)
        if (rangeAndTotal.size != 2) return null
        val bounds = rangeAndTotal[0].split('-', limit = 2)
        val start = bounds.getOrNull(0)?.toLongOrNull()
        val end = bounds.getOrNull(1)?.toLongOrNull()
        val total = rangeAndTotal[1].takeIf { it != "*" }?.toLongOrNull()
        if (start != null && end != null && end < start) return null
        return ContentRange(start = start, end = end, total = total)
    }

    private fun findElementIdOffsets(bytes: ByteArray, id: Long): List<Int> {
        val idBytes = elementIdBytes(id)
        if (idBytes.isEmpty() || bytes.size < idBytes.size) return emptyList()
        val result = mutableListOf<Int>()
        outer@ for (index in 0..bytes.size - idBytes.size) {
            for (offset in idBytes.indices) {
                if (bytes[index + offset] != idBytes[offset]) continue@outer
            }
            val parsed = readElement(bytes, index, bytes.size)
            if (parsed?.id == id && parsed.size != null) result += index
        }
        return result
    }

    private fun elementIdBytes(id: Long): ByteArray {
        var length = 1
        while (length < 8 && id >= (1L shl (length * 8))) length++
        return ByteArray(length) { index ->
            ((id shr ((length - index - 1) * 8)) and 0xFF).toByte()
        }
    }

    private fun forEachChild(
        bytes: ByteArray,
        start: Int,
        endExclusive: Int,
        action: (EbmlElement) -> Unit,
    ) {
        var position = start
        var count = 0
        while (position < endExclusive && count++ < 1_000_000) {
            val child = readElement(bytes, position, endExclusive) ?: break
            val end = child.endWithin(endExclusive) ?: break
            action(child)
            if (end <= position) break
            position = end
        }
    }

    private fun readElement(bytes: ByteArray, offset: Int, limit: Int): EbmlElement? {
        if (offset < 0 || offset >= limit || limit > bytes.size) return null
        val idLength = vintLength(bytes[offset].toInt() and 0xFF) ?: return null
        if (idLength > 4 || offset + idLength >= limit) return null

        var id = 0L
        for (index in 0 until idLength) {
            id = (id shl 8) or (bytes[offset + index].toLong() and 0xFFL)
        }

        val sizeOffset = offset + idLength
        val sizeLength = vintLength(bytes[sizeOffset].toInt() and 0xFF) ?: return null
        if (sizeLength > 8 || sizeOffset + sizeLength > limit) return null
        val markerMask = 1 shl (8 - sizeLength)
        var sizeValue = (bytes[sizeOffset].toInt() and (markerMask - 1)).toLong()
        for (index in 1 until sizeLength) {
            sizeValue = (sizeValue shl 8) or (bytes[sizeOffset + index].toLong() and 0xFFL)
        }
        val unknownValue = (1L shl (7 * sizeLength)) - 1L
        val size = if (sizeValue == unknownValue) null else sizeValue
        val dataStart = sizeOffset + sizeLength
        return EbmlElement(
            id = id,
            size = size,
            headerStart = offset,
            dataStart = dataStart,
            headerSize = dataStart - offset,
        )
    }

    private fun vintLength(firstByte: Int): Int? {
        if (firstByte == 0) return null
        var mask = 0x80
        var length = 1
        while ((firstByte and mask) == 0) {
            mask = mask ushr 1
            length++
            if (length > 8) return null
        }
        return length
    }

    private fun readUnsigned(bytes: ByteArray, element: EbmlElement): Long? {
        val size = element.size ?: return null
        if (size !in 1L..8L) return null
        val end = element.dataStart + size.toInt()
        if (end > bytes.size) return null
        var value = 0L
        for (index in element.dataStart until end) {
            value = (value shl 8) or (bytes[index].toLong() and 0xFFL)
        }
        return value
    }

    private fun readBinaryId(bytes: ByteArray, element: EbmlElement): Long? {
        val size = element.size ?: return null
        if (size !in 1L..4L) return null
        val end = element.dataStart + size.toInt()
        if (end > bytes.size) return null
        var value = 0L
        for (index in element.dataStart until end) {
            value = (value shl 8) or (bytes[index].toLong() and 0xFFL)
        }
        return value
    }

    private fun readUtf8(bytes: ByteArray, element: EbmlElement): String? {
        val size = element.size ?: return null
        if (size < 0L || size > Int.MAX_VALUE) return null
        val end = element.dataStart + size.toInt()
        if (end > bytes.size) return null
        return bytes.copyOfRange(element.dataStart, end)
            .toString(Charsets.UTF_8)
            .trimEnd('\u0000')
    }

    private data class Mp4BoxLocation(
        val position: Long,
        val size: Long,
    )

    private data class Mp4BoxHeader(
        val type: Int,
        val size: Long,
        val headerSize: Int,
    ) {
        fun dataStart(boxStart: Int): Int = boxStart + headerSize
    }

    private data class Mp4ChildRange(
        val start: Int,
        val dataStart: Int,
        val end: Int,
        val headerSize: Int,
    )

    private data class CachedLoadResult(
        val timeline: IndexedEmbeddedTimeline?,
        val createdAtNs: Long,
    )

    private data class RangeResponse(
        val bytes: ByteArray,
        val totalLength: Long?,
    )

    private data class RangeStats(
        var requests: Int = 0,
        var bytesDownloaded: Long = 0L,
        val deadlineNs: Long,
        val maxBytes: Long,
        val maxRequests: Int,
    ) {
        fun remainingBudgetMs(): Long =
            ((deadlineNs - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)

        fun remainingByteBudget(): Long =
            (maxBytes - bytesDownloaded).coerceAtLeast(0L)

        fun canRequest(length: Int): Boolean =
            length > 0 &&
                requests < maxRequests &&
                length.toLong() <= remainingByteBudget() &&
                remainingBudgetMs() > 0L
    }

    private data class ContentRange(
        val start: Long?,
        val end: Long?,
        val total: Long?,
    )

    private data class InitialMetadata(
        val segmentDataStart: Long,
        val directPositions: Map<Long, Long>,
        val totalLength: Long?,
        val initialBytes: ByteArray,
    )

    private data class EbmlElement(
        val id: Long,
        val size: Long?,
        val headerStart: Int,
        val dataStart: Int,
        val headerSize: Int,
    ) {
        fun endWithin(limit: Int): Int? {
            val contentSize = size ?: return null
            if (contentSize < 0L || contentSize > Int.MAX_VALUE) return null
            val end = dataStart.toLong() + contentSize
            if (end > limit.toLong()) return null
            return end.toInt()
        }
    }

    private data class MatroskaSubtitleTrack(
        val number: Int,
        val name: String?,
        val language: String?,
        val languageIetf: String?,
        val codecId: String?,
        val isDefault: Boolean,
        val forced: Boolean,
        val hearingImpaired: Boolean,
        val visualImpaired: Boolean,
        val textDescriptions: Boolean,
        val commentary: Boolean,
    )

    private data class CueTrackPosition(
        val trackNumber: Int,
        val durationTicks: Long?,
    )

    private data class PendingIndexedCue(
        val startTimeMs: Long,
        val explicitDurationMs: Long?,
    )

    private data class IndexedSubtitleTimeline(
        val cues: List<SubtitleSyncCue>,
        val estimatedEndStartsMs: Set<Long>,
    )
}

internal data class IndexedEmbeddedTimeline(
    val tracks: List<ReferenceTrack>,
    val source: String,
    val bytesDownloaded: Long,
    val rangeRequests: Int,
    val loadMs: Long,
    val skipLiveFallbackWait: Boolean = false,
    val noSubtitleTracks: Boolean = false,
)
