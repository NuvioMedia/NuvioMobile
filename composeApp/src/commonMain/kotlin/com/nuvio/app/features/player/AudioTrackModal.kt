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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_audio_amplification
import nuvio.composeapp.generated.resources.compose_player_audio_amplification_keep
import nuvio.composeapp.generated.resources.compose_player_audio_delay
import nuvio.composeapp.generated.resources.compose_player_audio_tracks
import nuvio.composeapp.generated.resources.compose_player_center_mix
import nuvio.composeapp.generated.resources.compose_player_no_audio_tracks_available
import nuvio.composeapp.generated.resources.compose_player_reset
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

private const val AUDIO_DELAY_HOLD_STEP_MS = 50
private const val AUDIO_DELAY_HOLD_FAST_STEP_MS = 100
private const val AUDIO_DELAY_HOLD_FAST_AFTER_MS = 2_000L
private val AUDIO_PANEL_SIDE_BY_SIDE_MIN_WIDTH = 760.dp

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
    showAudioAmplification: Boolean = false,
    audioAmplificationDb: Int = 0,
    keepAudioAmplification: Boolean = false,
    onAudioAmplificationChanged: (Int) -> Unit = {},
    onKeepAudioAmplificationChanged: (Boolean) -> Unit = {},
    showCenterMix: Boolean = false,
    centerMixLevelDb: Int = 0,
    onCenterMixLevelChanged: (Int) -> Unit = {},
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
            val panelMaxHeight = maxHeight
            val hasAdjustments = showAudioDelay || showAudioAmplification || showCenterMix
            val adjustments: @Composable () -> Unit = {
                if (showAudioDelay) {
                    AudioDelaySection(
                        delayMs = audioDelayMs,
                        onDelayChanged = onAudioDelayChanged,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                if (showAudioAmplification) {
                    AudioAmplificationSection(
                        gainDb = audioAmplificationDb,
                        keep = keepAudioAmplification,
                        onGainChanged = onAudioAmplificationChanged,
                        onKeepChanged = onKeepAudioAmplificationChanged,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                if (showCenterMix) {
                    CenterMixSection(
                        levelDb = centerMixLevelDb,
                        onLevelChanged = onCenterMixLevelChanged,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            val tracksColumn: @Composable (Modifier) -> Unit = { columnModifier ->
                Column(
                    modifier = columnModifier,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    AudioTracksHeader()
                    if (audioTracks.isEmpty()) {
                        NoAudioTracksText()
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
                }
            }

            if (!hasAdjustments) {
                tracksColumn(
                    Modifier
                        .width(railWidth)
                        .fillMaxHeight()
                        .align(Alignment.BottomStart),
                )
            } else if (maxWidth >= AUDIO_PANEL_SIDE_BY_SIDE_MIN_WIDTH) {
                // Wide enough: tracks on the left and adjustments on the right, so neither squeezes the other.
                val columnWidth = minOf((maxWidth - 32.dp) / 2, 444.dp)
                Row(
                    modifier = Modifier.align(Alignment.BottomStart),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    tracksColumn(
                        Modifier
                            .width(columnWidth)
                            .heightIn(max = panelMaxHeight),
                    )
                    Column(
                        modifier = Modifier
                            .width(columnWidth)
                            .heightIn(max = panelMaxHeight)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        adjustments()
                    }
                }
            } else {
                // One rail that scrolls as a whole, so the tracks keep their size next to the adjustments.
                Column(
                    modifier = Modifier
                        .width(railWidth)
                        .heightIn(max = panelMaxHeight)
                        .align(Alignment.BottomStart)
                        .verticalScroll(rememberScrollState()),
                ) {
                    AudioTracksHeader()
                    if (audioTracks.isEmpty()) {
                        NoAudioTracksText()
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            audioTracks.forEach { track ->
                                key("${track.index}:${track.id}") {
                                    AudioTrackRow(
                                        track = track,
                                        isSelected = track.index == selectedIndex,
                                        onClick = { onTrackSelected(track.index) },
                                    )
                                }
                            }
                        }
                    }
                    adjustments()
                }
            }
        }
    }
}

@Composable
private fun AudioTracksHeader() {
    Text(
        text = stringResource(Res.string.compose_player_audio_tracks),
        color = Color.White,
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun NoAudioTracksText() {
    Text(
        text = stringResource(Res.string.compose_player_no_audio_tracks_available),
        color = Color.White.copy(alpha = 0.7f),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
    )
}

@Composable
private fun CenterMixSection(
    levelDb: Int,
    onLevelChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentDb = levelDb.coerceIn(CENTER_MIX_LEVEL_MIN_DB, CENTER_MIX_LEVEL_MAX_DB)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(Res.string.compose_player_center_mix),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SubtitleStyleStepper(
                value = if (currentDb > 0) "+$currentDb dB" else "$currentDb dB",
                onDecrease = { onLevelChanged(currentDb - 1) },
                onIncrease = { onLevelChanged(currentDb + 1) },
            )
            SubtitleTextAction(
                label = stringResource(Res.string.compose_player_reset),
                enabled = currentDb != 0,
                onClick = { onLevelChanged(0) },
            )
        }
    }
}

@Composable
private fun AudioAmplificationSection(
    gainDb: Int,
    keep: Boolean,
    onGainChanged: (Int) -> Unit,
    onKeepChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentDb = gainDb.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(Res.string.compose_player_audio_amplification),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SubtitleStyleStepper(
                value = if (currentDb > 0) "+$currentDb dB" else "$currentDb dB",
                onDecrease = { onGainChanged(currentDb - 1) },
                onIncrease = { onGainChanged(currentDb + 1) },
            )
            AudioToggleChip(
                label = stringResource(Res.string.compose_player_audio_amplification_keep),
                checked = keep,
                onCheckedChange = onKeepChanged,
            )
        }
    }
}

@Composable
private fun AudioToggleChip(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val contentColor = if (checked) tokens.colors.onAccent else Color.White

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) tokens.colors.accent else Color.White.copy(alpha = 0.08f))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
