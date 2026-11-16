package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.i18n.localizedEpisodeTitle
import com.nuvio.app.core.ui.AppIconResource
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.accentBrush
import com.nuvio.app.core.ui.appIconPainter
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.core.ui.themePalette
import com.nuvio.app.features.streams.StreamBadge
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween

private val TileShape = RoundedCornerShape(NuvioTokens.Radius.xl)
private val TileColor = Color.White.copy(alpha = 0.08f)
private val TileBorder = Color.White.copy(alpha = 0.06f)
private val TileGap = 10.dp
private val RowHeight = 60.dp
private val SideTileWidth = 56.dp
private const val DimmedAlpha = 0.02f

@Composable
internal fun FoldControls(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    playbackSnapshot: PlayerPlaybackSnapshot,
    displayedPositionMs: Long,
    badges: List<StreamBadge>,
    resizeMode: PlayerResizeMode,
    showRemainingTime: Boolean,
    onRuntimeClick: () -> Unit,
    onInteraction: () -> Unit,
    onBack: () -> Unit,
    onLockToggle: () -> Unit,
    onPartyClick: (() -> Unit)?,
    partyActive: Boolean,
    onNextEpisodeClick: (() -> Unit)?,
    onTogglePlayback: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onScrubChange: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    onSubtitleClick: () -> Unit,
    onAudioClick: () -> Unit,
    onSourcesClick: (() -> Unit)?,
    onEpisodesClick: (() -> Unit)?,
    onChaptersClick: (() -> Unit)?,
    chapters: List<PlayerChapter>,
    onSpeedClick: () -> Unit,
    onResizeModeClick: () -> Unit,
    onVideoSettingsClick: (() -> Unit)?,
    onOpenInExternalPlayer: (() -> Unit)?,
    onStreamInfoClick: () -> Unit,
    onSubmitIntroClick: (() -> Unit)?,
    onSwitchEngineClick: (() -> Unit)?,
    gestureController: PlayerGestureController?,
    dimmed: Boolean,
    onWake: () -> Unit,
    horizontalSafePadding: Dp,
    modifier: Modifier = Modifier,
) {
    val currentOnWake by rememberUpdatedState(onWake)
    val contentAlpha by animateFloatAsState(
        targetValue = if (dimmed) DimmedAlpha else 1f,
        animationSpec = tween(durationMillis = if (dimmed) 700 else 150),
        label = "foldControlsAlpha",
    )
    val actions = playerControlActions(
        playbackSnapshot = playbackSnapshot,
        resizeMode = resizeMode,
        onSubtitleClick = onSubtitleClick,
        onAudioClick = onAudioClick,
        onSourcesClick = onSourcesClick,
        onEpisodesClick = onEpisodesClick,
        onChaptersClick = onChaptersClick,
        onNextEpisodeClick = null,
        onSpeedClick = onSpeedClick,
        onResizeModeClick = onResizeModeClick,
        onVideoSettingsClick = onVideoSettingsClick,
        onOpenInExternalPlayer = onOpenInExternalPlayer,
        onStreamInfoClick = onStreamInfoClick,
        onSubmitIntroClick = onSubmitIntroClick,
    ) + listOfNotNull(
        onSwitchEngineClick?.let {
            PlayerControlAction(
                stringResource(Res.string.cd_switch_player_engine), it,
                icon = Icons.Rounded.SwapHoriz,
            )
        },
    )
    val interact: (() -> Unit) -> () -> Unit = { action ->
        {
            onInteraction()
            action()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(dimmed) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    currentOnWake()
                    if (dimmed) {
                        down.consume()
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
            }
            .graphicsLayer { alpha = contentAlpha }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(horizontal = 16.dp + horizontalSafePadding, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(TileGap, Alignment.CenterVertically),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(RowHeight),
            horizontalArrangement = Arrangement.spacedBy(TileGap),
        ) {
            IconTile(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                description = stringResource(Res.string.compose_player_close),
                onClick = onBack,
            )
            NowPlayingTile(
                title = title,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                episodeTitle = episodeTitle,
                onNextEpisodeClick = onNextEpisodeClick?.let(interact),
                modifier = Modifier.weight(1f),
            )
            IconTile(
                icon = Icons.Rounded.Lock,
                description = stringResource(Res.string.compose_player_lock_controls),
                onClick = onLockToggle,
            )
            if (onPartyClick != null) {
                IconTile(
                    icon = Icons.Rounded.Groups,
                    description = stringResource(Res.string.party_title),
                    onClick = interact(onPartyClick),
                    tint = if (partyActive) MaterialTheme.colorScheme.primary else Color.White,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 72.dp, max = 132.dp),
            horizontalArrangement = Arrangement.spacedBy(TileGap),
        ) {
            if (gestureController != null) {
                LevelTile(
                    icon = { Icons.Rounded.LightMode },
                    description = stringResource(Res.string.compose_player_brightness),
                    read = gestureController::currentBrightness,
                    write = gestureController::setBrightness,
                    modifier = Modifier.width(SideTileWidth),
                )
            }
            Tile(onClick = interact(onSeekBack), modifier = Modifier.weight(1f).fillMaxHeight()) {
                Icon(
                    imageVector = Icons.Rounded.Replay10,
                    contentDescription = stringResource(Res.string.compose_player_seek_back_10),
                    tint = Color.White,
                    modifier = Modifier.size(NuvioTokens.Icon.xl),
                )
            }
            Tile(
                onClick = interact(onTogglePlayback),
                modifier = Modifier.weight(1.4f).fillMaxHeight(),
                background = MaterialTheme.themePalette.accentBrush(),
            ) {
                if (playbackSnapshot.isLoading) {
                    NuvioLoadingIndicator(color = Color.White, modifier = Modifier.size(NuvioTokens.Icon.xxl))
                } else {
                    Icon(
                        painter = appIconPainter(
                            if (playbackSnapshot.isPlaying) AppIconResource.PlayerPause else AppIconResource.PlayerPlay,
                        ),
                        contentDescription = stringResource(
                            if (playbackSnapshot.isPlaying) Res.string.compose_action_pause else Res.string.detail_btn_play,
                        ),
                        tint = Color.White,
                        modifier = Modifier.size(NuvioTokens.Icon.xxl),
                    )
                }
            }
            Tile(onClick = interact(onSeekForward), modifier = Modifier.weight(1f).fillMaxHeight()) {
                Icon(
                    imageVector = Icons.Rounded.Forward10,
                    contentDescription = stringResource(Res.string.compose_player_seek_forward_10),
                    tint = Color.White,
                    modifier = Modifier.size(NuvioTokens.Icon.xl),
                )
            }
            if (gestureController != null) {
                LevelTile(
                    icon = { level -> if (level <= 0.001f) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp },
                    description = stringResource(Res.string.compose_player_volume),
                    read = { gestureController.currentVolume()?.fraction },
                    write = { gestureController.setVolume(it)?.fraction },
                    modifier = Modifier.width(SideTileWidth),
                )
            }
        }

        Tile(onClick = null, modifier = Modifier.fillMaxWidth().heightIn(min = RowHeight)) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (badges.isNotEmpty()) {
                    PlayerBadges(badges = badges, modifier = Modifier.padding(bottom = 10.dp))
                }
                PlayerChapterTitle(chapters = chapters, positionMs = displayedPositionMs)
                TimelineRow(
                    chapters = chapters,
                    playbackSnapshot = playbackSnapshot,
                    displayedPositionMs = displayedPositionMs,
                    showRemainingTime = showRemainingTime,
                    onScrubChange = onScrubChange,
                    onScrubFinished = {
                        onInteraction()
                        onScrubFinished(it)
                    },
                    onRuntimeClick = interact(onRuntimeClick),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(TileGap),
        ) {
            actions.forEach { action ->
                ActionTile(action = action, onClick = interact(action.onClick))
            }
        }
    }
}

@Composable
private fun Tile(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    background: Brush = SolidColor(TileColor),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(TileShape)
            .background(background)
            .border(NuvioTokens.Border.thin, TileBorder, TileShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
private fun IconTile(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
) {
    Tile(onClick = onClick, modifier = Modifier.size(RowHeight)) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(NuvioTokens.Icon.lg))
    }
}

@Composable
private fun NowPlayingTile(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    onNextEpisodeClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val typeScale = MaterialTheme.nuvioTypeScale
    Tile(onClick = null, modifier = modifier.fillMaxHeight()) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = typeScale.bodyMd.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (seasonNumber != null && episodeNumber != null) {
                    val episodeCode = stringResource(Res.string.compose_player_episode_code_full, seasonNumber, episodeNumber)
                    val displayEpisodeTitle = localizedEpisodeTitle(episodeTitle, episodeNumber)
                    Text(
                        text = if (displayEpisodeTitle.isNullOrBlank()) episodeCode else "$episodeCode • $displayEpisodeTitle",
                        style = typeScale.labelSm,
                        color = Color.White.copy(alpha = 0.64f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onNextEpisodeClick != null) {
                Box(
                    modifier = Modifier.size(48.dp).clip(TileShape).clickable(onClick = onNextEpisodeClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = stringResource(Res.string.player_next_episode),
                        tint = Color.White,
                        modifier = Modifier.size(NuvioTokens.Icon.lg),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionTile(action: PlayerControlAction, onClick: () -> Unit) {
    Tile(onClick = onClick, modifier = Modifier.width(92.dp).height(76.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (action.painter != null) {
                Icon(action.painter, null, tint = Color.White, modifier = Modifier.size(NuvioTokens.Icon.md))
            } else if (action.icon != null) {
                Icon(action.icon, null, tint = Color.White, modifier = Modifier.size(NuvioTokens.Icon.md))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = action.description,
                style = MaterialTheme.nuvioTypeScale.labelSm.copy(fontSize = 11.sp, lineHeight = 13.sp),
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

@Composable
private fun TimeLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.nuvioTypeScale.labelSm,
        color = Color.White.copy(alpha = 0.85f),
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
internal fun FoldLockedControls(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(Res.string.compose_player_unlock_controls)
    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Tile(onClick = onUnlock, modifier = Modifier.height(RowHeight)) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.LockOpen, null, tint = Color.White, modifier = Modifier.size(NuvioTokens.Icon.lg))
                Spacer(Modifier.width(12.dp))
                Text(
                    text = label,
                    style = MaterialTheme.nuvioTypeScale.bodyMd.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun TimelineRow(
    chapters: List<PlayerChapter>,
    playbackSnapshot: PlayerPlaybackSnapshot,
    displayedPositionMs: Long,
    showRemainingTime: Boolean,
    onScrubChange: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    onRuntimeClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimeLabel(formatPlaybackTime(displayedPositionMs))
        PlayerTimeline(
            chapters = chapters,
            snapshot = playbackSnapshot,
            displayedPositionMs = displayedPositionMs,
            onScrubChange = onScrubChange,
            onScrubFinished = onScrubFinished,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        TimeLabel(
            text = if (showRemainingTime) {
                formatPlaybackRuntime(displayedPositionMs, playbackSnapshot.durationMs, true)
            } else {
                formatPlaybackTime(playbackSnapshot.durationMs)
            },
            modifier = Modifier.clickable(onClick = onRuntimeClick),
        )
    }
}

@Composable
private fun LevelTile(
    icon: (Float) -> ImageVector,
    description: String,
    read: () -> Float?,
    write: (Float) -> Float?,
    modifier: Modifier = Modifier,
) {
    var level by remember { mutableStateOf(read()) }
    var adjusting by remember { mutableStateOf(false) }
    LaunchedEffect(adjusting) {
        while (!adjusting) {
            delay(1000)
            level = read()
        }
    }
    val fraction = level?.coerceIn(0f, 1f) ?: 0f
    val update: (Float) -> Unit = { level = write(it.coerceIn(0f, 1f)) ?: level }
    val currentUpdate by rememberUpdatedState(update)
    val accent = MaterialTheme.themePalette.accentBrush()
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(TileShape)
            .background(TileColor)
            .border(NuvioTokens.Border.thin, TileBorder, TileShape)
            .drawBehind {
                clipRect(top = size.height * (1f - fraction)) {
                    drawRect(brush = accent, alpha = 0.5f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset -> currentUpdate(1f - offset.y / size.height) }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { adjusting = true },
                    onDragEnd = { adjusting = false },
                    onDragCancel = { adjusting = false },
                ) { change, _ ->
                    change.consume()
                    currentUpdate(1f - change.position.y / size.height)
                }
            }
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TimeLabel("${(fraction * 100).roundToInt()}")
            Spacer(Modifier.weight(1f))
            Icon(icon(fraction), null, tint = Color.White, modifier = Modifier.size(NuvioTokens.Icon.md))
        }
    }
}
