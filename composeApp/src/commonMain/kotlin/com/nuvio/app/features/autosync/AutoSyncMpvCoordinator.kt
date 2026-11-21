package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.PlayerEngineController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** File name prefix of the retimed subtitle copies that mpv players load. */
internal const val LIBMPV_AUTOSYNC_FILE_PREFIX = "autosync_"

/** What an mpv player needs from its platform to apply AutoSync results. */
internal interface AutoSyncMpvPlatform {
    /**
     * Writes [content] to the subtitle cache as [fileName], deletes the other files whose names
     * start with [stalePrefix], and returns the written file's path, or null when it cannot be written.
     */
    suspend fun writeSubtitleFile(fileName: String, stalePrefix: String, content: String): String?
}

/** Optional audio-based fallback when embedded AutoSync keeps the subtitle's original timing. */
internal interface AutoSyncMpvAudioFallback {
    fun setContent(contentType: String?, videoId: String?) {}
    fun setCandidates(candidates: List<AutoSyncSubtitleCandidate>) {}
    fun setAppliedListener(listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?) {}
    fun arm()
    fun disarm()
    fun takeOver(url: String): Boolean
    /** [restoreOriginalOnMpv]: the subtitle stays on, so mpv shows the original again in place of the retimed copy. */
    fun stop(restoreOriginalOnMpv: Boolean = false)
    fun release()
}

/**
 * AutoSync for one mpv source (Android libmpv, iOS MPVKit). mpv exposes no embedded subtitle
 * timing while it plays, so only the container's own subtitle index serves as the reference.
 * The original subtitle shows right away; a confident result replaces it with a copy whose
 * timestamps are rewritten.
 */
