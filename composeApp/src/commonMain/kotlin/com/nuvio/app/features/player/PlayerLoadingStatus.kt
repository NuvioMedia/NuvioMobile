package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_loading_buffering
import nuvio.composeapp.generated.resources.player_loading_building
import nuvio.composeapp.generated.resources.player_loading_starting
import nuvio.composeapp.generated.resources.player_loading_subtitles
import nuvio.composeapp.generated.resources.player_loading_subtitles_from
import nuvio.composeapp.generated.resources.player_loading_subtitles_progress
import nuvio.composeapp.generated.resources.player_loading_subtitles_addon
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun playerLoadingStatusResource(
    showStatus: Boolean,
    controllerReady: Boolean,
    buffering: Boolean,
): StringResource? = when {
    !showStatus -> null
    !controllerReady -> Res.string.player_loading_building
    buffering -> Res.string.player_loading_buffering
    else -> Res.string.player_loading_starting
}

@Composable
internal fun playerLoadingStatusMessage(
    showStatus: Boolean,
    controllerReady: Boolean,
    buffering: Boolean,
): String? = playerLoadingStatusResource(showStatus, controllerReady, buffering)
    ?.let { stringResource(it) }

@Composable
internal fun subtitleLoadingStatusMessage(): String {
    val progress by SubtitleRepository.loadingProgress.collectAsStateWithLifecycle()
    return subtitleLoadingStatusMessage(progress)
}

@Composable
internal fun subtitleLoadingStatusMessage(progress: SubtitleLoadingProgress?): String = when {
    progress == null -> stringResource(Res.string.player_loading_subtitles)
    progress.completed == 0 -> stringResource(Res.string.player_loading_subtitles_from, progress.total)
    !progress.addonName.isNullOrBlank() -> stringResource(
        Res.string.player_loading_subtitles_addon,
        progress.addonName,
        progress.completed,
        progress.total,
    )
    else -> stringResource(Res.string.player_loading_subtitles_progress, progress.completed, progress.total)
}
