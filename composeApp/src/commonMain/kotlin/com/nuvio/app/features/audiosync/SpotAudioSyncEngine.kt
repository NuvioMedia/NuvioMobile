package com.nuvio.app.features.audiosync

import com.nuvio.app.features.audiosync.asr.AsrLock
import com.nuvio.app.features.audiosync.asr.AsrSyncEngine
import com.nuvio.app.features.audiosync.asr.ReferenceSubtitle
import com.nuvio.app.features.audiosync.asr.SpeechSegmenter
import com.nuvio.app.features.audiosync.asr.SpeechToText
import com.nuvio.app.features.audiosync.asr.SubtitleBridge
import com.nuvio.app.features.audiosync.asr.WordAnchorMatcher
import com.nuvio.app.features.autosync.AutoSyncSubtitleCueParser
import com.nuvio.app.features.autosync.AutomaticSubtitleSync
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class AudioSyncReference(
    val url: String,
    val language: String,
    val headers: Map<String, String>,
)

internal class AudioSyncSpotRequest(
    val url: String,
    val headers: Map<String, String>,
    val spotsMs: List<Long>,
    val spotMs: Long,
    val targetBytes: Long,
    val maxBytes: Long,
    val minSpotMs: Long,
    val audioLanguage: String?,
)

internal class AudioSyncSpotResult(val sampled: Int, val bytes: Long, val failure: String?)

internal interface AudioSyncSpotSink {
    fun isCancelled(): Boolean

    fun onPcm(samples: FloatArray, count: Int, sampleRate: Int, timeUs: Long)

    fun onSpotEnd()
}

internal interface AudioSyncSpotReader {
    fun isUnmetered(): Boolean

    fun read(request: AudioSyncSpotRequest, sink: AudioSyncSpotSink): AudioSyncSpotResult
}

internal interface AudioSyncRecognizers {
    fun isModelReady(): Boolean

    fun acquire(): SpeechToText?

    fun release()
}

