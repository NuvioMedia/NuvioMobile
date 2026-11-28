@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.audiosync

import android.content.Context
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.CuesWithTiming
import com.nuvio.app.features.audiosync.SubtitleSyncStatus
import com.nuvio.app.features.autosync.AutomaticSubtitleSync
import com.nuvio.app.features.player.parseSidecarTimedCuesRobust
import com.nuvio.app.features.audiosync.asr.AsrLock
import com.nuvio.app.features.audiosync.asr.AsrModel
import com.nuvio.app.features.audiosync.asr.AsrSyncEngine
import com.nuvio.app.features.audiosync.asr.ReferenceSubtitle
import com.nuvio.app.features.audiosync.asr.SharedRecognizer
import com.nuvio.app.features.audiosync.asr.SpeechToText
import com.nuvio.app.features.audiosync.asr.SpeechSegmenter
import com.nuvio.app.features.audiosync.asr.SubtitleBridge
import com.nuvio.app.features.audiosync.asr.WordAnchorMatcher
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Keeps external subtitles in sync with the audio of the playing stream.
 *
 * Compressed audio is copied as ExoPlayer demuxes it (see [AudioSyncExtractorsFactory]), decoded on
 * a background thread, turned into a speech timeline by Silero VAD, and aligned against the
 * subtitle cues. The resulting mapping is exposed as an extra subtitle delay through
 * [autoDelayMs], on top of the user's manual delay. One instance lives per player surface.
 */
