package com.nuvio.app.features.autosync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.SidecarSubtitleController
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.coroutines.CoroutineScope

/**
 * Creates the AutoSync coordinator for one ExoPlayer instance and disposes it with that player.
 * Pass the returned coordinator's [AutoSyncPlayerCoordinator.wrap] result to `onControllerReady`.
 */
@Composable
internal fun rememberAutoSyncCoordinator(
    scope: CoroutineScope,
    player: ExoPlayer,
    sidecar: SidecarSubtitleController,
    playerSourceKey: Any,
    sourceUrl: String,
    sourceHeaders: Map<String, String>,
    externalSubtitles: List<StreamSubtitle>,
    useLibass: Boolean,
    preferredSubtitleLanguage: String?,
    onMimeTypeSelected: (String) -> Unit,
    onSubtitleDelayChanged: (Int) -> Unit,
): AutoSyncPlayerCoordinator {
    val context = LocalContext.current
    val latestExternalSubtitles = rememberUpdatedState(externalSubtitles)
    val latestUseLibass = rememberUpdatedState(useLibass)
    val latestPreferredLanguage = rememberUpdatedState(preferredSubtitleLanguage)
    val coordinator = remember(playerSourceKey, player, sidecar, scope) {
        AutoSyncPlayerCoordinator(
            context = context,
            scope = scope,
            player = player,
            sidecar = sidecar,
            sourceUrl = sourceUrl,
            sourceHeaders = sourceHeaders,
            getSubtitleHeaders = { subtitleUrl ->
                latestExternalSubtitles.value
                    .firstOrNull { it.url == subtitleUrl }
                    ?.headers
                    .orEmpty()
            },
            getUseLibass = { latestUseLibass.value },
            getPreferredLanguage = { latestPreferredLanguage.value },
            onMimeTypeSelected = onMimeTypeSelected,
            onSubtitleDelayChanged = onSubtitleDelayChanged,
        )
    }
    DisposableEffect(coordinator) {
        onDispose { coordinator.dispose() }
    }
    return coordinator
}

/**
 * Nuvio's ExoPlayer controller with AutoSync layered on top. Every call is forwarded to [base]
 * unchanged; subtitle changes first stop any AutoSync run that still targets the old subtitle.
 */
internal class AutoSyncPlayerEngineController(
    private val base: PlayerEngineController,
    private val coordinator: AutoSyncPlayerCoordinator,
) : PlayerEngineController by base, AutoSyncPlayerController {

    override fun setAutoSyncSubtitleCandidates(candidates: List<AutoSyncSubtitleCandidate>) =
        coordinator.setCandidates(candidates)

    override fun setAutoSyncAppliedListener(
        listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
    ) = coordinator.setAppliedListener(listener)

    override fun setSubtitleUri(url: String) =
        coordinator.attachWithoutAutoSync(url, base::setSubtitleUri)

    override fun setSubtitleUriWithAutoSync(url: String) =
        coordinator.start(
            url = url,
            candidateScope = AutoSyncCandidateScope.STARTUP_SEARCH,
            fallbackAttach = base::setSubtitleUri,
        )

    override fun setSubtitleUriWithSelectedAutoSync(url: String) =
        coordinator.start(
            url = url,
            candidateScope = AutoSyncCandidateScope.SELECTED_ONLY,
            fallbackAttach = base::setSubtitleUri,
        )

    override fun selectSubtitleTrack(index: Int) {
        coordinator.cancel()
        base.selectSubtitleTrack(index)
    }

    override fun clearExternalSubtitle() {
        coordinator.cancel()
        base.clearExternalSubtitle()
    }

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        coordinator.cancel()
        base.clearExternalSubtitleAndSelect(trackIndex)
    }

    override fun cancelForManualSubtitleDelay() {
        coordinator.cancel()
        AutoSyncSyncedSubtitle.clear()
    }
}
