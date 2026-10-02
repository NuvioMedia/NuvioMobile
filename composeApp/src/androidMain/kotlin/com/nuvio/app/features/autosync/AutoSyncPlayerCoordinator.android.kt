@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.autosync

import android.content.Context
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerSubtitleUtils
import com.nuvio.app.features.player.SidecarSubtitleController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.autosync_toast_failed
import nuvio.composeapp.generated.resources.autosync_toast_failed_no_reference
import nuvio.composeapp.generated.resources.autosync_toast_failed_unsupported
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
        appliedListener = null
    }

    fun attachWithoutAutoSync(url: String, attach: (String) -> Unit) {
        cancel()
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
        if (!sidecar.canAttachAddonSubtitleViaSidecar(url, useLibass)) {
            fallbackAttach(url)
            showFailure(token, Res.string.autosync_toast_failed_unsupported)
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
            showFailure(token, Res.string.autosync_toast_failed)
            return
        }
        onMimeTypeSelected(PlayerSubtitleUtils.mimeTypeFromUrl(url))
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()

        selectedBodyJob = scope.launch {
            val body = try {
                AutomaticSubtitleSync.downloadSubtitleBody(url, subtitleHeaders)
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
                    preferredLanguage = candidates.firstOrNull { it.url == url }?.language
                        ?.takeIf { it.isNotBlank() } ?: getPreferredLanguage(),
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
                    showFailure(token, when (outcome) {
                        AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS,
                        AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE -> Res.string.autosync_toast_failed_no_reference
                        else -> Res.string.autosync_toast_failed
                    })
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
                    showFailure(token, Res.string.autosync_toast_failed)
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
                showFailure(token, Res.string.autosync_toast_failed)
            }
        }
    }
}