internal class AudioSubtitleSyncController(
    context: Context,
    /** The user's current manual subtitle delay. */
    private val manualDelayMs: () -> Int,
    /** User-visible progress, called from background threads. */
    private val onStatus: (AudioSyncStatus) -> Unit = {},
    /** Asks for the subtitle [url] to be shown instead of the current one (it fits the audio). */
    private val requestSwitch: (url: String) -> Unit,
) : AudioSampleSink, PlaybackPcmListener {
    private val appContext = context.applicationContext
    private val timeline = SpeechTimeline()
    private val aligner: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "NuvioAudioSyncAlign").apply { isDaemon = true }
    }
    private val alignRunning = AtomicBoolean(false)
    private val sessionRequest = AtomicInteger(0)

    @Volatile
    private var decoder: AudioSyncDecoder? = null

    @Volatile
    private var decoderUnavailable = false

    private class Session(
        val key: String,
        val track: SubtitleSpeechTrack,
        val dialogue: List<Triple<Long, Long, String>>,
    ) {
        val tracker = AudioSyncTracker(track)

        /** Recognition locked and keeps the mapping current; speech detection only listens. */
        @Volatile
        var maintainedByRecognition = false
    }

    /** An addon or stream subtitle: an English reference, or an alternative to the chosen one. */
    data class ReferenceCandidate(
        val url: String,
        val language: String,
        val headers: Map<String, String>,
        /** Name shown to the user. */
        val label: String = "",
    )

    private val fetchPool: ExecutorService = Executors.newFixedThreadPool(3) { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "NuvioAudioSyncFetch").apply { isDaemon = true }
    }

    // English references recognition waits for. Kept apart from [fetchPool], which can hold a dozen
    // same-language alternatives, so recognition never queues behind them. One thread per reference
    // used, so a slow host holds up only its own file. Threads start on first use.
    private val referenceFetchPool: ExecutorService = Executors.newFixedThreadPool(MAX_REFERENCES) { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "NuvioAudioSyncReference").apply { isDaemon = true }
    }

    // Model initialization must not queue behind subtitle HTTP requests. Keep its existing
    // background priority so loading does not take CPU priority over video playback.
    private val recognizerLoader: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "NuvioAsrLoad").apply { isDaemon = true }
    }
    private val recognizerLock = Any()
    @Volatile private var recognizerQueueMs = 0L
    @Volatile private var recognizerAcquireMs = 0L
    @Volatile private var recognizerAcquireStartedAtMs = 0L

    @Volatile
    private var candidates: List<ReferenceCandidate> = emptyList()

    /** Parsed dialogue of reference subtitles by url; empty list = failed. */
    private val parsedReferences = ConcurrentHashMap<String, List<Triple<Long, Long, String>>>()
    private val referenceFetcher = ReferenceFetcher(
        normal = fetchPool,
        urgent = referenceFetchPool,
        parsed = parsedReferences,
        download = { url ->
            SharedReferenceCache.get(url)?.also { SyncLog.i("reference $url ready from an earlier download") }
                ?: downloadReference(url).also { SharedReferenceCache.put(url, it) }
        },
        log = SyncLog::w,
    )

    @Volatile
    private var asr: AsrSyncEngine? = null

    @Volatile
    private var recognizer: SpeechToText? = null
    private val recognizerLoading = AtomicBoolean(false)
    @Volatile private var recognizerPreparationRequested = false

    @Volatile
    private var recognizerFailed = false

    /** No recogniser for this playback: the model is missing and can't be fetched, or failed. */
    @Volatile
    private var recognizerUnavailable = false

    /** The session's subtitle has no English reference the heard words could be matched against. */
    @Volatile
    private var noUsableReference = false

    @Volatile
    private var recognizerReady = "ready"


    @Volatile
    private var estimated = false

    @Volatile
    private var liveOnly = false



    private var lastRecognizerRefreshAtMs = 0L

    @Volatile
    private var sourceKey: String = ""
    private val preferences by lazy { appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }

    @Volatile
    private var session: Session? = null

    /** Other subtitles in the chosen one's language, tested against the audio while it is unsynced. */
    @Volatile
    private var pool: SubtitleCandidatePool? = null
    private var poolSession: Session? = null
    private val poolRequested = ConcurrentHashMap.newKeySet<String>()

    /** Subtitles switched away from on this stream; picking one again is respected. */
    private val switchedAway = ConcurrentHashMap.newKeySet<String>()

    /** Mapping of the subtitle being switched to, adopted as soon as its session starts. */
    private class Handover(val key: String, val model: SubtitleSyncModel, val method: String, val notice: String)

    @Volatile
    private var handover: Handover? = null


    @Volatile
    private var mediaDurationMs = 0L

    /** The stream, as the player reads it, for sampling audio away from the playhead. */
    private class SpotSource(
        val sourceKey: String,
        val uri: Uri,
        val dataSourceFactory: DataSource.Factory,
        val extractorsFactory: ExtractorsFactory,
    )

    @Volatile
    private var spotSource: SpotSource? = null
    private val spotSamplingStarted = AtomicBoolean(false)

    /** Bytes sampling has read from this stream so far: later runs share one budget. */
    @Volatile
    private var spotBytesUsed = 0L

    /** Sampling runs cut short by a subtitle change and started again for this stream. */
    private val spotRestarts = AtomicInteger(0)



    @Volatile
    private var model: SubtitleSyncModel? = null

    @Volatile
    private var manualDelayAtLockMs = 0

    @Volatile
    private var lockedAtElapsedMs = 0L


    /** How much of the film may be read ahead of playback; the user's setting. */
    @Volatile
    var samplingMode: AudioSamplingMode = AudioSamplingMode.LOW
        set(value) {
            field = value
            // Declined while sampling was off earlier in this stream: try again now that it is allowed.
            if (value != AudioSamplingMode.OFF && spotDeclined) {
                spotDeclined = false
                spotSamplingStarted.set(false)
            }
        }

    @Volatile
    private var spotDeclined = false

    @Volatile
    var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) model = null
        }

    @Volatile
    private var playbackPositionMs = 0L

    @Volatile
    private var selectedAudioFormat: Format? = null

    @Volatile
    private var provisionalAudioFormat: Format? = null

    @Volatile
    private var released = false

    private var lastAlignedSession: Session? = null
    private var lastAlignVersion = -1L
    private var lastAlignAtMs = 0L

    /** Wraps [factory] so audio is tapped while it is demuxed. */
    fun wrap(factory: ExtractorsFactory): ExtractorsFactory = AudioSyncExtractorsFactory(factory, this)

    /** Wraps the player's audio output so its decoded audio can be used when demux-time capture can't. */
    fun wrapAudioSink(sink: AudioSink): AudioSink = PlaybackAudioTap(sink, this)

    /**
     * Whether audio is analysed before any subtitle session exists, so a session that starts later
     * finds audio already heard. Off: nothing is decoded until a session starts.
     */
    @Volatile
    var listensBeforeSession: Boolean = true

    /**
     * No audio reaches this controller while the film plays (the mpv engine plays it outside
     * ExoPlayer, so neither the demux nor the output tap sees it). The film is only heard through
     * the spots sampled ahead of playback, which then start without waiting for heard audio.
     */
    @Volatile
    var spotOnly: Boolean = false

    /**
     * Language of the audio track the player outside ExoPlayer plays ([spotOnly]), used to pick
     * the same track out of the sampled stream and to judge whether the speech model applies.
     */
    @Volatile
    var spotOnlyAudioLanguage: String? = null
        set(value) {
            val changed = SubtitleCandidatePool.languageKey(value) != SubtitleCandidatePool.languageKey(field)
            field = value
            if (changed && spotOnly) {
                // Another track (a dub, say): the earlier pick and what was heard on it no longer apply.
                provisionalAudioFormat = null
                updateListening()
            }
        }

    /** The stream this controller listens to (see [onSourceChanged]). */
    val currentSourceKey: String get() = sourceKey

    /** The mapping currently applied to the session's subtitle (subtitle time -> media time), if any. */
    fun currentModel(): SubtitleSyncModel? = model.takeIf { enabled && session != null }

    /** True when [currentModel] is confirmed rather than an early provisional estimate. */
    val isModelConfirmed: Boolean get() = enabled && session != null && model != null && !estimated

    /** Extra delay to add to the user's subtitle delay at the current playback position. */
    fun autoDelayMs(): Int {
        val current = model ?: return 0
        if (!enabled || session == null) return 0
        val totalMs = current.delayUsAt(playbackPositionMs * 1_000L) / 1_000.0
        return (totalMs - manualDelayAtLockMs).roundToInt()
    }

    /** Called on the main thread from the player's periodic snapshot; [durationMs] <= 0 when unknown. */
    fun onPlaybackPosition(positionMs: Long, durationMs: Long = 0L) {
        playbackPositionMs = positionMs.coerceAtLeast(0L)
        if (durationMs > 0 && durationMs != mediaDurationMs) {
            mediaDurationMs = durationMs
            pool?.mediaDurationMs = durationMs
        }
        asr?.onPlayhead(playbackPositionMs)
        scheduleAlignment()
        maybeSampleSpots()
        val now = SystemClock.elapsedRealtime()
        if (now - lastRecognizerRefreshAtMs >= RECOGNIZER_REFRESH_MS) {
            lastRecognizerRefreshAtMs = now
            refreshRecognizer()
        }
    }

    /** Pick up a model downloaded from Settings while this session is running. */
    private fun refreshRecognizer() {
        if (session != null && enabled && !released && recognizer == null &&
            SubtitleSyncStatus.speechModel.value.downloaded && !audioIsForeign()) {
            ensureRecognizer()
        }
    }

    fun onSourceChanged(newSourceKey: String) {
        sourceKey = newSourceKey
        timeline.clear()
        decoder?.discontinuity()
        asr?.clear()
        selectedAudioFormat = null
        provisionalAudioFormat = null
        // Without a session, a new stream starts out worth listening to; a running session keeps
        // its verdict on references (same subtitle), while the audio language is judged afresh.
        if (session == null) noUsableReference = false
        updateListening()
        stopPool()
        switchedAway.clear()
        handover = null
        spotSamplingStarted.set(false)
        spotBytesUsed = 0L
        spotRestarts.set(0)
        spotDeclined = false
        model = null
        lockedAtElapsedMs = 0L
        lastAlignVersion = -1L
        session?.let { current ->
            val restarted = Session(current.key, current.track, current.dialogue)
            session = restarted
            startPool(restarted)
        }
    }

    /**
     * The stream the player is reading for the source [forSourceKey], so short stretches of audio from across the film can be
     * sampled ahead of playback (see [AudioSpotSampler]). Not called for streams where random
     * access would compete with playback (a local torrent engine).
     */
    fun setSpotSource(
        forSourceKey: String,
        url: String,
        dataSourceFactory: DataSource.Factory,
        extractorsFactory: ExtractorsFactory,
        localEngine: Boolean,
    ) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val path = uri?.path.orEmpty().lowercase()
        val unavailable = when {
            localEngine -> "not used for torrent streams (would slow playback)"
            uri == null || (uri.scheme != "http" && uri.scheme != "https") -> "not available for this stream"
            path.endsWith(".m3u8") || path.endsWith(".mpd") -> "not available for playlist streams"
            else -> null
        }
        spotSource = if (unavailable == null) SpotSource(forSourceKey, uri!!, dataSourceFactory, extractorsFactory) else null
    }

    /**
     * Once playback runs with some buffer, samples a few dialogue spots across the film while the
     * chosen subtitle is still unsynced. Once per stream, unless sampling is off.
     */
    private fun maybeSampleSpots() {
        if (spotSamplingStarted.get()) return
        val source = spotSource ?: return
        val current = session ?: return
        if (!enabled || released || source.sourceKey != sourceKey || (model != null && !estimated)) return
        if (mediaDurationMs < MIN_SAMPLED_FILM_MS) return
        // Spot-only playback never fills the timeline itself, so there is nothing to wait for.
        if (!spotOnly && timeline.knownFrameCount() < SAMPLE_AFTER_FRAMES) return
        if (!spotSamplingStarted.compareAndSet(false, true)) return
        if (samplingMode == AudioSamplingMode.OFF) {
            SyncLog.i("audio sampling across the film is off")
            spotDeclined = true
            return
        }
        // The spot decoder can't decode what the look-ahead decoder couldn't either, so sampling
        // would only open extra connections to the host for nothing.
        if (liveOnly) {
            SyncLog.i("audio sampling skipped: this audio can only be heard during playback")
            spotDeclined = true
            return
        }
        Thread({ sampleSpots(source, current) }, "NuvioAudioSyncSpots").apply {
            isDaemon = true
            start()
        }
    }

    private fun sampleSpots(source: SpotSource, current: Session) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val weights = runCatching { SileroVadBundle.load() }.getOrNull() ?: run {
            SyncLog.w("audio sampling skipped: speech detector weights unavailable")
            return
        }
        createDecoder()
        // One decoder per connection: each reads a different place, so their audio must not mix.
        // They are made as connections open, so a single-connection host costs a single decoder.
        val decoders = ArrayList<AudioSyncDecoder>()
        val segmenters = ArrayList<SpeechSegmenter>()
        val newWorker = {
            val analyzer = SpeechAnalyzer(SileroVad(weights), timeline)
            asr?.let { engine ->
                val segmenter = SpeechSegmenter { startFrame, samples ->
                    engine.offerSegment(startFrame, samples, spread = true)
                }
                synchronized(segmenters) { segmenters += segmenter }
                analyzer.chunkListener = segmenter
            }
            val spotDecoder = AudioSyncDecoder(analyzer, analyzer, allowVendorDecoders = false)
            synchronized(decoders) { decoders += spotDecoder }
            AudioSyncExtractorsFactory(source.extractorsFactory, SpotSink(spotDecoder))
        }
        // Metered networks get at most the low setting.
        val high = samplingMode == AudioSamplingMode.HIGH && AsrModel.isUnmetered(appContext)
        val spotCount = if (high) SPOT_COUNT else LOW_SPOT_COUNT
        val maxConnections = when {
            !high -> 1
            SpotConnections.isLowMemoryTv(appContext) -> LOW_MEMORY_SPOT_WORKERS
            else -> SPOT_WORKERS
        }
        // On high, a high-bitrate file may read past the target so each spot still holds whole
        // sentences; on low the target is also the cap.
        // A run that resumes one cut short spends what is left of the stream's budget.
        val targetBytes = (if (high) TARGET_SPOT_BYTES else LOW_SPOT_BYTES) - spotBytesUsed
        val maxBytes = (if (high) MAX_SPOT_BYTES else LOW_SPOT_BYTES) - spotBytesUsed
        if (targetBytes < MIN_RESUMED_SPOT_BYTES) {
            SyncLog.i("audio sampling budget for this stream is spent")
            return
        }
        var planned = 0
        var sampled = 0
        try {
            // Every subtitle at hand shows roughly where people talk, even with its timing off.
            val tracks = listOf(current.track) + englishCandidates()
                .mapNotNull { (parsedReferences[it.url] ?: SharedReferenceCache.get(it.url))?.takeIf { cues -> cues.isNotEmpty() } }
                .map(SubtitleSpeechTrack::fromCues)
            // A fresh start covers the opening itself; after a resume any part of the film helps.
            val coveredMs = timeline.segments(fromFrame = SpeechTimeline.frameForTimeUs(playbackPositionMs * 1_000L))
                .firstOrNull()?.let { (it.toFrame * SpeechTimeline.FRAME_DURATION_MS).toLong() } ?: 0L
            val notBeforeMs = if (playbackPositionMs < RESUME_THRESHOLD_MS) coveredMs + SPOT_MS else 0L
            // Places an earlier, interrupted run already sampled are known and skipped.
            val spots = DialogueSpotPlanner.plan(tracks, mediaDurationMs, spotCount, SPOT_MS, notBeforeMs)
                .filterNot { spotMs ->
                    val from = SpeechTimeline.frameForTimeUs(spotMs * 1_000L)
                    val to = SpeechTimeline.frameForTimeUs((spotMs + SPOT_MS) * 1_000L)
                    timeline.knownFramesIn(from, to) * 2 >= to - from
                }
            planned = spots.size
            if (spots.isEmpty()) {
                SyncLog.i("audio sampling skipped: no suitable dialogue spots")
                return
            }
            SyncLog.i("sampling audio at ${spots.map { it / 1_000 }}s")
            val sampler = AudioSpotSampler(source.uri, source.dataSourceFactory, targetBytes, maxBytes, MIN_SPOT_AUDIO_MS) {
                SyncLog.i("host refused another connection ($it); sampling continues on the open ones")
            }
            val result = sampler.run(
                spotsMs = spots,
                spotMs = SPOT_MS,
                maxWorkers = maxConnections,
                newWorker = newWorker,
                mayAddConnection = ::playbackBufferedAhead,
                isCancelled = {
                    released || !enabled || sourceKey != source.sourceKey || session == null ||
                        (model != null && !estimated)
                },
            ) { _, _ -> }
            sampled = result.sampled
            spotBytesUsed += result.bytes
            SyncLog.i(
                "sampled ${result.sampled} spots over ${result.connections} connection(s), " +
                    "${result.bytes / 1_000_000} MB, failure=${result.failure}"
            )
            synchronized(decoders) { decoders.toList() }.forEach { it.awaitDrained(DRAIN_TIMEOUT_MS) }
            // The last spot of each connection ends mid-speech more often than not.
            synchronized(segmenters) { segmenters.toList() }.forEach(SpeechSegmenter::flush)
        } catch (error: Throwable) {
            SyncLog.w("audio sampling failed: ${error.message}")
        } finally {
            synchronized(decoders) { decoders.toList() }.forEachIndexed { index, sampledDecoder ->
                SyncLog.i("sampling decoder[$index] ${sampledDecoder.diagnostics()}")
                sampledDecoder.release()
            }
        }
        // Cut short because the subtitle changed (its session ended) while this stream still plays
        // unsynced: the next subtitle's session samples the remaining spots instead of only
        // listening to playback.
        val cutShort = sampled < planned && session !== current && sourceKey == source.sourceKey &&
            !released && enabled && (model == null || estimated)
        if (cutShort && spotRestarts.incrementAndGet() <= MAX_SPOT_RESTARTS) {
            SyncLog.i("audio sampling cut short by a subtitle change ($sampled of $planned spots); resuming for the next subtitle")
            spotSamplingStarted.set(false)
        }
    }

    /**
     * Whether playback has enough audio buffered ahead that one more sampling connection won't
     * starve it. Streams whose audio can't be captured ahead give no such signal and are not held back.
     */
    private fun playbackBufferedAhead(): Boolean {
        if (liveOnly || spotOnly) return true
        val playheadFrame = SpeechTimeline.frameForTimeUs(playbackPositionMs * 1_000L)
        val known = timeline.knownFramesIn(playheadFrame, playheadFrame + BUFFERED_AHEAD_FRAMES)
        return known >= BUFFERED_AHEAD_FRAMES * 8 / 10
    }

    /** Feeds sampled audio of the playing track to [decoder], waiting when it is busy. */
    private inner class SpotSink(private val decoder: AudioSyncDecoder) : AudioSampleSink {
        override fun wantsSamples(format: Format): Boolean {
            if (!enabled || released) return false
            val playing = selectedAudioFormat ?: provisionalAudioFormat
                ?: (if (spotOnly) adoptSpotOnlyAudio(format) else null)
                ?: return false
            return matches(format, playing)
        }

        override fun onSample(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int) {
            while (!decoder.offer(format, timeUs, data, offset, size)) {
                if (!decoder.accepts(format)) return
                Thread.sleep(SPOT_BACKOFF_MS)
            }
        }

        override fun onDiscontinuity() {
            decoder.discontinuity()
        }
    }

    /**
     * Spot-only: the first sampled audio track in the language the player plays (or the first
     * one, when either language is unknown) stands in for the playing track.
     */
    private fun adoptSpotOnlyAudio(format: Format): Format? {
        val adopted = synchronized(spotOnlyAudioLock) {
            provisionalAudioFormat?.let { return it }
            val wanted = SubtitleCandidatePool.languageKey(spotOnlyAudioLanguage?.takeIf { it.lowercase() !in UNKNOWN_LANGUAGES })
            val offered = SubtitleCandidatePool.languageKey(format.language?.takeIf { it.lowercase() !in UNKNOWN_LANGUAGES })
            if (wanted != null && offered != null && wanted != offered) return null
            format.also { provisionalAudioFormat = it }
        }
        SyncLog.d("spot-only audio track: ${describe(adopted)}")
        updateListening()
        return adopted
    }
    private val spotOnlyAudioLock = Any()

    fun onAudioTrackSelected(format: Format?) {
        if (format == null || selectedAudioFormat?.let { matches(it, format) } == true) return
        val previous = selectedAudioFormat
        selectedAudioFormat = format
        SyncLog.d("audio track selected: ${describe(format)}")
        if (previous != null) {
            // Another track (a dub, say): speech heard on the previous one must not mix with this one's.
            timeline.clear()
            decoder?.discontinuity()
            asr?.forgetHeard()
        }
        updateListening()
        if (enabled && !audioIsForeign()) {
            if (session != null) ensureRecognizer()
            else if (recognizerPreparationRequested) prepareRecognizer()
        }
    }

    /**
     * True only when the playing audio is tagged with a language other than English: the speech
     * model understands English only. Untagged audio is assumed to be English.
     */
    private fun audioIsForeign(): Boolean {
        val language = ((selectedAudioFormat ?: provisionalAudioFormat)?.language ?: spotOnlyAudioLanguage.takeIf { spotOnly })
            ?.trim()?.lowercase()
        if (language.isNullOrEmpty() || language in UNKNOWN_LANGUAGES) return false
        return !isEnglish(language)
    }

    /** Keeps speech for recognition only while recognition can help (see [AsrSyncEngine.setListening]). */
    private fun updateListening() {
        asr?.setListening(!recognizerUnavailable && !noUsableReference && !audioIsForeign())
    }

    /**
     * Starts syncing the subtitle identified by [key] with the given parsed cues. The dialogue track
     * is built and the model loaded on the background thread; the session becomes active afterwards
     * unless another subtitle was chosen meanwhile.
     */
    fun startSession(key: String, cues: List<CuesWithTiming>) {
        prepareRecognizer()
        val request = sessionRequest.incrementAndGet()
        session = null
        stopPool()
        val handedOver = handover?.takeIf { it.key == key }
        handover = null
        model = null
        lockedAtElapsedMs = 0L
        estimated = false
        try {
            aligner.execute {
                val dialogue = dialogueOf(cues)
                val track = SubtitleSpeechTrack.fromCues(dialogue)
                if (sessionRequest.get() != request || released) return@execute
                if (track.size < MIN_TRACK_CUES) {
                    SyncLog.i("subtitle $key has only ${track.size} dialogue cues; audio sync skipped")
                    notify(AudioSyncStatus.Failed)
                    return@execute
                }
                if (createDecoder() == null) {
                    notify(AudioSyncStatus.Failed)
                    return@execute
                }
                if (sessionRequest.get() != request) return@execute
                val started = Session(key, track, dialogue)
                session = started
                SyncLog.i("sync session started for $key with ${track.size} dialogue cues")
                if (!enabled) return@execute
                when {
                    handedOver != null -> adoptHandover(started, handedOver)
                    applyRemembered(started) -> Unit
                    else -> {
                        notify(AudioSyncStatus.Listening)
                        startPool(started)
                    }
                }
                startRecognition(started)
            }
        } catch (_: Exception) {
            // Executor already shut down: the surface is being released.
        }
    }

    private fun dialogueOf(cues: List<CuesWithTiming>): List<Triple<Long, Long, String>> =
        cues.mapNotNull { entry ->
            val startUs = entry.startTimeUs
            if (startUs == C.TIME_UNSET) return@mapNotNull null
            val endUs = when {
                entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs
                entry.durationUs != C.TIME_UNSET -> startUs + entry.durationUs
                else -> return@mapNotNull null
            }
            val text = entry.cues.joinToString("\n") { it.text?.toString().orEmpty() }
            Triple(startUs / 1_000L, endUs / 1_000L, text)
        }

    fun stopSession() {
        recognizerPreparationRequested = false
        sessionRequest.incrementAndGet()
        stopPool()
        lockedAtElapsedMs = 0L
        asr?.stopSession()
        session?.let { current ->
            val evidence = timeline.diagnostics()
            SyncLog.i("sync session stopped analysed=${evidence.analysedSeconds.toInt()}s" +
                " speechActivity=${evidence.speechSeconds.toInt()}s waiting=${current.tracker.waitingReason}" +
                " ${decoder?.diagnostics().orEmpty()}")
        }
        session = null
        model = null
    }

    fun activeSessionKey(): String? = session?.key

    fun release() {
        released = true
        session = null
        stopPool()
        model = null
        decoder?.release()
        asr?.release()
        aligner.shutdownNow()
        fetchPool.shutdownNow()
        referenceFetchPool.shutdownNow()
        // Do not interrupt native initialization. A late acquisition is returned below by
        // its worker, and the cache can serve the next player instead of starting over.
        recognizerLoader.shutdown()
        // Shared with later players and closed only after a while unused, so a worker that is
        // mid-decode finishes safely and the next episode starts with the model loaded.
        synchronized(recognizerLock) {
            if (recognizer != null) SharedRecognizer.release()
            recognizer = null
        }
    }

    /**
     * Downloads the English references recognition will use ([bridgeReferences] takes the same
     * ones), as soon as they are known: by the time audio sync takes over, usually right after
     * playback starts, they are ready instead of downloading then. Each file downloads once.
     */
    private fun prefetchReferences() {
        if (!AsrModel.isReady(appContext)) return
        englishCandidates().take(MAX_REFERENCES).forEach { fetchReference(it, urgent = true, onReady = null) }
    }

    /** Subtitles the user could pick; English ones become recognition references. */
    fun setReferenceSubtitles(list: List<ReferenceCandidate>) {
        candidates = list
        if (list.isNotEmpty()) prepareRecognizer()
        prefetchReferences()
        // Subtitles listed after the pick join the running search.
        session?.let { current -> if (model == null || estimated) startPool(current) }
    }

    /**
     * Starts (or extends) testing the other subtitles in the chosen one's language against the
     * audio, so a file that fits can replace one that never will.
     */
    private fun startPool(current: Session) {
        if (!enabled || released || session !== current || current.key in switchedAway) return
        val language = SubtitleCandidatePool.languageKey(candidates.firstOrNull { it.url == current.key }?.language)
            ?: return
        val alternatives = candidates
            .filter { it.url != current.key && SubtitleCandidatePool.languageKey(it.language) == language }
            .distinctBy { it.url }
            .take(MAX_ALTERNATIVES)
        if (alternatives.isEmpty()) return
        val active = synchronized(poolRequested) {
            if (poolSession !== current) {
                poolRequested.clear()
                poolSession = current
                pool = SubtitleCandidatePool(current.track) { SyncLog.i(it) }.apply { mediaDurationMs = this@AudioSubtitleSyncController.mediaDurationMs }
                SyncLog.i("testing ${alternatives.size} other $language subtitles against the audio")
            }
            pool
        } ?: return
        for (alternative in alternatives) {
            if (!poolRequested.add(alternative.url)) continue
            fetchReference(alternative) { dialogue ->
                if (pool === active) active.addCandidate(alternative.url, dialogue)
            }
        }
        // English references the recognised words can pin to the audio.
        if (!AsrModel.isReady(appContext)) return
        englishCandidates().take(MAX_REFERENCES).forEach { reference ->
            if (reference.url != current.key && poolRequested.add(reference.url)) {
                fetchReference(reference) { dialogue -> if (pool === active) active.addReference(reference.url, dialogue) }
            }
        }
    }

    private fun stopPool() {
        synchronized(poolRequested) {
            pool = null
            poolSession = null
            poolRequested.clear()
        }
    }

    /** Scores the alternatives while the chosen subtitle is unconfirmed; switches to one that fits. */
    private fun updatePool(current: Session) {
        val alternatives = pool ?: return
        if (session !== current || (model != null && !estimated)) return
        val winner = alternatives.update(timeline, asr?.heardWords(), SystemClock.elapsedRealtime()) ?: return
        if (session !== current || !enabled || released) return
        if (winner.chosen) {
            stopPool()
            if (model == null) manualDelayAtLockMs = manualDelayMs()
            current.tracker.adopt(winner.model)
            model = winner.model
            estimated = false
            lockedAtElapsedMs = SystemClock.elapsedRealtime()
            remember(current.key, winner.model)
            SyncLog.i("LOCK of the chosen subtitle through the recognised reference: ${winner.model}")
            notify(
                AudioSyncStatus.Synced(
                    offsetMs = winner.model.delayUsAt(playbackPositionMs * 1_000L) / 1_000L,
                    rateCorrected = winner.model.segments.first().scale != 1.0,
                ),
            )
            return
        }
        val label = candidates.firstOrNull { it.url == winner.key }?.label?.takeIf { it.isNotBlank() } ?: "another file"
        SyncLog.i("SWITCHING subtitle ${current.key} -> ${winner.key} via ${winner.method}: ${winner.model}")
        switchedAway += current.key
        stopPool()
        remember(winner.key, winner.model)
        val notice = "Switched to a subtitle that matches the audio: $label"
        handover = Handover(winner.key, winner.model, winner.method, notice)
        requestSwitch(winner.key)
    }

    /** Applies the mapping found for the subtitle that was switched to. */
    private fun adoptHandover(current: Session, handedOver: Handover) {
        manualDelayAtLockMs = manualDelayMs()
        current.tracker.adopt(handedOver.model)
        model = handedOver.model
        estimated = false
        lockedAtElapsedMs = SystemClock.elapsedRealtime()
        SyncLog.i("adopted mapping of switched-to subtitle ${handedOver.model}")
        notify(
            AudioSyncStatus.Synced(
                offsetMs = handedOver.model.delayUsAt(playbackPositionMs * 1_000L) / 1_000L,
                rateCorrected = handedOver.model.segments.first().scale != 1.0,
            ),
        )
    }

    private fun englishCandidates(): List<ReferenceCandidate> =
        (candidates.filter { isEnglish(it.language) } + fallbackCandidates).distinctBy { it.url }

    @Volatile
    private var contentKey: String? = null

    /** English subtitles from the public OpenSubtitles service, independent of addon language settings. */
    @Volatile
    private var fallbackCandidates: List<ReferenceCandidate> = emptyList()

    @Volatile
    private var fallbackState = FallbackState.Idle
    private val fallbackListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    private enum class FallbackState { Idle, Loading, Done }

    /** The title being played, used to look up English references when the addons return none. */
    fun setContent(type: String?, videoId: String?) {
        val key = if (type.isNullOrBlank() || videoId.isNullOrBlank()) null else "$type|$videoId"
        if (key == contentKey) return
        contentKey = key
        fallbackCandidates = emptyList()
        fallbackState = FallbackState.Idle
        fallbackListeners.clear()
        if (key != null && enabled) loadFallbackCandidates(key, type!!, videoId!!)
    }

    private fun loadFallbackCandidates(key: String, type: String, videoId: String) {
        fallbackState = FallbackState.Loading
        try {
            fetchPool.execute {
                val canonicalType = if (type.equals("tv", ignoreCase = true)) "series" else type.lowercase()
                val url = "$OPEN_SUBTITLES_FALLBACK/subtitles/$canonicalType/$videoId.json"
                val found = runCatching {
                    val body = runBlocking { AutomaticSubtitleSync.downloadSubtitleBody(url = url, headers = emptyMap()) }
                    val array = org.json.JSONObject(body).optJSONArray("subtitles") ?: org.json.JSONArray()
                    (0 until array.length()).mapNotNull { index ->
                        val item = array.optJSONObject(index) ?: return@mapNotNull null
                        val subtitleUrl = item.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val language = item.optString("lang")
                        if (!isEnglish(language)) return@mapNotNull null
                        ReferenceCandidate(subtitleUrl, language, emptyMap())
                    }.take(MAX_FALLBACK_REFERENCES)
                }.getOrElse {
                    SyncLog.w("English reference lookup failed for $videoId: ${it.message}")
                    emptyList()
                }
                if (contentKey != key) return@execute
                SyncLog.i("English reference lookup for $videoId found ${found.size}")
                fallbackCandidates = found
                fallbackState = FallbackState.Done
                prefetchReferences()
                fallbackListeners.forEach { runCatching(it) }
                fallbackListeners.clear()
            }
        } catch (_: Exception) {
            fallbackState = FallbackState.Done
        }
    }

    /** Subtitle services throttle bursts (HTTP 429): wait a little and try again. */
    private fun downloadWithRetry(candidate: ReferenceCandidate): String {
        var attempt = 0
        while (true) {
            try {
                return runBlocking { AutomaticSubtitleSync.downloadSubtitleBody(url = candidate.url, headers = candidate.headers) }
            } catch (error: Exception) {
                val throttled = error.message.orEmpty().contains("429")
                if (!throttled || ++attempt > DOWNLOAD_RETRIES || released) throw error
                Thread.sleep(DOWNLOAD_RETRY_DELAY_MS * attempt)
            }
        }
    }

    /** See [ReferenceFetcher]: each file downloads once; [urgent] ones skip the alternatives' queue. */
    private fun fetchReference(
        candidate: ReferenceCandidate,
        urgent: Boolean = false,
        onReady: ((List<Triple<Long, Long, String>>) -> Unit)?,
    ) {
        referenceHeaders.putIfAbsent(candidate.url, candidate)
        referenceRequestedAt.putIfAbsent(candidate.url, SystemClock.elapsedRealtime())
        referenceFetcher.fetch(candidate.url, urgent, onReady)
    }

    private val referenceHeaders = ConcurrentHashMap<String, ReferenceCandidate>()

    private fun downloadReference(url: String): List<Triple<Long, Long, String>> {
        val candidate = referenceHeaders[url] ?: ReferenceCandidate(url = url, language = "", headers = emptyMap())
        val startedAt = SystemClock.elapsedRealtime()
        val queuedMs = referenceRequestedAt[url]?.let { startedAt - it } ?: -1L
        return runCatching {
            val body = downloadWithRetry(candidate)
            val downloadedAt = SystemClock.elapsedRealtime()
            dialogueOf(parseSidecarTimedCuesRobust(body, url).cues).also { dialogue ->
                SyncLog.i(
                    "reference $url: queued ${queuedMs}ms, downloaded in ${downloadedAt - startedAt}ms, " +
                        "parsed in ${SystemClock.elapsedRealtime() - downloadedAt}ms (${dialogue.size} lines)"
                )
            }
        }.getOrElse {
            SyncLog.w("reference download failed for $url after ${SystemClock.elapsedRealtime() - startedAt}ms: ${it.message}")
            emptyList()
        }
    }

    /** When each reference was first asked for, for the log's queue times. */
    private val referenceRequestedAt = ConcurrentHashMap<String, Long>()

    /**
     * Speech recognition: English words heard in the dialogue are matched against an English
     * subtitle (the target itself, or a reference bridged to the target by timing pattern).
     */
    private fun startRecognition(current: Session) {
        val engine = asr ?: return
        noUsableReference = false
        updateListening()
        // The speech model understands English only: it is not loaded for foreign-language audio
        // (it is if the audio track changes to English, see [onAudioTrackSelected]).
        if (!audioIsForeign()) ensureRecognizer()
        val english = isEnglish(candidates.firstOrNull { it.url == current.key }?.language) ||
            looksEnglish(current.dialogue)
        if (english) {
            engine.startSession(current.track, listOf(ReferenceSubtitle(current.key, current.dialogue, null)))
            return
        }
        engine.startSession(current.track, emptyList())
        val references = englishCandidates().take(MAX_REFERENCES)
        if (references.isEmpty()) {
            if (fallbackState == FallbackState.Loading) {
                // English references are still being looked up: recognise speech meanwhile.
                engine.expectReferences(REFERENCE_WAIT_MS)
                fallbackListeners += { if (session === current) bridgeReferences(current, engine) }
                return
            }
            SyncLog.i("no English reference subtitle available; recognition idle")
            noUsableReference = true
            updateListening()
            return
        }
        bridgeReferences(current, engine)
    }

    private fun bridgeReferences(current: Session, engine: AsrSyncEngine) {
        val references = englishCandidates().take(MAX_REFERENCES)
        if (references.isEmpty()) {
            engine.expectReferences(0L)
            noUsableReference = true
            updateListening()
            return
        }
        val matched = java.util.concurrent.atomic.AtomicInteger(0)
        val finished = java.util.concurrent.atomic.AtomicInteger(0)
        // Speech is recognised while they download, so the words are ready when one matches.
        engine.expectReferences(REFERENCE_WAIT_MS)
        for (candidate in references) {
            fetchReference(candidate, urgent = true) { dialogue ->
                if (session !== current) return@fetchReference
                val bridgingAt = SystemClock.elapsedRealtime()
                val referenceTrack = SubtitleSpeechTrack.fromCues(dialogue)
                val bridge = SubtitleBridge.align(current.track, referenceTrack)
                SyncLog.i("reference ${candidate.url}: bridge=$bridge in ${SystemClock.elapsedRealtime() - bridgingAt}ms")
                pool?.addReference(candidate.url, dialogue)
                if (bridge != null) {
                    matched.incrementAndGet()
                    engine.addReference(ReferenceSubtitle(candidate.url, dialogue, bridge))
                    if (noUsableReference) {
                        noUsableReference = false
                        updateListening()
                    }
                }
                val done = finished.incrementAndGet()
                if (done == references.size && matched.get() == 0) {
                    engine.expectReferences(0L)
                    noUsableReference = true
                    updateListening()
                }
            }
        }
    }

    /**
     * Starts an available model before reference HTTP or alignment work, on its own worker.
     * Speculative preparation never downloads a model. The confirmed session may do that later.
     */
    fun prepareRecognizer() {
        recognizerPreparationRequested = true
        if (enabled && !released && !audioIsForeign() && AsrModel.isReady(appContext)) ensureRecognizer()
    }

    private fun ensureRecognizer() {
        if (recognizer != null || released || recognizerFailed) return
        if (!recognizerLoading.compareAndSet(false, true)) return
        recognizerAcquireStartedAtMs = 0L
        val requestedAt = SystemClock.elapsedRealtime()
        try {
            recognizerLoader.execute {
                try {
                    if (released || !enabled) return@execute
                    recognizerQueueMs = SystemClock.elapsedRealtime() - requestedAt
                    recognizerUnavailable = false
                    updateListening()
                    // The model is only downloaded from Settings, never during playback.
                    if (!AsrModel.isReady(appContext)) {
                        SyncLog.i("speech model not downloaded; using speech detection only")
                        recognizerUnavailable = true
                        updateListening()
                        return@execute
                    }
                    if (released || !enabled) return@execute
                    // The optimized ORT model, or the upstream ONNX one when only that could be fetched.
                    val modelDirectory = AsrModel.directory(appContext)
                    val alreadyLoaded = SharedRecognizer.isLoaded
                    val startedAt = SystemClock.elapsedRealtime()
                    recognizerAcquireStartedAtMs = startedAt
                    SyncLog.i("speech model loading queueMs=$recognizerQueueMs cached=$alreadyLoaded")
                    val loaded = SharedRecognizer.acquire(
                        modelDirectory,
                        RECOGNIZER_THREADS,
                        lowMemory = SpotConnections.isLowMemoryTv(appContext),
                    )
                    recognizerAcquireMs = SystemClock.elapsedRealtime() - startedAt
                    synchronized(recognizerLock) {
                        if (released || !enabled) {
                            SharedRecognizer.release()
                            return@execute
                        }
                        val seconds = recognizerAcquireMs / 100 / 10.0
                        recognizerReady = if (alreadyLoaded) "ready (${loaded.modelFormat})" else "ready (${loaded.modelFormat}, loaded in ${seconds}s)"
                        recognizer = loaded
                        asr?.setRecognizer(loaded)
                    }
                    SyncLog.i("speech recognizer $recognizerReady queueMs=$recognizerQueueMs acquireMs=$recognizerAcquireMs")
                    if (recognizerAcquireMs >= 10_000L) {
                        SyncLog.w("speech model acquisition exceeded 10s: ${recognizerAcquireMs}ms")
                    }
                } catch (error: Throwable) {
                    SyncLog.w("speech recognizer unavailable: ${error.message}")
                    recognizerFailed = true
                    recognizerUnavailable = true
                    updateListening()
                } finally {
                    recognizerLoading.set(false)
                }
            }
        } catch (_: Exception) {
            recognizerLoading.set(false)
        }
    }

    private fun onAsrLock(result: AsrLock) {
        val current = session ?: return
        if (!enabled || released) return
        // A provisional estimate never replaces a confirmed sync (e.g. one found through the pool).
        if (!result.final && model != null && !estimated) return
        val adopted = SubtitleSyncModel(result.segments)
        val previous = model
        val wasFinal = current.maintainedByRecognition
        if (previous == null) manualDelayAtLockMs = manualDelayMs()
        current.tracker.adopt(adopted)
        if (result.final) current.maintainedByRecognition = true
        model = adopted
        // Until the frame rate is known the mapping is an estimate: sampling across the film and
        // the search among other subtitles keep running, and nothing is remembered yet.
        estimated = !result.final
        if (result.final) {
            lockedAtElapsedMs = SystemClock.elapsedRealtime()
            remember(current.key, adopted)
        }
        val offsetMs = adopted.delayUsAt(playbackPositionMs * 1_000L) / 1_000L
        SyncLog.i("RECOGNITION LOCK $adopted via ${result.referenceKey} fine=${result.fineTuned} final=${result.final}")
        val previousOffsetMs = previous?.delayUsAt(playbackPositionMs * 1_000L)?.div(1_000L)
        val moved = previousOffsetMs == null || kotlin.math.abs(previousOffsetMs - offsetMs) >= NOTIFY_CHANGE_MS
        when {
            result.final && wasFinal -> if (moved) notify(AudioSyncStatus.Adjusted(offsetMs = offsetMs))
            result.final -> notify(AudioSyncStatus.Synced(offsetMs = offsetMs, rateCorrected = result.scale != 1.0))
            moved -> notify(AudioSyncStatus.Estimated(offsetMs = offsetMs))
        }
    }

    /** Versioned: syncs saved by earlier builds could hold a 0.1% stretch found on too little audio. */
    private fun rememberKey(subtitleKey: String): String = "$REMEMBER_PREFIX${sourceKey.hashCode()}:${subtitleKey.hashCode()}"

    /**
     * Stored as "savedAtMs;" followed by "fromMs,scale,shiftMs" per segment, separated by "|".
     * Stream URLs often change between sessions, so only the most recently used are kept.
     */
    private fun remember(subtitleKey: String, synced: SubtitleSyncModel) {
        val segments = synced.segments.joinToString("|") { "${it.fromMediaMs},${it.scale},${it.shiftMs}" }
        runCatching { store(rememberKey(subtitleKey), segments) }
    }

    private fun store(key: String, segments: String) {
        val editor = preferences.edit().putString(key, "${System.currentTimeMillis()};$segments")
        val others = preferences.all.filterKeys { it != key }
        // Entries of earlier formats, then the least recently used beyond the limit.
        others.keys.filterNot { it.startsWith(REMEMBER_PREFIX) }.forEach(editor::remove)
        others.filterKeys { it.startsWith(REMEMBER_PREFIX) }
            .map { (stored, value) -> stored to ((value as? String)?.substringBefore(';')?.toLongOrNull() ?: 0L) }
            .sortedByDescending { it.second }
            .drop(MAX_REMEMBERED - 1)
            .forEach { (stored, _) -> editor.remove(stored) }
        editor.apply()
    }

    private fun parseRemembered(stored: String): SubtitleSyncModel? {
        val segments = stored.substringAfter(';').split('|').map { entry ->
            val parts = entry.split(',')
            SubtitleSyncSegment(
                fromMediaMs = parts.getOrNull(0)?.toLongOrNull() ?: return null,
                scale = parts.getOrNull(1)?.toDoubleOrNull() ?: return null,
                shiftMs = parts.getOrNull(2)?.toDoubleOrNull() ?: return null,
            )
        }
        return segments.takeIf { it.isNotEmpty() }?.let(::SubtitleSyncModel)
    }

    /** Applies the mapping found the last time this subtitle played on this stream. */
    private fun applyRemembered(current: Session): Boolean {
        val stored = runCatching { preferences.getString(rememberKey(current.key), null) }.getOrNull() ?: return false
        val restored = parseRemembered(stored) ?: return false
        // Used again: keep it among the most recent.
        runCatching { store(rememberKey(current.key), stored.substringAfter(';')) }
        val scale = restored.segments.first().scale
        manualDelayAtLockMs = manualDelayMs()
        current.tracker.adopt(restored)
        model = restored
        estimated = false
        SyncLog.i("restored remembered sync $restored")
        notify(AudioSyncStatus.Synced(offsetMs = restored.delayUsAt(playbackPositionMs * 1_000L) / 1_000L, rateCorrected = scale != 1.0))
        return true
    }

    /** Before any subtitle is picked, listen to the first minutes so a pick can sync at once. */
    private fun listening(): Boolean =
        session != null || (listensBeforeSession && timeline.knownFrameCount() < PRE_SESSION_FRAMES)

    override fun wantsSamples(format: Format): Boolean {
        if (!enabled || released || decoderUnavailable || !listening()) return false
        val selected = selectedAudioFormat
        if (selected != null) return matches(format, selected)
        val provisional = provisionalAudioFormat ?: format.also { provisionalAudioFormat = it }
        return matches(format, provisional)
    }

    override fun onSample(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int) {
        if (!inDutyWindow(timeUs)) return
        val decoder = decoder ?: createDecoder() ?: return
        decoder.offer(format, timeUs, data, offset, size)
    }

    override fun onDiscontinuity() {
        decoder?.discontinuity()
    }

    /**
     * Playback audio is only analysed where the look-ahead capture left the timeline unknown: codecs
     * without a usable second decoder, HLS/DASH streams, or gaps. Otherwise it is skipped cheaply.
     */
    override fun wantsPlaybackPcm(mediaTimeUs: Long, durationUs: Long): Boolean {
        if (!enabled || released || decoder == null || !listening()) return false
        if (mediaTimeUs < 0 || !inDutyWindow(mediaTimeUs)) return false
        return timeline.needsAnalysis(mediaTimeUs, durationUs)
    }

    override fun onPlaybackPcm(mono: FloatArray, frames: Int, sampleRate: Int, mediaTimeUs: Long) {
        decoder?.offerPlaybackPcm(mono, frames, sampleRate, mediaTimeUs)
    }

    /**
     * Once a lock has been refined over a long stretch, analyse one minute in three: enough to follow
     * later jumps while saving most of the CPU.
     */
    private fun inDutyWindow(timeUs: Long): Boolean {
        if (session?.tracker?.model == null || lockedAtElapsedMs == 0L) return true
        if (SystemClock.elapsedRealtime() - lockedAtElapsedMs < FULL_ANALYSIS_AFTER_LOCK_MS) return true
        return (timeUs / DUTY_WINDOW_US) % DUTY_CYCLE == 0L
    }

    @Synchronized
    private fun createDecoder(): AudioSyncDecoder? {
        decoder?.let { return it }
        if (decoderUnavailable || released) return null
        val weights = runCatching { SileroVadBundle.load() }.onFailure {
            SyncLog.w("could not load VAD weights: ${it.message}")
        }.getOrNull()
        if (weights == null) {
            decoderUnavailable = true
            notify(AudioSyncStatus.Failed)
            return null
        }
        val analyzer = SpeechAnalyzer(SileroVad(weights), timeline)
        val liveAnalyzer = SpeechAnalyzer(SileroVad(weights), timeline)
        val engine = AsrSyncEngine(
            timeline = timeline,
            onLock = ::onAsrLock,
            log = { SyncLog.i(it) },
            workerSetup = { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) },
        )
        // Publish the engine and attach any model under the same lock as model completion.
        // Otherwise both threads can see the other's field as null and miss the handoff.
        synchronized(recognizerLock) {
            recognizer?.let(engine::setRecognizer)
            asr = engine
        }
        analyzer.chunkListener = SpeechSegmenter(engine::offerSegment)
        liveAnalyzer.chunkListener = SpeechSegmenter(engine::offerSegment)
        return AudioSyncDecoder(analyzer, liveAnalyzer) { mime ->
            SyncLog.i("look-ahead capture unavailable for $mime; syncing from playback audio instead")
            liveOnly = true
            if (session != null) notify(AudioSyncStatus.LiveOnly(mime))
        }.also { decoder = it }
    }

    private fun scheduleAlignment() {
        val current = session ?: return
        if (!enabled || released) return
        val now = SystemClock.elapsedRealtime()
        val version = timeline.version
        // Check often while searching so an early estimate lands quickly; relax once confirmed.
        val interval = if (current.tracker.model == null) SEARCH_INTERVAL_MS else ALIGN_INTERVAL_MS
        if (current === lastAlignedSession && (version == lastAlignVersion || now - lastAlignAtMs < interval)) {
            return
        }
        if (!alignRunning.compareAndSet(false, true)) return
        lastAlignedSession = current
        lastAlignVersion = version
        lastAlignAtMs = now
        val position = playbackPositionMs
        try {
            aligner.execute {
                try {
                    align(current, position)
                } catch (error: Throwable) {
                    SyncLog.w("alignment failed: ${error.message}")
                } finally {
                    alignRunning.set(false)
                }
            }
        } catch (_: Exception) {
            alignRunning.set(false)
        }
    }

    private fun align(current: Session, positionMs: Long) {
        // Recognition maintains a confirmed mapping (including steps between parts of the film).
        if (current.maintainedByRecognition) return
        val startedAt = SystemClock.elapsedRealtime()
        val outcome = current.tracker.update(timeline, positionMs)
        if (session !== current) return
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        when (outcome) {
            is AudioSyncTracker.Outcome.Provisional -> {
                val first = model == null
                if (first) manualDelayAtLockMs = manualDelayMs()
                model = outcome.model
                estimated = true
                SyncLog.i("provisional ${outcome.model} ${describe(outcome.estimate)} in ${elapsed}ms")
                if (first) {
                    notify(
                        AudioSyncStatus.Estimated(
                            offsetMs = outcome.model.delayUsAt(positionMs * 1_000L) / 1_000L,
                        ),
                    )
                }
            }
            is AudioSyncTracker.Outcome.Retracted -> {
                model = null
                estimated = false
                SyncLog.i("early estimate withdrawn ${describe(outcome.estimate)}")
                notify(AudioSyncStatus.Withdrawn)
            }
            is AudioSyncTracker.Outcome.Locked -> {
                if (model == null) manualDelayAtLockMs = manualDelayMs()
                lockedAtElapsedMs = SystemClock.elapsedRealtime()
                model = outcome.model
                estimated = false
                SyncLog.i("LOCKED ${outcome.model} ${describe(outcome.estimate)} in ${elapsed}ms")
                notify(
                    AudioSyncStatus.Synced(
                        offsetMs = outcome.model.delayUsAt(positionMs * 1_000L) / 1_000L,
                        rateCorrected = outcome.estimate.scale != 1.0,
                    ),
                )
            }
            is AudioSyncTracker.Outcome.Refined -> {
                model = outcome.model
                SyncLog.i("refined ${outcome.model} ${describe(outcome.estimate)}")
            }
            is AudioSyncTracker.Outcome.Jumped -> {
                model = outcome.model
                SyncLog.i("jump detected ${outcome.model} ${describe(outcome.estimate)}")
                notify(AudioSyncStatus.Adjusted(offsetMs = outcome.model.delayUsAt(positionMs * 1_000L) / 1_000L))
            }
            is AudioSyncTracker.Outcome.Searching -> SyncLog.d("searching ${outcome.estimate?.let(::describe) ?: "-"} known=${timeline.knownFrameCount()} in ${elapsed}ms",
            )
            else -> Unit
        }
        updatePool(current)
    }

    private fun notify(status: AudioSyncStatus) {
        try {
            onStatus(status)
        } catch (_: Throwable) {
        }
    }

    private fun describe(estimate: SubtitleAudioAligner.Estimate): String =
        "scale=${"%.5f".format(estimate.scale)} shift=${estimate.shiftMs.roundToInt()}ms " +
            "peak=${"%.3f".format(estimate.peak)} prominence=${"%.3f".format(estimate.prominence)} " +
            "cues=${estimate.cueCount} speech=${estimate.speechSeconds.roundToInt()}s"

    private fun describe(format: Format): String =
        "${format.sampleMimeType} ${format.channelCount}ch ${format.sampleRate}Hz lang=${format.language} id=${format.id}"

    companion object {
        private const val MIN_TRACK_CUES = 20
        private const val ALIGN_INTERVAL_MS = 8_000L
        private const val SEARCH_INTERVAL_MS = 3_000L
        private const val RECOGNIZER_REFRESH_MS = 1_000L
        private const val DIAGNOSTICS_HEARTBEAT_MS = 15_000L
        private const val PREFERENCES = "nuvio_audio_sync"
        private const val REMEMBER_PREFIX = "v3:"
        private const val MAX_REMEMBERED = 200
        private const val MAX_REFERENCES = 4

        /**
         * How long speech is recognised ahead of references still downloading: covers a slow host
         * (references took up to 20 s on a TV), and bounds the work when none ever arrives.
         */
        private const val REFERENCE_WAIT_MS = 30_000L
        private const val MAX_FALLBACK_REFERENCES = 6
        private const val MAX_ALTERNATIVES = 12
        private const val DOWNLOAD_RETRIES = 3
        private const val DOWNLOAD_RETRY_DELAY_MS = 1_500L

        // Sampling audio across the film.
        private const val SPOT_COUNT = 4
        /** Up to one connection per spot, so every spot can arrive in the same round. */
        private const val SPOT_WORKERS = SPOT_COUNT
        /** 2 GB TVs: each connection carries its own decoder and speech detector. */
        private const val LOW_MEMORY_SPOT_WORKERS = 2
        private const val SPOT_MS = 30_000L
        private const val TARGET_SPOT_BYTES = 150L * 1_000_000L
        private const val MAX_SPOT_BYTES = 400L * 1_000_000L

        /** Low sampling: fewer spots over a single connection, capped well below high. */
        private const val LOW_SPOT_COUNT = 3

        /** A resumed sampling run needs at least this much of the stream's budget left. */
        private const val MIN_RESUMED_SPOT_BYTES = 10L * 1_000_000L

        /** How often an interrupted sampling run is resumed per stream. */
        private const val MAX_SPOT_RESTARTS = 2
        private const val LOW_SPOT_BYTES = 75L * 1_000_000L

        /** Enough for a few whole sentences, which speech recognition needs. */
        private const val MIN_SPOT_AUDIO_MS = 8_000L
        private const val MIN_SAMPLED_FILM_MS = 20 * 60_000L
        private const val RESUME_THRESHOLD_MS = 10 * 60_000L
        private const val SPOT_BACKOFF_MS = 5L
        private const val DRAIN_TIMEOUT_MS = 10_000L

        /** Playback has started and buffered this much before sampling competes for bandwidth. */
        private val SAMPLE_AFTER_FRAMES = (5_000 / SpeechTimeline.FRAME_DURATION_MS).toInt()
        private val NEAR_PLAYHEAD_FRAMES = (5_000 / SpeechTimeline.FRAME_DURATION_MS).toInt()
        /** Audio playback should have buffered before sampling opens another connection. */
        private val BUFFERED_AHEAD_FRAMES = (15_000 / SpeechTimeline.FRAME_DURATION_MS).toInt()
        private const val OPEN_SUBTITLES_FALLBACK = "https://opensubtitles-v3.strem.io"
        private const val RECOGNIZER_THREADS = 2

        /** Language tags that say nothing about the language spoken. */
        private val UNKNOWN_LANGUAGES = setOf("und", "mul", "zxx", "mis", "unknown")
        private const val NOTIFY_CHANGE_MS = 1_000L
        /** 10 minutes of audio analysed before any subtitle is chosen. */
        private val PRE_SESSION_FRAMES = (10 * 60 * 1_000 / SpeechTimeline.FRAME_DURATION_MS).toInt()

        private val englishStopWords = setOf("the", "and", "you", "to", "is", "it", "that", "of", "what", "this", "don't", "we", "your", "are", "have")

        private fun isEnglish(language: String?): Boolean {
            val value = language?.trim()?.lowercase() ?: return false
            return value == "en" || value == "eng" || value.startsWith("en-") || value.startsWith("en_") ||
                value.contains("english")
        }

        /** True when a good share of the words are common English function words. */
        private fun looksEnglish(dialogue: List<Triple<Long, Long, String>>): Boolean {
            val words = dialogue.asSequence().flatMap { WordAnchorMatcher.tokenize(it.third) }.take(2_000).toList()
            if (words.size < 50) return false
            return words.count { it in englishStopWords } >= words.size * 0.15
        }
        private const val FULL_ANALYSIS_AFTER_LOCK_MS = 10 * 60_000L
        private const val DUTY_WINDOW_US = 60_000_000L
        private const val DUTY_CYCLE = 3L


        private val periodPrefix = Regex("^(\\d+:)+")
        private val changingPanelFields = Regex(" ahead=\\d+s words=\\d+")

        /**
         * Same audio track, tolerating the Format differences between the extractor output and the
         * player's track groups (MergingMediaSource prefixes ids with the child index, e.g. "0:2").
         */
        private fun matches(a: Format, b: Format): Boolean {
            if (a == b) return true
            if (a.sampleMimeType != b.sampleMimeType) return false
            val aId = a.id?.replace(periodPrefix, "")
            val bId = b.id?.replace(periodPrefix, "")
            if (!aId.isNullOrEmpty() && !bId.isNullOrEmpty()) return aId == bId
            return a.language == b.language && a.channelCount == b.channelCount && a.sampleRate == b.sampleRate
        }
    }
}

/** Format of the audio track the player currently has selected, if any. */
internal fun Tracks.selectedAudioFormat(): Format? {
    for (group in groups) {
        if (group.type != C.TRACK_TYPE_AUDIO || !group.isSelected) continue
        for (index in 0 until group.length) {
            if (group.isTrackSelected(index)) return group.getTrackFormat(index)
        }
    }
    return null
}
