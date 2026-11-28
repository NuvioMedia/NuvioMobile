package com.nuvio.app.features.audiosync

import com.nuvio.app.features.autosync.AutoSyncMpvAudioFallback
import com.nuvio.app.features.autosync.AutoSyncPreferencesRepository
import com.nuvio.app.features.autosync.AutoSyncState
import com.nuvio.app.features.autosync.AutoSyncSubtitleCandidate
import com.nuvio.app.features.autosync.AutoSyncSubtitleTextRetimer
import com.nuvio.app.features.autosync.AutoSyncSyncedSubtitle
import com.nuvio.app.features.autosync.AutomaticSubtitleSync
import com.nuvio.app.features.autosync.LIBMPV_AUTOSYNC_FILE_PREFIX
import com.nuvio.app.features.autosync.SubtitleTimeMapping
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface MpvSpotAudioSyncHost {
    fun positionMs(): Long

    fun durationMs(): Long

    fun isPlaying(): Boolean

    fun selectedAudioLanguage(): String?

    fun replaceSubtitle(replacedUrl: String, path: String)

    fun setSubtitleDelayMs(delayMs: Int) {}

    suspend fun writeSubtitleFile(fileName: String, stalePrefix: String, content: String): String?
}

internal class SyncModelTimeMapping(private val model: SubtitleSyncModel) : SubtitleTimeMapping {
    override fun mapStartMs(originalMs: Long): Long =
        model.mediaTimeUs(originalMs * 1_000L)?.let { (it / 1_000L).coerceAtLeast(0L) } ?: CUT_LINE_MS

    override fun mapEndMs(originalMs: Long): Long =
        model.mediaTimeUs(originalMs * 1_000L)?.let { (it / 1_000L).coerceAtLeast(0L) } ?: (CUT_LINE_MS + 1L)

    private companion object {
        const val CUT_LINE_MS = 0L
    }
}

