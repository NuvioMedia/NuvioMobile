package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The file's chapters, from the right edge like the sources and episodes panels. The list opens
 * scrolled to the chapter playing; tapping a chapter seeks to its start.
 */
@Composable
internal fun PlayerChaptersPanel(
    visible: Boolean,
    chapters: List<PlayerChapter>,
    positionMs: Long,
    durationMs: Long,
    onChapterSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentIndex = PlayerChapters.indexAt(chapters, positionMs)
    val listState = rememberLazyListState()

    LaunchedEffect(visible) {
        if (visible) listState.scrollToItem((currentIndex - 1).coerceAtLeast(0))
    }

    PlayerSidePanel(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
        ) {
            PlayerPanelHeader(
                title = stringResource(Res.string.compose_player_panel_chapters, chapters.size),
            ) {
                PlayerDialogButton(
                    label = stringResource(Res.string.action_close),
                    onClick = onDismiss,
                )
            }

            Spacer(Modifier.height(16.dp))

            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxHeight(),
            ) {
                itemsIndexed(chapters, key = { _, chapter -> chapter.startMs }) { index, chapter ->
                    val endMs = chapters.getOrNull(index + 1)?.startMs ?: durationMs
                    val isCurrent = index == currentIndex
                    ChapterRow(
                        number = index + 1,
                        chapter = chapter,
                        isCurrent = isCurrent,
                        progress = if (isCurrent && endMs > chapter.startMs) {
                            ((positionMs - chapter.startMs).toFloat() / (endMs - chapter.startMs)).coerceIn(0f, 1f)
                        } else {
                            null
                        },
                        remainingMs = if (isCurrent) (endMs - positionMs).coerceAtLeast(0L) else null,
                        onClick = { onChapterSelected(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    number: Int,
    chapter: PlayerChapter,
    isCurrent: Boolean,
    progress: Float?,
    remainingMs: Long?,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.colors.surfaceCard)
            .then(if (isCurrent) Modifier.border(2.dp, tokens.colors.focusRing, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                if (isCurrent) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.colors.textMuted,
                    )
                }
            }
            Text(
                text = chapter.title ?: stringResource(Res.string.compose_player_chapter_number, number),
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) accent else tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatPlaybackTime(chapter.startMs),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
        }
        if (progress != null && remainingMs != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(start = 40.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.2f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .background(accent),
                    )
                }
                Text(
                    text = stringResource(Res.string.compose_player_chapter_remaining, formatPlaybackTime(remainingMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
        }
    }
}