internal class SpotAudioSyncEngine(
    private val reader: AudioSyncSpotReader,
    private val recognizers: AudioSyncRecognizers?,
    private val log: (String) -> Unit,
    private val onStatus: (AudioSyncStatus) -> Unit,
) {
    private class Session(
        val key: String,
        val track: SubtitleSpeechTrack,
        val dialogue: List<Triple<Long, Long, String>>,
    ) {
        val tracker = AudioSyncTracker(track)

        @Volatile
        var maintainedByRecognition = false
    }

    private enum class FallbackState { Idle, Loading, Done }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val timeline = SpeechTimeline()
    private val asr = AsrSyncEngine(timeline = timeline, onLock = ::onAsrLock, log = log)
    private val sessionRequest = atomic(0)
    private val alignRunning = atomic(false)
    private val recognizerLoading = atomic(false)
    private val spotSamplingStarted = atomic(false)
    private val spotRestarts = atomic(0)
    private val referenceLock = SynchronizedObject()
    private val parsedReferences = HashMap<String, List<Triple<Long, Long, String>>>()
    private val pendingReferences = HashMap<String, MutableList<(List<Triple<Long, Long, String>>) -> Unit>>()
    private val fallbackListeners = ArrayList<() -> Unit>()

    @Volatile
    private var session: Session? = null

    @Volatile
    private var model: SubtitleSyncModel? = null

    @Volatile
    private var estimated = false

    @Volatile
    private var released = false

    @Volatile
    private var sourceUrl: String? = null

    @Volatile
    private var sourceHeaders: Map<String, String> = emptyMap()

    @Volatile
    private var mediaDurationMs = 0L

    @Volatile
    private var playbackPositionMs = 0L

    @Volatile
    private var spotBytesUsed = 0L

    @Volatile
    private var spotDeclined = false

    @Volatile
    private var candidates: List<AudioSyncReference> = emptyList()

    @Volatile
    private var fallbackCandidates: List<AudioSyncReference> = emptyList()

    @Volatile
    private var fallbackState = FallbackState.Idle

    @Volatile
    private var contentKey: String? = null

    @Volatile
    private var recognizer: SpeechToText? = null

    @Volatile
    private var recognizerFailed = false

    @Volatile
    private var recognizerUnavailable = false

    @Volatile
    private var noUsableReference = false

    private var lastAlignedSession: Session? = null
    private var lastAlignVersion = -1L
    private var lastAlignAtMs = 0L
    private var lastRecognizerRefreshAtMs = 0L

    @Volatile
    var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) model = null
        }

    @Volatile
    var samplingMode: AudioSamplingMode = AudioSamplingMode.LOW
        set(value) {
            field = value
            if (value != AudioSamplingMode.OFF && spotDeclined) {
                spotDeclined = false
                spotSamplingStarted.value = false
            }
        }

    @Volatile
    var audioLanguage: String? = null
        set(value) {
            val changed = languageKey(value) != languageKey(field)
            field = value
            if (changed) updateListening()
        }

    fun setSource(url: String, headers: Map<String, String>) {
        if (url == sourceUrl) return
        sourceUrl = url
        sourceHeaders = headers
        timeline.clear()
        asr.clear()
        spotSamplingStarted.value = false
        spotBytesUsed = 0L
        spotRestarts.value = 0
        spotDeclined = false
        model = null
        estimated = false
    }

    fun currentModel(): SubtitleSyncModel? = model.takeIf { enabled && session != null }

    fun setReferenceSubtitles(list: List<AudioSyncReference>) {
        candidates = list
        if (list.isNotEmpty()) prepareRecognizer()
        prefetchReferences()
    }

    fun setContent(type: String?, videoId: String?) {
        val key = if (type.isNullOrBlank() || videoId.isNullOrBlank()) null else "$type|$videoId"
        if (key == contentKey) return
        contentKey = key
        fallbackCandidates = emptyList()
        fallbackState = FallbackState.Idle
        synchronized(referenceLock) { fallbackListeners.clear() }
        if (key != null && enabled) loadFallbackCandidates(key, type!!, videoId!!)
    }

    fun prepareRecognizer() {
        if (enabled && !released && !audioIsForeign() && recognizers?.isModelReady() == true) ensureRecognizer()
    }

    fun startSession(key: String, dialogue: List<Triple<Long, Long, String>>) {
        prepareRecognizer()
        val request = sessionRequest.incrementAndGet()
        session = null
        model = null
        estimated = false
        scope.launch(serial) {
            val track = SubtitleSpeechTrack.fromCues(dialogue)
            if (sessionRequest.value != request || released) return@launch
            if (track.size < MIN_TRACK_CUES) {
                log("subtitle $key has only ${track.size} dialogue cues; audio sync skipped")
                notify(AudioSyncStatus.Failed)
                return@launch
            }
            val started = Session(key, track, dialogue)
            session = started
            log("sync session started for $key with ${track.size} dialogue cues")
            if (!enabled) return@launch
            notify(AudioSyncStatus.Listening)
            startRecognition(started)
        }
    }

    fun stopSession() {
        sessionRequest.incrementAndGet()
        asr.stopSession()
        session = null
        model = null
    }

    fun onPlaybackPosition(positionMs: Long, durationMs: Long) {
        playbackPositionMs = positionMs.coerceAtLeast(0L)
        if (durationMs > 0) mediaDurationMs = durationMs
        asr.onPlayhead(playbackPositionMs)
        scheduleAlignment()
        maybeSampleSpots()
        val now = audioSyncNanoTime() / 1_000_000L
        if (now - lastRecognizerRefreshAtMs >= RECOGNIZER_REFRESH_MS) {
            lastRecognizerRefreshAtMs = now
            if (session != null && enabled && !released && recognizer == null &&
                SubtitleSyncStatus.speechModel.value.downloaded && !audioIsForeign()
            ) {
                ensureRecognizer()
            }
        }
    }

    fun release() {
        released = true
        session = null
        model = null
        asr.release()
        scope.cancel()
        if (recognizer != null) {
            recognizer = null
            recognizers?.release()
        }
    }

    private fun audioIsForeign(): Boolean {
        val language = audioLanguage?.trim()?.lowercase()
        if (language.isNullOrEmpty() || language in UNKNOWN_LANGUAGES) return false
        return !isEnglish(language)
    }

    private fun updateListening() {
        asr.setListening(!recognizerUnavailable && !noUsableReference && !audioIsForeign())
    }

    private fun ensureRecognizer() {
        val source = recognizers ?: return
        if (recognizer != null || released || recognizerFailed) return
        if (!recognizerLoading.compareAndSet(expect = false, update = true)) return
        scope.launch(Dispatchers.Default) {
            try {
                if (released || !enabled) return@launch
                recognizerUnavailable = false
                updateListening()
                if (!source.isModelReady()) {
                    log("speech model not downloaded; using speech detection only")
                    recognizerUnavailable = true
                    updateListening()
                    return@launch
                }
                val loaded = source.acquire()
                if (loaded == null) {
                    log("speech recognizer unavailable")
                    recognizerFailed = true
                    recognizerUnavailable = true
                    updateListening()
                    return@launch
                }
                if (released || !enabled) {
                    source.release()
                    return@launch
                }
                recognizer = loaded
                asr.setRecognizer(loaded)
                log("speech recognizer ready")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                log("speech recognizer unavailable: ${error.message}")
                recognizerFailed = true
                recognizerUnavailable = true
                updateListening()
            } finally {
                recognizerLoading.value = false
            }
        }
    }

    private fun startRecognition(current: Session) {
        noUsableReference = false
        updateListening()
        if (!audioIsForeign()) ensureRecognizer()
        val english = isEnglish(candidates.firstOrNull { it.url == current.key }?.language) ||
            looksEnglish(current.dialogue)
        if (english) {
            asr.startSession(current.track, listOf(ReferenceSubtitle(current.key, current.dialogue, null)))
            return
        }
        asr.startSession(current.track, emptyList())
        val references = englishCandidates().take(MAX_REFERENCES)
        if (references.isEmpty()) {
            if (fallbackState == FallbackState.Loading) {
                asr.expectReferences(REFERENCE_WAIT_MS)
                synchronized(referenceLock) {
                    fallbackListeners += { if (session === current) bridgeReferences(current) }
                }
                return
            }
            log("no English reference subtitle available; recognition idle")
            noUsableReference = true
            updateListening()
            return
        }
        bridgeReferences(current)
    }

    private fun bridgeReferences(current: Session) {
        val references = englishCandidates().take(MAX_REFERENCES)
        if (references.isEmpty()) {
            asr.expectReferences(0L)
            noUsableReference = true
            updateListening()
            return
        }
        val matched = atomic(0)
        val finished = atomic(0)
        asr.expectReferences(REFERENCE_WAIT_MS)
        for (candidate in references) {
            fetchReference(candidate) { dialogue ->
                if (session !== current) return@fetchReference
                val bridge = SubtitleBridge.align(current.track, SubtitleSpeechTrack.fromCues(dialogue))
                log("reference ${candidate.url}: bridge=$bridge")
                if (bridge != null) {
                    matched.incrementAndGet()
                    asr.addReference(ReferenceSubtitle(candidate.url, dialogue, bridge))
                    if (noUsableReference) {
                        noUsableReference = false
                        updateListening()
                    }
                }
                if (finished.incrementAndGet() == references.size && matched.value == 0) {
                    asr.expectReferences(0L)
                    noUsableReference = true
                    updateListening()
                }
            }
        }
    }

    private fun englishCandidates(): List<AudioSyncReference> =
        (candidates.filter { isEnglish(it.language) } + fallbackCandidates).distinctBy { it.url }

    private fun prefetchReferences() {
        if (recognizers?.isModelReady() != true) return
        englishCandidates().take(MAX_REFERENCES).forEach { fetchReference(it, onReady = null) }
    }

    private fun fetchReference(
        candidate: AudioSyncReference,
        onReady: ((List<Triple<Long, Long, String>>) -> Unit)?,
    ) {
        val ready = synchronized(referenceLock) {
            parsedReferences[candidate.url]?.let { return@synchronized it }
            val waiting = pendingReferences[candidate.url]
            if (waiting != null) {
                if (onReady != null) waiting += onReady
                return@synchronized null
            }
            pendingReferences[candidate.url] = ArrayList<(List<Triple<Long, Long, String>>) -> Unit>().also { list ->
                if (onReady != null) list += onReady
            }
            scope.launch(Dispatchers.IO) {
                val dialogue = downloadDialogue(candidate)
                val listeners = synchronized(referenceLock) {
                    parsedReferences[candidate.url] = dialogue
                    pendingReferences.remove(candidate.url).orEmpty()
                }
                listeners.forEach { runCatching { it(dialogue) } }
            }
            null
        }
        if (ready != null) onReady?.invoke(ready)
    }

    private suspend fun downloadDialogue(candidate: AudioSyncReference): List<Triple<Long, Long, String>> {
        var attempt = 0
        while (true) {
            try {
                val body = AutomaticSubtitleSync.downloadSubtitleBody(url = candidate.url, headers = candidate.headers)
                return dialogueOf(body, candidate.url)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                val throttled = error.message.orEmpty().contains("429")
                if (!throttled || ++attempt > DOWNLOAD_RETRIES || released) {
                    log("reference download failed for ${candidate.url}: ${error.message}")
                    return emptyList()
                }
                delay(DOWNLOAD_RETRY_DELAY_MS * attempt)
            }
        }
    }

    private fun loadFallbackCandidates(key: String, type: String, videoId: String) {
        fallbackState = FallbackState.Loading
        scope.launch(Dispatchers.IO) {
            val canonicalType = if (type.equals("tv", ignoreCase = true)) "series" else type.lowercase()
            val url = "$OPEN_SUBTITLES_FALLBACK/subtitles/$canonicalType/$videoId.json"
            val found = try {
                val body = AutomaticSubtitleSync.downloadSubtitleBody(url = url, headers = emptyMap())
                val array = Json.parseToJsonElement(body).jsonObject["subtitles"]?.jsonArray.orEmpty()
                array.mapNotNull { element ->
                    val item = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val subtitleUrl = item["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val language = item["lang"]?.jsonPrimitive?.content.orEmpty()
                    if (!isEnglish(language)) return@mapNotNull null
                    AudioSyncReference(subtitleUrl, language, emptyMap())
                }.take(MAX_FALLBACK_REFERENCES)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                log("English reference lookup failed for $videoId: ${error.message}")
                emptyList()
            }
            if (contentKey != key) return@launch
            log("English reference lookup for $videoId found ${found.size}")
            fallbackCandidates = found
            fallbackState = FallbackState.Done
            prefetchReferences()
            val listeners = synchronized(referenceLock) {
                fallbackListeners.toList().also { fallbackListeners.clear() }
            }
            listeners.forEach { runCatching(it) }
        }
    }

    private fun scheduleAlignment() {
        val current = session ?: return
        if (!enabled || released) return
        val now = audioSyncNanoTime() / 1_000_000L
        val version = timeline.version
        val interval = if (current.tracker.model == null) SEARCH_INTERVAL_MS else ALIGN_INTERVAL_MS
        if (current === lastAlignedSession && (version == lastAlignVersion || now - lastAlignAtMs < interval)) return
        if (!alignRunning.compareAndSet(expect = false, update = true)) return
        lastAlignedSession = current
        lastAlignVersion = version
        lastAlignAtMs = now
        val position = playbackPositionMs
        scope.launch(serial) {
            try {
                align(current, position)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                log("alignment failed: ${error.message}")
            } finally {
                alignRunning.value = false
            }
        }
    }

    private fun align(current: Session, positionMs: Long) {
        if (current.maintainedByRecognition) return
        val outcome = current.tracker.update(timeline, positionMs)
        if (session !== current) return
        when (outcome) {
            is AudioSyncTracker.Outcome.Provisional -> {
                val first = model == null
                model = outcome.model
                estimated = true
                log("provisional ${outcome.model}")
                if (first) notify(AudioSyncStatus.Estimated(offsetMs = outcome.model.delayUsAt(positionMs * 1_000L) / 1_000L))
            }
            is AudioSyncTracker.Outcome.Retracted -> {
                model = null
                estimated = false
                log("early estimate withdrawn")
                notify(AudioSyncStatus.Withdrawn)
            }
            is AudioSyncTracker.Outcome.Locked -> {
                model = outcome.model
                estimated = false
                log("LOCKED ${outcome.model}")
                notify(
                    AudioSyncStatus.Synced(
                        offsetMs = outcome.model.delayUsAt(positionMs * 1_000L) / 1_000L,
                        rateCorrected = outcome.estimate.scale != 1.0,
                    ),
                )
            }
            is AudioSyncTracker.Outcome.Refined -> {
                model = outcome.model
                log("refined ${outcome.model}")
            }
            is AudioSyncTracker.Outcome.Jumped -> {
                model = outcome.model
                log("jump detected ${outcome.model}")
                notify(AudioSyncStatus.Adjusted(offsetMs = outcome.model.delayUsAt(positionMs * 1_000L) / 1_000L))
            }
            else -> Unit
        }
    }

    private fun onAsrLock(result: AsrLock) {
        val current = session ?: return
        if (!enabled || released) return
        if (!result.final && model != null && !estimated) return
        val adopted = SubtitleSyncModel(result.segments)
        val previous = model
        val wasFinal = current.maintainedByRecognition
        current.tracker.adopt(adopted)
        if (result.final) current.maintainedByRecognition = true
        model = adopted
        estimated = !result.final
        val position = playbackPositionMs
        val offsetMs = adopted.delayUsAt(position * 1_000L) / 1_000L
        log("RECOGNITION LOCK $adopted via ${result.referenceKey} final=${result.final}")
        val previousOffsetMs = previous?.delayUsAt(position * 1_000L)?.div(1_000L)
        val moved = previousOffsetMs == null || abs(previousOffsetMs - offsetMs) >= NOTIFY_CHANGE_MS
        when {
            result.final && wasFinal -> if (moved) notify(AudioSyncStatus.Adjusted(offsetMs = offsetMs))
            result.final -> notify(AudioSyncStatus.Synced(offsetMs = offsetMs, rateCorrected = result.scale != 1.0))
            moved -> notify(AudioSyncStatus.Estimated(offsetMs = offsetMs))
        }
    }

    private fun maybeSampleSpots() {
        if (spotSamplingStarted.value) return
        val url = sourceUrl ?: return
        val current = session ?: return
        if (!enabled || released || (model != null && !estimated)) return
        if (mediaDurationMs < MIN_SAMPLED_FILM_MS) return
        if (!spotSamplingStarted.compareAndSet(expect = false, update = true)) return
        if (samplingMode == AudioSamplingMode.OFF) {
            log("audio sampling across the film is off")
            spotDeclined = true
            return
        }
        val headers = sourceHeaders
        scope.launch(Dispatchers.IO) { sampleSpots(url, headers, current) }
    }

    private fun sampleSpots(url: String, headers: Map<String, String>, current: Session) {
        val weights = runCatching { SileroVadBundle.load() }.getOrNull() ?: run {
            log("audio sampling skipped: speech detector weights unavailable")
            return
        }
        val high = samplingMode == AudioSamplingMode.HIGH && reader.isUnmetered()
        val spotCount = if (high) SPOT_COUNT else LOW_SPOT_COUNT
        val targetBytes = (if (high) TARGET_SPOT_BYTES else LOW_SPOT_BYTES) - spotBytesUsed
        val maxBytes = (if (high) MAX_SPOT_BYTES else LOW_SPOT_BYTES) - spotBytesUsed
        if (targetBytes < MIN_RESUMED_SPOT_BYTES) {
            log("audio sampling budget for this stream is spent")
            return
        }
        val analyzer = SpeechAnalyzer(SileroVad(weights), timeline)
        val segmenter = SpeechSegmenter { startFrame, samples -> asr.offerSegment(startFrame, samples, spread = true) }
        analyzer.chunkListener = segmenter
        var planned = 0
        var sampled = 0
        try {
            val referenceTracks = synchronized(referenceLock) {
                englishCandidates().mapNotNull { parsedReferences[it.url]?.takeIf { cues -> cues.isNotEmpty() } }
            }.map(SubtitleSpeechTrack::fromCues)
            val tracks = listOf(current.track) + referenceTracks
            val coveredMs = timeline.segments(fromFrame = SpeechTimeline.frameForTimeUs(playbackPositionMs * 1_000L))
                .firstOrNull()?.let { (it.toFrame * SpeechTimeline.FRAME_DURATION_MS).toLong() } ?: 0L
            val notBeforeMs = if (playbackPositionMs < RESUME_THRESHOLD_MS) coveredMs + SPOT_MS else 0L
            val spots = DialogueSpotPlanner.plan(tracks, mediaDurationMs, spotCount, SPOT_MS, notBeforeMs)
                .filterNot { spotMs ->
                    val from = SpeechTimeline.frameForTimeUs(spotMs * 1_000L)
                    val to = SpeechTimeline.frameForTimeUs((spotMs + SPOT_MS) * 1_000L)
                    timeline.knownFramesIn(from, to) * 2 >= to - from
                }
            planned = spots.size
            if (spots.isEmpty()) {
                log("audio sampling skipped: no suitable dialogue spots")
                return
            }
            log("sampling audio at ${spots.map { it / 1_000 }}s")
            val request = AudioSyncSpotRequest(
                url = url,
                headers = headers,
                spotsMs = spots,
                spotMs = SPOT_MS,
                targetBytes = targetBytes,
                maxBytes = maxBytes,
                minSpotMs = MIN_SPOT_AUDIO_MS,
                audioLanguage = audioLanguage,
            )
            val result = reader.read(
                request,
                object : AudioSyncSpotSink {
                    override fun isCancelled(): Boolean =
                        released || !enabled || sourceUrl != url || session == null || (model != null && !estimated)

                    override fun onPcm(samples: FloatArray, count: Int, sampleRate: Int, timeUs: Long) {
                        analyzer.accept(samples, count, sampleRate, timeUs)
                    }

                    override fun onSpotEnd() {
                        segmenter.flush()
                    }
                },
            )
            sampled = result.sampled
            spotBytesUsed += result.bytes
            log("sampled ${result.sampled} spots, ${result.bytes / 1_000_000} MB, failure=${result.failure}")
            segmenter.flush()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            log("audio sampling failed: ${error.message}")
        }
        val cutShort = sampled < planned && session !== current && sourceUrl == url &&
            !released && enabled && (model == null || estimated)
        if (cutShort && spotRestarts.incrementAndGet() <= MAX_SPOT_RESTARTS) {
            log("audio sampling cut short by a subtitle change ($sampled of $planned spots); resuming for the next subtitle")
            spotSamplingStarted.value = false
        }
    }

    private fun notify(status: AudioSyncStatus) {
        try {
            onStatus(status)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val MIN_TRACK_CUES = 20
        private const val ALIGN_INTERVAL_MS = 8_000L
        private const val SEARCH_INTERVAL_MS = 3_000L
        private const val RECOGNIZER_REFRESH_MS = 1_000L
        private const val MAX_REFERENCES = 4
        private const val REFERENCE_WAIT_MS = 30_000L
        private const val MAX_FALLBACK_REFERENCES = 6
        private const val DOWNLOAD_RETRIES = 3
        private const val DOWNLOAD_RETRY_DELAY_MS = 1_500L
        private const val SPOT_COUNT = 4
        private const val LOW_SPOT_COUNT = 3
        private const val SPOT_MS = 30_000L
        private const val TARGET_SPOT_BYTES = 150L * 1_000_000L
        private const val MAX_SPOT_BYTES = 400L * 1_000_000L
        private const val LOW_SPOT_BYTES = 75L * 1_000_000L
        private const val MIN_RESUMED_SPOT_BYTES = 10L * 1_000_000L
        private const val MAX_SPOT_RESTARTS = 2
        private const val MIN_SPOT_AUDIO_MS = 8_000L
        private const val MIN_SAMPLED_FILM_MS = 20 * 60_000L
        private const val RESUME_THRESHOLD_MS = 10 * 60_000L
        private const val NOTIFY_CHANGE_MS = 1_000L
        private const val OPEN_SUBTITLES_FALLBACK = "https://opensubtitles-v3.strem.io"
        private val UNKNOWN_LANGUAGES = setOf("und", "mul", "zxx", "mis", "unknown")
        private val englishStopWords = setOf(
            "the", "and", "you", "to", "is", "it", "that", "of", "what", "this", "don't", "we", "your", "are", "have",
        )

        fun isEnglish(language: String?): Boolean {
            val value = language?.trim()?.lowercase() ?: return false
            return value == "en" || value == "eng" || value.startsWith("en-") || value.startsWith("en_") ||
                value.contains("english")
        }

        fun languageKey(language: String?): String? {
            val value = language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it !in UNKNOWN_LANGUAGES }
                ?: return null
            return value.substringBefore('-').substringBefore('_')
        }

        fun dialogueOf(body: String, url: String): List<Triple<Long, Long, String>> =
            AutoSyncSubtitleCueParser.parse(body, url).map { Triple(it.startTimeMs, it.endTimeMs, it.text) }

        private fun looksEnglish(dialogue: List<Triple<Long, Long, String>>): Boolean {
            val words = dialogue.asSequence().flatMap { WordAnchorMatcher.tokenize(it.third) }.take(2_000).toList()
            if (words.size < 50) return false
            return words.count { it in englishStopWords } >= words.size * 0.15
        }
    }
}