internal class AutoSyncMpvCoordinator(
    private val scope: CoroutineScope,
    private val sourceUrl: String,
    private val sourceHeaders: Map<String, String>,
    private val getSubtitleHeaders: (String) -> Map<String, String>,
    private val getPreferredLanguage: () -> String?,
    private val platform: AutoSyncMpvPlatform,
    private val audioFallbackFactory: (() -> AutoSyncMpvAudioFallback?)? = null,
) {
    private var job: Job? = null
    private var operationToken = 0L
    private var candidates: List<AutoSyncSubtitleCandidate> = emptyList()
    private var contentType: String? = null
    private var videoId: String? = null
    private var appliedListener: ((subtitleUrl: String, delayMs: Int) -> Unit)? = null
    private var audioFallback: AutoSyncMpvAudioFallback? = null

    /**
     * [replaceExternalSubtitle] loads a local file in place of the external track that was loaded
     * from a URL, keeping that track's label.
     */
    fun wrap(
        controller: PlayerEngineController,
        replaceExternalSubtitle: (replacedUrl: String, path: String) -> Unit,
    ): PlayerEngineController =
        AutoSyncMpvEngineController(controller, this, replaceExternalSubtitle)

    fun setContent(contentType: String?, videoId: String?) {
        this.contentType = contentType
        this.videoId = videoId
        audioFallback?.setContent(contentType, videoId)
    }

    fun setCandidates(value: List<AutoSyncSubtitleCandidate>) {
        candidates = value.distinctBy { it.url }
        audioFallback?.setCandidates(candidates)
    }

    fun setAppliedListener(listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?) {
        appliedListener = listener
        audioFallback?.setAppliedListener(listener)
    }

    fun cancel(restoreAudioSyncOriginal: Boolean = false) {
        operationToken++
        job?.cancel()
        job = null
        audioFallback?.stop(restoreOriginalOnMpv = restoreAudioSyncOriginal)
    }

    fun dispose() {
        cancel()
        audioFallback?.release()
        audioFallback = null
        appliedListener = null
        AutoSyncSyncedSubtitle.clear()
    }

    private fun ensureAudioFallback(): AutoSyncMpvAudioFallback? {
        audioFallback?.let { return it }
        return audioFallbackFactory?.invoke()?.also { fallback ->
            fallback.setContent(contentType, videoId)
            fallback.setCandidates(candidates)
            fallback.setAppliedListener(appliedListener)
            audioFallback = fallback
        }
    }

    fun start(
        url: String,
        candidateScope: AutoSyncCandidateScope,
        base: PlayerEngineController,
        replaceExternalSubtitle: (replacedUrl: String, path: String) -> Unit,
    ) {
        cancel()
        val token = operationToken
        AutoSyncSyncedSubtitle.clear()
        base.setSubtitleUri(url)
        AutoSyncPreferencesRepository.ensureLoaded()
        if (decideAutoSyncStart(AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value) ==
            AutoSyncStartAction.ATTACH_ORIGINAL ||
            !(sourceUrl.startsWith("https://", true) || sourceUrl.startsWith("http://", true))
        ) {
            return
        }

        val subtitleHeaders = getSubtitleHeaders(url)
        val subtitleLanguage = candidates.firstOrNull { it.url == url }?.language
            ?.takeIf { it.isNotBlank() } ?: getPreferredLanguage()
        EmbeddedSubtitleTimelineLoader.prefetch(scope, sourceUrl, sourceHeaders)

        val fallback = ensureAudioFallback()
        fallback?.arm()
        val statusRun = AutoSyncSyncedSubtitle.start(url)

        job = scope.launch {
            var audioTookOver = false
            try {
                var outcome: AutoSyncAnalysisOutcome? = null
                suspend fun search(searchScope: AutoSyncCandidateScope) =
                    AutomaticSubtitleSync.findTimelineRetime(
                        sourceKey = sourceUrl,
                        sourceHeaders = sourceHeaders,
                        selectedSubtitleUrl = url,
                        selectedSubtitleHeaders = subtitleHeaders,
                        preferredLanguage = subtitleLanguage,
                        alternativeSubtitles = searchScope.alternativeCandidates(candidates),
                        alternativeSubtitlesProvider = if (searchScope.usesAlternativeProvider) {
                            { candidates }
                        } else null,
                        onAnalysisOutcome = { outcome = it },
                        allowLiveReference = false,
                    )
                var resolved = search(candidateScope)
                // The restored subtitle does not fit: look for another one that does.
                if (
                    resolved == null && token == operationToken &&
                    candidateScope.searchAfterSelectedFailed(outcome)
                ) {
                    resolved = search(AutoSyncCandidateScope.STARTUP_SEARCH)
                }
                currentCoroutineContext().ensureActive()
                if (token != operationToken) return@launch
                if (resolved == null) {
                    audioTookOver = outcome != AutoSyncAnalysisOutcome.SUBTITLE_UNAVAILABLE &&
                        fallback?.takeOver(url) == true
                    if (!audioTookOver) {
                        AutoSyncSyncedSubtitle.mark(url, outcome.toFailureState())
                    }
                    return@launch
                }

                val chosenUrl = resolved.subtitleUrl
                val tolerance = AutoSyncPreferencesRepository.syncToleranceMs.value
                val withinTolerance = tolerance > 0 && chosenUrl == url &&
                    resolved.timeline.maxAlignmentShiftMs() <= tolerance
                if (!withinTolerance) {
                    val path = writeRetimedSubtitle(chosenUrl, resolved, subtitleLanguage)
                    currentCoroutineContext().ensureActive()
                    if (token != operationToken) return@launch
                    if (path == null) {
                        AutoSyncSyncedSubtitle.mark(url, AutoSyncState.FAILED)
                        return@launch
                    }
                    replaceExternalSubtitle(url, path)
                }
                base.setSubtitleDelayMs(0)
                appliedListener?.invoke(chosenUrl, 0)
                AutoSyncSyncedSubtitle.mark(
                    chosenUrl,
                    if (withinTolerance) AutoSyncState.NOT_NEEDED else AutoSyncState.SYNCED,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (token == operationToken) {
                    AutoSyncSyncedSubtitle.mark(url, AutoSyncState.FAILED)
                }
            } finally {
                if (!audioTookOver && currentCoroutineContext().isActive) {
                    fallback?.disarm()
                }
            }
        }.also { activeJob ->
            activeJob.invokeOnCompletion { AutoSyncSyncedSubtitle.end(statusRun) }
        }
    }

    /**
     * Writes the subtitle at [subtitleUrl] with the AutoSync timeline applied, and returns its path,
     * or null when its format cannot be retimed. The name follows the content, so mpv's `cached`
     * sub-add reuses the track when the same result is applied again.
     */
    private suspend fun writeRetimedSubtitle(
        subtitleUrl: String,
        resolved: AutoSyncResolvedTimeline,
        languageHint: String?,
    ): String? {
        val body = resolved.subtitleBody ?: AutomaticSubtitleSync.downloadSubtitleBody(
            url = subtitleUrl,
            headers = resolved.subtitleHeaders.ifEmpty { getSubtitleHeaders(subtitleUrl) },
            languageHint = languageHint,
        )
        val retimed = withContext(autoSyncCpuDispatcher) {
            AutoSyncSubtitleTextRetimer.retime(body, resolved.timeline)
        } ?: return null
        val prefix = "$LIBMPV_AUTOSYNC_FILE_PREFIX${subtitleUrl.hashCode().toHexString()}_"
        val extension = when {
            retimed.trimStart().startsWith("WEBVTT") -> "vtt"
            retimed.contains("[Events]", ignoreCase = true) -> "ass"
            else -> "srt"
        }
        return platform.writeSubtitleFile(
            fileName = "$prefix${retimed.hashCode().toHexString()}.$extension",
            stalePrefix = prefix,
            content = retimed,
        )
    }

    /** Same digits as java.lang.Integer.toHexString. */
    private fun Int.toHexString(): String = toUInt().toString(16)
}

/**
 * A player's mpv controller with AutoSync layered on top. Every call is forwarded to [base]
 * unchanged; subtitle changes first stop any AutoSync run that still targets the old subtitle.
 * Open so a platform can keep extra interfaces of its controller (iOS system controls).
 */
internal open class AutoSyncMpvEngineController(
    private val base: PlayerEngineController,
    private val coordinator: AutoSyncMpvCoordinator,
    private val replaceExternalSubtitle: (replacedUrl: String, path: String) -> Unit,
) : PlayerEngineController by base, AutoSyncPlayerController {

    override fun setAutoSyncContent(contentType: String?, videoId: String?) =
        coordinator.setContent(contentType, videoId)

    override fun setAutoSyncSubtitleCandidates(candidates: List<AutoSyncSubtitleCandidate>) =
        coordinator.setCandidates(candidates)

    override fun setAutoSyncAppliedListener(
        listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
    ) = coordinator.setAppliedListener(listener)

    override fun setSubtitleUri(url: String) {
        coordinator.cancel()
        AutoSyncSyncedSubtitle.clear()
        base.setSubtitleUri(url)
    }

    override fun setSubtitleUriWithAutoSync(url: String) =
        coordinator.start(url, AutoSyncCandidateScope.STARTUP_SEARCH, base, replaceExternalSubtitle)

    override fun setSubtitleUriWithSelectedAutoSync(url: String) =
        coordinator.start(url, AutoSyncCandidateScope.SELECTED_ONLY, base, replaceExternalSubtitle)

    override fun setSubtitleUriWithRestoredAutoSync(url: String) =
        coordinator.start(url, AutoSyncCandidateScope.SELECTED_THEN_SEARCH, base, replaceExternalSubtitle)

    override fun selectSubtitleTrack(index: Int) {
        coordinator.cancel()
        AutoSyncSyncedSubtitle.clear()
        base.selectSubtitleTrack(index)
    }

    override fun clearExternalSubtitle() {
        coordinator.cancel()
        AutoSyncSyncedSubtitle.clear()
        base.clearExternalSubtitle()
    }

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        coordinator.cancel()
        AutoSyncSyncedSubtitle.clear()
        base.clearExternalSubtitleAndSelect(trackIndex)
    }

    override fun cancelForManualSubtitleDelay() {
        // The same subtitle stays on with the user's delay, so the audio-synced copy gives way to the original.
        coordinator.cancel(restoreAudioSyncOriginal = true)
        AutoSyncSyncedSubtitle.clear()
    }
}