internal class MpvSpotAudioSyncFallback(
    private val scope: CoroutineScope,
    private val host: MpvSpotAudioSyncHost,
    reader: AudioSyncSpotReader,
    recognizers: AudioSyncRecognizers?,
    private val sourceUrl: String,
    sourceHeaders: Map<String, String>,
    private val getSubtitleHeaders: (String) -> Map<String, String>,
    private val log: (String) -> Unit,
) : AutoSyncMpvAudioFallback {
    private class Target(
        val url: String,
        val body: String,
        var replacedSource: String = url,
    ) {
        var shownPath: String? = null
    }

    private val engine = SpotAudioSyncEngine(
        reader = reader,
        recognizers = recognizers,
        log = log,
        onStatus = ::onStatus,
        requestSwitch = ::replaceSubtitle,
    )

    @Volatile
    private var target: Target? = null

    @Volatile
    private var activeUrl: String? = null

    @Volatile
    private var candidates: List<AutoSyncSubtitleCandidate> = emptyList()

    @Volatile
    private var contentType: String? = null

    @Volatile
    private var videoId: String? = null

    @Volatile
    private var appliedListener: ((subtitleUrl: String, delayMs: Int) -> Unit)? = null

    private var armed = false
    private var appliedModel: SubtitleSyncModel? = null
    private var ticker: Job? = null
    private var takeOverJob: Job? = null
    @Volatile
    private var switchJob: Job? = null
    private var settingsJob: Job? = null
    private var pendingStatus: AudioSyncStatus? = null
    private var statusRun: AutoSyncSyncedSubtitle.Status? = null
    private var unmatchedPlayedMs = 0L
    private var unmatchedReported = false
    private var lockConfirmed = false

    init {
        engine.enabled = AudioSyncSettings.fallbackEnabled.value
        engine.samplingMode = spotSamplingMode()
        engine.audioLanguage = host.selectedAudioLanguage()
        engine.setSource(sourceUrl, sourceHeaders)
        settingsJob = scope.launch {
            combine(
                AudioSyncSettings.fallbackEnabled,
                AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart,
            ) { audio, auto -> audio && auto }
                .collect { enabled ->
                    if (!enabled) {
                        stop(restoreOriginalOnMpv = true)
                        engine.enabled = false
                    }
                }
        }
    }

    override fun setContent(contentType: String?, videoId: String?) {
        this.contentType = contentType
        this.videoId = videoId
        if (armed) engine.setContent(contentType, videoId)
    }

    override fun setCandidates(candidates: List<AutoSyncSubtitleCandidate>) {
        this.candidates = candidates
        if (armed) engine.setReferenceSubtitles(references(candidates))
    }

    override fun setAppliedListener(listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?) {
        appliedListener = listener
    }

    override fun arm() {
        stop(restoreOriginalOnMpv = false)
        if (!AudioSyncSettings.fallbackEnabled.value) return
        engine.enabled = true
        engine.samplingMode = spotSamplingMode()
        engine.prepareRecognizer()
        if (!armed) {
            armed = true
            engine.setContent(contentType, videoId)
        }
        engine.setReferenceSubtitles(references(candidates))
        startTicker()
    }

    override fun disarm() {
        stop(restoreOriginalOnMpv = false)
    }

    override fun takeOver(url: String): Boolean {
        if (!AudioSyncSettings.fallbackEnabled.value || !engine.enabled) {
            disarm()
            return false
        }
        takeOverJob?.cancel()
        switchJob?.cancel()
        switchJob = null
        statusRun = AutoSyncSyncedSubtitle.start(url)
        activeUrl = url
        takeOverJob = scope.launch { takeOverSubtitle(url) }
        return true
    }

    override fun stop(restoreOriginalOnMpv: Boolean) {
        takeOverJob?.cancel()
        takeOverJob = null
        switchJob?.cancel()
        switchJob = null
        AutoSyncSyncedSubtitle.end(statusRun)
        statusRun = null
        ticker?.cancel()
        ticker = null
        val previousUrl = activeUrl
        if (!restoreOriginalOnMpv) activeUrl = null
        val previous = target
        target = null
        if (previous != null) {
            AutoSyncSyncedSubtitle.clear()
            if (restoreOriginalOnMpv && previousUrl == previous.url && previous.shownPath != null) {
                scope.launch { showOriginal(previous) }
            }
        }
        appliedModel = null
        pendingStatus = null
        lockConfirmed = false
        engine.stopSession()
    }

    override fun release() {
        stop(restoreOriginalOnMpv = false)
        settingsJob?.cancel()
        engine.release()
    }

    private fun spotSamplingMode(): AudioSamplingMode =
        AudioSyncSettings.samplingMode.value.takeIf { it != AudioSamplingMode.OFF } ?: AudioSamplingMode.LOW

    private fun references(list: List<AutoSyncSubtitleCandidate>): List<AudioSyncReference> =
        list.distinctBy { it.url }.map { subtitle ->
            AudioSyncReference(
                url = subtitle.url,
                language = subtitle.language,
                headers = subtitle.headers.ifEmpty { getSubtitleHeaders(subtitle.url) },
            )
        }

    private suspend fun takeOverSubtitle(url: String) {
        val candidate = candidates.firstOrNull { it.url == url }
        val headers = candidate?.headers?.ifEmpty { getSubtitleHeaders(url) } ?: getSubtitleHeaders(url)
        val body = try {
            withContext(Dispatchers.IO) {
                AutomaticSubtitleSync.downloadSubtitleBody(url = url, headers = headers, languageHint = candidate?.language)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            log("could not download $url for audio sync: ${error.message}")
            null
        }
        if (activeUrl != url) return disarm()
        val dialogue = body?.let { text ->
            withContext(Dispatchers.Default) { SpotAudioSyncEngine.dialogueOf(text, url) }
        }.orEmpty()
        if (body == null || dialogue.isEmpty()) {
            AutoSyncSyncedSubtitle.fail(statusRun)
            return disarm()
        }
        target = Target(url, body)
        appliedModel = null
        unmatchedPlayedMs = 0L
        unmatchedReported = false
        lockConfirmed = false
        startTicker()
        log("AutoSync kept the original timing of $url; syncing it to sampled audio")
        engine.startSession(url, dialogue)
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val current = target
                if (current != null && activeUrl != current.url) {
                    stop(restoreOriginalOnMpv = false)
                    return@launch
                }
                engine.audioLanguage = host.selectedAudioLanguage()
                val positionMs = host.positionMs().coerceAtLeast(0L)
                val durationMs = host.durationMs().coerceAtLeast(0L)
                engine.onPlaybackPosition(positionMs, durationMs)
                val model = engine.currentModel()
                if (current != null && model !== appliedModel) {
                    appliedModel = model
                    apply(current, model)
                }
                val status = pendingStatus
                pendingStatus = null
                if (current != null && target === current && status != null) {
                    when (status) {
                        is AudioSyncStatus.Synced, is AudioSyncStatus.Adjusted -> {
                            if (appliedModel != null && appliedModel === engine.currentModel()) {
                                lockConfirmed = true
                                AutoSyncSyncedSubtitle.mark(current.url)
                            } else if (engine.currentModel() != null) {
                                pendingStatus = status
                            }
                        }
                        AudioSyncStatus.Withdrawn -> {
                            if (engine.currentModel() == null && lockConfirmed) {
                                lockConfirmed = false
                                statusRun = AutoSyncSyncedSubtitle.start(current.url)
                                unmatchedPlayedMs = 0L
                                unmatchedReported = false
                            }
                        }
                        AudioSyncStatus.Failed -> {
                            if (engine.currentModel() == null) {
                                AutoSyncSyncedSubtitle.mark(current.url, AutoSyncState.FAILED)
                            }
                        }
                        else -> Unit
                    }
                }
                if (current != null && target === current && !unmatchedReported && !engine.isModelConfirmed) {
                    if (host.isPlaying()) unmatchedPlayedMs += TICK_MS
                    if (unmatchedPlayedMs >= UNMATCHED_REPORT_MS) {
                        unmatchedReported = true
                        log("no audio match after ${unmatchedPlayedMs / 1_000}s of playback; still searching")
                        AutoSyncSyncedSubtitle.fail(statusRun)
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    private suspend fun apply(current: Target, model: SubtitleSyncModel?) {
        if (model == null) {
            if (current.shownPath != null) showOriginal(current)
            return
        }
        val retimed = withContext(Dispatchers.Default) {
            AutoSyncSubtitleTextRetimer.retime(current.body, SyncModelTimeMapping(model))
        }
        if (retimed == null) {
            log("this subtitle format cannot be retimed for mpv")
            return stop(restoreOriginalOnMpv = false)
        }
        val path = writeCopy(current.url, retimed, original = false)
        if (target !== current || activeUrl != current.url) return
        if (path == null) {
            log("could not write the synced subtitle copy")
            return stop(restoreOriginalOnMpv = false)
        }
        host.replaceSubtitle(current.replacedSource, path)
        current.replacedSource = path
        current.shownPath = path
    }

    private suspend fun showOriginal(current: Target) {
        val shown = current.shownPath ?: return
        val path = writeCopy(current.url, current.body, original = true) ?: return
        if (activeUrl != current.url) return
        host.replaceSubtitle(shown, path)
        current.replacedSource = path
        current.shownPath = path
    }

    private fun replaceSubtitle(url: String) {
        switchJob?.cancel()
        switchJob = scope.launch {
            val current = target ?: return@launch
            val candidate = candidates.firstOrNull { it.url == url }
            val headers = candidate?.headers?.ifEmpty { getSubtitleHeaders(url) } ?: getSubtitleHeaders(url)
            val body = try {
                withContext(Dispatchers.IO) {
                    AutomaticSubtitleSync.downloadSubtitleBody(
                        url = url,
                        headers = headers,
                        languageHint = candidate?.language,
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                log("could not download $url for audio sync switch: ${error.message}")
                null
            }
            if (body == null || target !== current || activeUrl != current.url) return@launch
            val dialogue = withContext(Dispatchers.Default) { SpotAudioSyncEngine.dialogueOf(body, url) }
            if (dialogue.isEmpty() || target !== current || activeUrl != current.url) return@launch
            statusRun = AutoSyncSyncedSubtitle.start(url)
            activeUrl = url
            target = Target(url, body, replacedSource = current.shownPath ?: current.url)
            appliedModel = null
            unmatchedPlayedMs = 0L
            unmatchedReported = false
            lockConfirmed = false
            host.setSubtitleDelayMs(0)
            appliedListener?.invoke(url, 0)
            engine.startSession(url, dialogue)
        }
    }

    private suspend fun writeCopy(url: String, text: String, original: Boolean): String? {
        val prefix = "${LIBMPV_AUTOSYNC_FILE_PREFIX}audio_${url.hashCode().toUInt().toString(16)}_"
        val extension = when {
            text.trimStart().startsWith("WEBVTT") -> "vtt"
            text.contains("[Events]", ignoreCase = true) -> "ass"
            else -> "srt"
        }
        val name = if (original) "orig" else text.hashCode().toUInt().toString(16)
        return runCatching {
            host.writeSubtitleFile(fileName = "$prefix$name.$extension", stalePrefix = prefix, content = text)
        }.getOrNull()
    }

    private fun onStatus(status: AudioSyncStatus) {
        if (status !is AudioSyncStatus.Synced && status !is AudioSyncStatus.Adjusted &&
            status != AudioSyncStatus.Withdrawn && status != AudioSyncStatus.Failed
        ) return
        val current = target ?: return
        scope.launch {
            if (target === current) pendingStatus = status
        }
    }

    private companion object {
        const val TICK_MS = 250L
        const val UNMATCHED_REPORT_MS = 5 * 60_000L
    }
}
