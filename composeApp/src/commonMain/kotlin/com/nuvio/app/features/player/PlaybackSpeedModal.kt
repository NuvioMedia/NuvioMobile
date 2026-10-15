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
import nuvio.composeapp.generated.resources.compose_player_playback_speed
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

private data class PlaybackSpeedOption(
    val speed: Float,
    val label: String,
)

private val playbackSpeedOptions = listOf(
    PlaybackSpeedOption(0.25f, "0.25x"),
    PlaybackSpeedOption(0.5f, "0.5x"),
    PlaybackSpeedOption(0.75f, "0.75x"),
    PlaybackSpeedOption(1.0f, "1x (Normal)"),
    PlaybackSpeedOption(1.25f, "1.25x"),
    PlaybackSpeedOption(1.5f, "1.5x"),
    PlaybackSpeedOption(1.75f, "1.75x"),
    PlaybackSpeedOption(2.0f, "2x"),
)

@Composable
fun PlaybackSpeedModal(
    visible: Boolean,
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
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
                    text = stringResource(Res.string.compose_player_playback_speed),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = railMaxHeight),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(playbackSpeedOptions, key = { it.speed }) { option ->
                        val isSelected = abs(currentSpeed - option.speed) < 0.05f
                        PlaybackSpeedRow(
                            option = option,
                            isSelected = isSelected,
                            onClick = {
                                onSpeedSelected(option.speed)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackSpeedRow(
    option: PlaybackSpeedOption,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val primaryColor = if (isSelected) tokens.colors.onAccent else Color.White
    val backgroundColor = if (isSelected) tokens.colors.accent else Color.White.copy(alpha = 0.07f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = option.label,
            color = primaryColor,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )

        if (isSelected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = primaryColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
