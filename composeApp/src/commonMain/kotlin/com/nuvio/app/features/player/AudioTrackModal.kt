package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_audio_delay
import nuvio.composeapp.generated.resources.compose_player_audio_tracks
import nuvio.composeapp.generated.resources.compose_player_no_audio_tracks_available
import nuvio.composeapp.generated.resources.compose_player_reset
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

private const val AUDIO_DELAY_HOLD_STEP_MS = 50
private const val AUDIO_DELAY_HOLD_FAST_STEP_MS = 100
private const val AUDIO_DELAY_HOLD_FAST_AFTER_MS = 2_000L

@Composable
fun AudioTrackModal(
    visible: Boolean,
    audioTracks: List<AudioTrack>,
    selectedIndex: Int,
    onTrackSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    showAudioDelay: Boolean = false,
    audioDelayMs: Int = 0,
    onAudioDelayChanged: (Int) -> Unit = {},
) {
    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        contentPadding = PaddingValues(start = 44.dp, end = 44.dp, top = 28.dp, bottom = 64.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val railWidth = minOf(maxWidth, 444.dp)
            val railMaxHeight = (maxHeight - 64.dp).coerceAtLeast(120.dp).coerceAtMost(620.dp)

            Column(
                modifier = Modifier
                    .width(railWidth)
                    .fillMaxHeight()
                    .align(Alignment.BottomStart),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    text = stringResource(Res.string.compose_player_audio_tracks),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                if (audioTracks.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.compose_player_no_audio_tracks_available),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = railMaxHeight),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(audioTracks, key = { "${it.index}:${it.id}" }) { track ->
                            AudioTrackRow(
                                track = track,
                                isSelected = track.index == selectedIndex,
                                onClick = { onTrackSelected(track.index) },
                            )
                        }
                    }
                }

                if (showAudioDelay) {
                    AudioDelaySection(
                        delayMs = audioDelayMs,
                        onDelayChanged = onAudioDelayChanged,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioDelaySection(
    delayMs: Int,
    onDelayChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentDelayMs = delayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS)
    // Holding a button steps faster the longer it is held.
    fun holdStepMs(heldMs: Long): Int =
        if (heldMs >= AUDIO_DELAY_HOLD_FAST_AFTER_MS) AUDIO_DELAY_HOLD_FAST_STEP_MS else AUDIO_DELAY_HOLD_STEP_MS

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(Res.string.compose_player_audio_delay),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SubtitleStyleStepper(
                value = formatAudioDelay(currentDelayMs),
                onDecrease = { onDelayChanged(currentDelayMs - AUDIO_DELAY_STEP_MS) },
                onIncrease = { onDelayChanged(currentDelayMs + AUDIO_DELAY_STEP_MS) },
                onDecreaseHold = { heldMs -> onDelayChanged(currentDelayMs - holdStepMs(heldMs)) },
                onIncreaseHold = { heldMs -> onDelayChanged(currentDelayMs + holdStepMs(heldMs)) },
                valueWidth = 96.dp,
            )
            SubtitleTextAction(
                label = stringResource(Res.string.compose_player_reset),
                enabled = currentDelayMs != 0,
                onClick = { onDelayChanged(0) },
            )
        }
    }
}

private fun formatAudioDelay(delayMs: Int): String {
    val sign = when {
        delayMs > 0 -> "+"
        delayMs < 0 -> "-"
        else -> ""
    }
    val absoluteMs = abs(delayMs)
    return "$sign${absoluteMs / 1000}.${(absoluteMs % 1000).toString().padStart(3, '0')}s"
}

@Composable
private fun AudioTrackRow(
    track: AudioTrack,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val primaryColor = if (isSelected) tokens.colors.onAccent else Color.White
    val secondaryColor = if (isSelected) {
        tokens.colors.onAccent.copy(alpha = 0.82f)
    } else {
        Color.White.copy(alpha = 0.72f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) tokens.colors.accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = localizedTrackDisplayName(track.label, track.language, track.index),
                color = primaryColor,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            track.language?.takeIf { it.isNotBlank() && it != "und" }?.let { language ->
                Text(
                    text = languageLabelForCode(language),
                    color = secondaryColor,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = tokens.colors.onAccent,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(20.dp),
            )
        }
    }
}
