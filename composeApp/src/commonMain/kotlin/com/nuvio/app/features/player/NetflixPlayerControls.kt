package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Toc
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NetflixColors
import com.nuvio.app.core.ui.NetflixSans
import com.nuvio.app.core.ui.NuvioLoadingIndicator

/**
 * Netflix-style playback controls: back + centered title on top, big rewind / play / forward in
 * the middle, a red scrubber with time remaining, and a labelled action row along the bottom.
 * Every action of the stock controls is still here.
 */
@Composable
internal fun NetflixPlayerControls(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    playbackSnapshot: PlayerPlaybackSnapshot,
    displayedPositionMs: Long,
    resizeMode: PlayerResizeMode,
    showRemainingTime: Boolean,
    onRuntimeClick: () -> Unit,
    onNextEpisodeClick: (() -> Unit)?,
    onInteraction: () -> Unit,
    showPlaybackControls: Boolean,
    onLockToggle: () -> Unit,
    onBack: () -> Unit,
    onPartyClick: (() -> Unit)?,
    partyActive: Boolean,
    onTogglePlayback: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onResizeModeClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onSubtitleClick: () -> Unit,
    onAudioClick: () -> Unit,
    onVideoSettingsClick: (() -> Unit)?,
    onSourcesClick: (() -> Unit)?,
    onEpisodesClick: (() -> Unit)?,
    chapters: List<PlayerChapter>,
    onChaptersClick: (() -> Unit)?,
    onOpenInExternalPlayer: (() -> Unit)?,
    onSubmitIntroClick: (() -> Unit)?,
    onSwitchEngineClick: (() -> Unit)?,
    onStreamInfoClick: () -> Unit,
    parentalWarnings: List<ParentalWarning>,
    showParentalGuide: Boolean,
    onParentalGuideAnimationComplete: () -> Unit,
    onScrubChange: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    horizontalSafePadding: Dp,
    modifier: Modifier = Modifier,
) {
    fun act(block: () -> Unit): () -> Unit = {
        onInteraction()
        block()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        val sidePadding = if (compact) 12.dp else 28.dp

        ParentalGuideOverlay(
            warnings = parentalWarnings,
            isVisible = showParentalGuide,
            onAnimationComplete = onParentalGuideAnimationComplete,
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
            modifier = Modifier.align(Alignment.TopStart),
        )
        if (!showPlaybackControls) return@BoxWithConstraints

        // Netflix dims the whole frame a little, with deeper shade top and bottom.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.65f),
                        0.25f to Color.Black.copy(alpha = 0.25f),
                        0.7f to Color.Black.copy(alpha = 0.25f),
                        1f to Color.Black.copy(alpha = 0.8f),
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalSafePadding),
        ) {
            // ---- Top bar ----
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Top))
                    .padding(horizontal = sidePadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NetflixTopIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val episodeLine = if (seasonNumber != null && episodeNumber != null) {
                        buildString {
                            append("S$seasonNumber:E$episodeNumber")
                            if (!episodeTitle.isNullOrBlank()) append(" “$episodeTitle”")
                        }
                    } else {
                        null
                    }
                    Text(
                        text = if (episodeLine != null) "$title: $episodeLine" else title,
                        style = TextStyle(fontFamily = NetflixSans, fontWeight = FontWeight.Bold, fontSize = if (compact) 14.sp else 16.sp, color = Color.White),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    onPartyClick?.let {
                        NetflixTopIcon(Icons.Rounded.Groups, "Watch party", act(it), tint = if (partyActive) NetflixColors.Red else Color.White)
                    }
                    onSubmitIntroClick?.let { NetflixTopIcon(Icons.Rounded.Flag, "Submit intro", act(it)) }
                    onSwitchEngineClick?.let { NetflixTopIcon(Icons.Rounded.Build, "Switch engine", act(it)) }
                    onVideoSettingsClick?.let { NetflixTopIcon(Icons.Rounded.Tune, "Video settings", act(it)) }
                    onOpenInExternalPlayer?.let { NetflixTopIcon(Icons.AutoMirrored.Rounded.OpenInNew, "External player", act(it)) }
                    NetflixTopIcon(Icons.Rounded.Info, "Stream info", act(onStreamInfoClick))
                }
            }

            // ---- Center: rewind / play-pause / forward ----
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 40.dp else 88.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NetflixCenterButton(Icons.Rounded.Replay10, "Back 10 seconds", 52.dp, act(onSeekBack))
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .clickable(onClick = act(onTogglePlayback)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (playbackSnapshot.isLoading) {
                        NuvioLoadingIndicator(color = NetflixColors.Red, size = 56.dp)
                    } else {
                        Icon(
                            imageVector = if (playbackSnapshot.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playbackSnapshot.isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(76.dp),
                        )
                    }
                }
                NetflixCenterButton(Icons.Rounded.Forward10, "Forward 10 seconds", 52.dp, act(onSeekForward))
            }

            // ---- Bottom: scrubber, remaining time, action row ----
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Bottom))
                    .padding(horizontal = sidePadding)
                    .padding(bottom = 6.dp),
            ) {
                PlayerChapterTitle(chapters = chapters, positionMs = displayedPositionMs)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        PlayerTimeline(
                            snapshot = playbackSnapshot,
                            displayedPositionMs = displayedPositionMs,
                            onScrubChange = onScrubChange,
                            onScrubFinished = {
                                onInteraction()
                                onScrubFinished(it)
                            },
                            chapters = chapters,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = formatPlaybackRuntime(displayedPositionMs, playbackSnapshot.durationMs, !showRemainingTime),
                        modifier = Modifier
                            .padding(top = 20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClick = onRuntimeClick)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        style = TextStyle(fontFamily = NetflixSans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Color.White),
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 18.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val speedLabel = formatPlaybackSpeedLabel(playbackSnapshot.playbackSpeed)
                    NetflixPlayerAction(Icons.Rounded.Speed, "Speed ($speedLabel)", act(onSpeedClick))
                    NetflixPlayerAction(Icons.Rounded.LockOpen, "Lock", act(onLockToggle))
                    onEpisodesClick?.let { NetflixPlayerAction(Icons.Rounded.VideoLibrary, "Episodes", act(it)) }
                    onSourcesClick?.let { NetflixPlayerAction(Icons.Rounded.SwapHoriz, "Sources", act(it)) }
                    NetflixPlayerAction(Icons.Rounded.Subtitles, "Subtitles", act(onSubtitleClick))
                    NetflixPlayerAction(Icons.Rounded.Audiotrack, "Audio", act(onAudioClick))
                    onChaptersClick?.let { NetflixPlayerAction(Icons.AutoMirrored.Rounded.Toc, "Chapters", act(it)) }
                    NetflixPlayerAction(Icons.Rounded.AspectRatio, resizeMode.name, act(onResizeModeClick))
                    onNextEpisodeClick?.let { NetflixPlayerAction(Icons.Rounded.SkipNext, "Next Ep.", act(it)) }
                }
            }
        }
    }
}

@Composable
private fun NetflixTopIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun NetflixCenterButton(
    icon: ImageVector,
    description: String,
    size: Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size + 20.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(size))
    }
}

@Composable
private fun NetflixPlayerAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .widthIn(min = 64.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            style = TextStyle(fontFamily = NetflixSans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Color.White),
            maxLines = 1,
        )
    }
}
