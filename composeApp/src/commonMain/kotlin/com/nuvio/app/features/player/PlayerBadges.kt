package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.streams.StreamBadge
import com.nuvio.app.features.streams.StreamBadgeChipSize
import com.nuvio.app.features.streams.StreamBadgeImage
import com.nuvio.app.features.streams.StreamBadgeMatcher
import com.nuvio.app.features.streams.StreamBadgeSettingsRepository
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlayerScreenRuntime.rememberPlayerBadges(): List<StreamBadge> {
    val settings by remember {
        StreamBadgeSettingsRepository.ensureLoaded()
        StreamBadgeSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    if (!settings.showInPlayer) return emptyList()
    val controller = playerController
    LaunchedEffect(controller, activeSourceUrl, selectedAudioIndex, controlsVisible, playbackSnapshot.isLoading) {
        if (controller != null && controlsVisible && !playbackSnapshot.isLoading) {
            streamMediaInfo = controller.getMediaInfo()
        }
    }
    val track = audioTracks.firstOrNull { it.index == selectedAudioIndex }
    val languageName = track?.language?.let(::languageLabelResForCode)?.let { stringResource(it) }
    val text = playbackMatchText(streamMediaInfo, listOfNotNull(track?.language, languageName))
    return remember(text, settings.rules, settings.playerBadgeSource) {
        val rules = settings.rules.forPlayer(settings.playerBadgeSource)
        StreamBadgeMatcher.matchedBadges(listOfNotNull(text.takeIf { it.isNotBlank() }), StreamBadgeMatcher.compile(rules))
            .filter { it.imageURL.isNotBlank() }
    }
}

@Composable
internal fun PlayerBadges(
    badges: List<StreamBadge>,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = { badges.forEach { StreamBadgeImage(badge = it, size = StreamBadgeChipSize.PLAYER) } },
        modifier = modifier,
    ) { measurables, constraints ->
        val gap = 4.dp.roundToPx()
        val placeables = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
        var width = 0
        var visibleCount = 0
        for (placeable in placeables) {
            val next = if (visibleCount == 0) placeable.width else width + gap + placeable.width
            if (next > constraints.maxWidth) break
            width = next
            visibleCount++
        }
        val visible = placeables.take(visibleCount)
        val height = visible.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            var x = 0
            visible.forEach { placeable ->
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width + gap
            }
        }
    }
}
