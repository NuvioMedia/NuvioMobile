@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.autosync

import android.content.Context
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerSubtitleUtils
import com.nuvio.app.features.player.SidecarSubtitleController
import com.nuvio.app.features.player.addonSubtitleTrackId
import com.nuvio.app.features.player.getAssHandlerCompat
import com.nuvio.tv.libass.media.AssHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.autosync_toast_no_fit
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/** AutoSync for one mobile ExoPlayer session; subtitle changes invalidate every pending result. */
internal class AutoSyncPlayerCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val player: ExoPlayer,
    private val sidecar: SidecarSubtitleController,
    private val sourceUrl: String,
    private val sourceHeaders: Map<String, String>,
    private val getSubtitleHeaders: (String) -> Map<String, String>,
    private val getUseLibass: () -> Boolean,
    private val getPreferredLanguage: () -> String?,
    private val onMimeTypeSelected: (String) -> Unit,
    private val onSubtitleDelayChanged: (Int) -> Unit,
) {
    private var job: Job? = null
    private var selectedBodyJob: Job? = null
    private var operationToken = 0L
    private var candidates: List<AutoSyncSubtitleCandidate> = emptyList()
    private var appliedListener: ((subtitleUrl: String, delayMs: Int) -> Unit)? = null

    fun wrap(controller: PlayerEngineController): PlayerEngineController =
        AutoSyncPlayerEngineController(controller, this)

    fun setCandidates(value: List<AutoSyncSubtitleCandidate>) {
        candidates = value.distinctBy { it.url }
    }

    fun setAppliedListener(listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?) {
        appliedListener = listener
    }

    fun cancel() {
        operationToken++
        job?.cancel()
        job = null
        selectedBodyJob?.cancel()
        selectedBodyJob = null
    }

    fun dispose() {
        cancel()
        forgetLibassCopies()
        appliedListener = null
    }

    /** The subtitle is replaced or cleared: libass must load the original again next time. */
    fun forgetLibassCopies() {
        AutoSyncLibassSubtitles.clear(player.getAssHandlerCompat())
    }

    fun attachWithoutAutoSync(url: String, attach: (String) -> Unit) {
        cancel()
        forgetLibassCopies()
        val generationBefore = sidecar.currentGenerationFor(url)
        attach(url)
        val generationAfter = sidecar.currentGenerationFor(url)
        if (generationAfter != null && generationAfter != generationBefore) {
            onMimeTypeSelected(PlayerSubtitleUtils.mimeTypeFromUrl(url))
        } else {
            sidecar.stopSidecarAddonSubtitle(clearView = true)
        }
    }

    private fun showFailure(token: Long, resource: StringResource) {
        scope.launch {
            val text = getString(resource)
            if (operationToken == token) Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    fun start(url: String, candidateScope: AutoSyncCandidateScope, fallbackAttach: (String) -> Unit) {
        cancel()
        forgetLibassCopies()
        val token = operationToken
        AutoSyncPreferencesRepository.ensureLoaded()
        if (decideAutoSyncStart(AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value) ==
            AutoSyncStartAction.ATTACH_ORIGINAL ||
            !(sourceUrl.startsWith("https://", true) || sourceUrl.startsWith("http://", true))) {
            fallbackAttach(url)
            return
        }

        val useLibass = getUseLibass()
        val subtitleHeaders = getSubtitleHeaders(url)
        val subtitleLanguage = candidates.firstOrNull { it.url == url }?.language
            ?.takeIf { it.isNotBlank() } ?: getPreferredLanguage()
        // ASS/SSA stays on libass's own path so its styling is kept; the result is applied there.
        val assHandler = player.getAssHandlerCompat()
        if (useLibass && assHandler != null && PlayerSubtitleUtils.mimeTypeFromUrl(url) == MimeTypes.TEXT_SSA) {
            startLibass(token, url, candidateScope, subtitleHeaders, subtitleLanguage, assHandler, fallbackAttach)
            return
        }
        if (!sidecar.canAttachAddonSubtitleViaSidecar(url, useLibass)) {
            fallbackAttach(url)
            return
        }

        // Start index requests only once an add-on subtitle actually needs AutoSync.
        EmbeddedSubtitleTimelineLoader.prefetch(scope, sourceUrl, sourceHeaders)
        val bodyDeferred = CompletableDeferred<String?>()
        if (!sidecar.startSidecarAddonSubtitle(
                url = url,
                headers = subtitleHeaders,
                useLibass = useLibass,
                rawBodyLoader = { bodyDeferred.await() ?: error("Subtitle body unavailable") },
            )) {
            fallbackAttach(url)
            return
        }
        onMimeTypeSelected(PlayerSubtitleUtils.mimeTypeFromUrl(url))
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()

        selectedBodyJob = scope.launch {
            val body = try {
                AutomaticSubtitleSync.downloadSubtitleBody(url, subtitleHeaders, subtitleLanguage)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            bodyDeferred.complete(body)
        }.also { download ->
            download.invokeOnCompletion { bodyDeferred.complete(null) }
        }

        fun restoreOriginalIfRelevant() {
            if (token == operationToken && sidecar.activeSidecarSubtitleKey == null) fallbackAttach(url)
        }

        job = scope.launch {
            try {
                var outcome: AutoSyncAnalysisOutcome? = null
                val resolved = AutomaticSubtitleSync.findTimelineRetime(
                    sourceKey = sourceUrl,
                    sourceHeaders = sourceHeaders,
                    selectedSubtitleUrl = url,
                    selectedSubtitleHeaders = subtitleHeaders,
                    selectedSubtitleBodyDeferred = bodyDeferred,
                    preferredLanguage = subtitleLanguage,
                    alternativeSubtitles = candidateScope.alternativeCandidates(candidates),
                    alternativeSubtitlesProvider = if (candidateScope.usesAlternativeProvider) {
                        { candidates }
                    } else null,
                    onAnalysisOutcome = { outcome = it },
                )
                currentCoroutineContext().ensureActive()
                if (token != operationToken) return@launch
                if (resolved == null) {
                    restoreOriginalIfRelevant()
                    showNoFit(token, outcome)
                    return@launch
                }

                val chosenUrl = resolved.subtitleUrl
                val tolerance = AutoSyncPreferencesRepository.syncToleranceMs.value
                val withinTolerance = tolerance > 0 && chosenUrl == url &&
                    resolved.timeline.maxAlignmentShiftMs() <= tolerance
                val applied = when {
                    withinTolerance -> sidecar.activeSidecarSubtitleKey == url
                    chosenUrl == url -> applyAutoSyncSidecarTimeline(sidecar, url, resolved.timeline)
                    sidecar.activeSidecarSubtitleKey == null && sidecar.startSidecarAddonSubtitle(
                        url = chosenUrl,
                        headers = resolved.subtitleHeaders,
                        useLibass = useLibass,
                        rawBodyLoader = resolved.subtitleBody?.let { body -> suspend { body } },
                    ) -> applyAutoSyncSidecarTimeline(sidecar, chosenUrl, resolved.timeline)
                    else -> replaceAutoSyncSidecarSubtitle(
                        sidecar = sidecar,
                        expectedCurrentUrl = url,
                        url = chosenUrl,
                        headers = resolved.subtitleHeaders,
                        rawBody = resolved.subtitleBody,
                        useLibass = useLibass,
                        timeline = resolved.timeline,
                    )
                }
                currentCoroutineContext().ensureActive()
                if (token != operationToken) return@launch
                if (!applied) {
                    restoreOriginalIfRelevant()
                    return@launch
                }
                onMimeTypeSelected(PlayerSubtitleUtils.mimeTypeFromUrl(chosenUrl))
                onSubtitleDelayChanged(0)
                appliedListener?.invoke(chosenUrl, 0)
                AutoSyncSyncedSubtitle.mark(chosenUrl)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (token != operationToken) return@launch
                restoreOriginalIfRelevant()
            }
        }
    }

    /** Only a confirmed no-fit is worth telling the viewer; every other failure stays silent. */
    private fun showNoFit(token: Long, outcome: AutoSyncAnalysisOutcome?) {
        if (outcome == AutoSyncAnalysisOutcome.NO_FIT) showFailure(token, Res.string.autosync_toast_no_fit)
    }

    /**
     * ASS/SSA add-on subtitles drawn by libass. Nuvio loads the original as usual and libass keeps
     * showing it while the analysis runs. A confident result is applied by loading a copy whose
     * timestamps are rewritten into libass's track for it ([AutoSyncLibassSubtitles]), so styling is
     * kept and the media source is not reloaded.
     *
     * The startup search tries ASS/SSA candidates first, so the styling is kept whenever one fits.
     * Only when none does are the other formats tried; a fitting SRT/VTT is then attached through
     * Nuvio's sidecar and retimed there, and the ASS subtitle is put back if that fails. A synced
     * subtitle without styling beats a styled one that is out of sync.
     */
    private fun startLibass(
        token: Long,
        url: String,
        candidateScope: AutoSyncCandidateScope,
        subtitleHeaders: Map<String, String>,
        subtitleLanguage: String?,
        assHandler: AssHandler,
        attach: (String) -> Unit,
    ) {
        attach(url)
        EmbeddedSubtitleTimelineLoader.prefetch(scope, sourceUrl, sourceHeaders)
        fun isAss(candidateUrl: String) =
            PlayerSubtitleUtils.mimeTypeFromUrl(candidateUrl) == MimeTypes.TEXT_SSA

        job = scope.launch {
            try {
                var outcome: AutoSyncAnalysisOutcome? = null
                suspend fun search(ass: Boolean): AutoSyncResolvedTimeline? {
                    val pool = { candidates.filter { isAss(it.url) == ass } }
                    return AutomaticSubtitleSync.findTimelineRetime(
                        sourceKey = sourceUrl,
                        sourceHeaders = sourceHeaders,
                        selectedSubtitleUrl = url,
                        selectedSubtitleHeaders = subtitleHeaders,
                        preferredLanguage = subtitleLanguage,
                        alternativeSubtitles = candidateScope.alternativeCandidates(pool()),
                        alternativeSubtitlesProvider = if (candidateScope.usesAlternativeProvider) pool else null,
                        onAnalysisOutcome = { outcome = it },
                    )
                }
                var resolved = search(ass = true)
                // No ASS/SSA fits: try the other formats, unless the video has nothing to compare with.
                if (
                    resolved == null && candidateScope.usesAlternativeProvider &&
                    token == operationToken &&
                    outcome != AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS &&
                    outcome != AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE &&
                    candidates.any { !isAss(it.url) }
                ) {
                    resolved = search(ass = false)
                }
                currentCoroutineContext().ensureActive()
                if (token != operationToken) return@launch
                if (resolved == null) {
                    showNoFit(token, outcome)
                    return@launch
                }

                val chosenUrl = resolved.subtitleUrl
                if (!isAss(chosenUrl)) {
                    // Nuvio's own switch shows the SRT/VTT through the sidecar; retime it there.
                    attach(chosenUrl)
                    if (!applyAutoSyncSidecarTimeline(sidecar, chosenUrl, resolved.timeline)) {
                        if (token == operationToken) attach(url)
                        return@launch
                    }
                    onMimeTypeSelected(PlayerSubtitleUtils.mimeTypeFromUrl(chosenUrl))
                    onSubtitleDelayChanged(0)
                    appliedListener?.invoke(chosenUrl, 0)
                    AutoSyncSyncedSubtitle.mark(chosenUrl)
                    return@launch
                }
                val tolerance = AutoSyncPreferencesRepository.syncToleranceMs.value
                val withinTolerance = tolerance > 0 && chosenUrl == url &&
                    resolved.timeline.maxAlignmentShiftMs() <= tolerance
                if (!withinTolerance) {
                    val body = resolved.subtitleBody ?: AutomaticSubtitleSync.downloadSubtitleBody(
                        url = chosenUrl,
                        headers = resolved.subtitleHeaders.ifEmpty { getSubtitleHeaders(chosenUrl) },
                        languageHint = subtitleLanguage,
                    )
                    val retimed = withContext(autoSyncCpuDispatcher) {
                        AutoSyncSubtitleTextRetimer.retime(body, resolved.timeline)
                    } ?: return@launch
                    currentCoroutineContext().ensureActive()
                    if (token != operationToken) return@launch
                    val trackId = addonSubtitleTrackId(chosenUrl)
                    if (chosenUrl == url) {
                        AutoSyncLibassSubtitles.apply(assHandler, trackId, retimed)
                    } else {
                        // Nuvio's own switch loads the chosen subtitle; libass gets the synced copy.
                        AutoSyncLibassSubtitles.remember(assHandler, trackId, retimed)
                        attach(chosenUrl)
                    }
                }
                onSubtitleDelayChanged(0)
                appliedListener?.invoke(chosenUrl, 0)
                AutoSyncSyncedSubtitle.mark(chosenUrl)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Technical failures stay silent: libass keeps showing the original subtitle.
            }
        }
    }
}
