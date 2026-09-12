package com.nuvio.app.features.player.skip

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.PlatformBackHandler
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_episode_title_format
import nuvio.composeapp.generated.resources.detail_btn_play
import nuvio.composeapp.generated.resources.player_next_episode
import nuvio.composeapp.generated.resources.player_next_episode_finding_source
import nuvio.composeapp.generated.resources.player_next_episode_playing_via_countdown
import nuvio.composeapp.generated.resources.player_next_episode_thumbnail
import nuvio.composeapp.generated.resources.player_next_episode_unaired
import org.jetbrains.compose.resources.stringResource

@Composable
fun NextEpisodeCard(
    nextEpisode: NextEpisodeInfo?,
    visible: Boolean,
    isAutoPlaySearching: Boolean,
    autoPlaySourceName: String?,
    autoPlayCountdownSec: Int?,
    blurred: Boolean,
    onPlayNext: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (nextEpisode == null) return

    PlatformBackHandler(enabled = visible, onBack = onDismiss)
    val isPlayable = nextEpisode.hasAired

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(animationSpec = tween(260), initialOffsetX = { it / 2 }) +
            fadeIn(animationSpec = tween(220)),
        exit = slideOutHorizontally(animationSpec = tween(200), targetOffsetX = { it / 2 }) +
            fadeOut(animationSpec = tween(160)),
        modifier = modifier,
    ) {
        val shape = RoundedCornerShape(16.dp)
        Row(
            modifier = Modifier
                .widthIn(max = 292.dp)
                .clip(shape)
                .background(Color(0xFF191919).copy(alpha = 0.89f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
                .clickable { if (isPlayable) onPlayNext() }
                .padding(horizontal = 9.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail
            Box(
                modifier = Modifier
                    .size(width = 78.dp, height = 44.dp)
                    .clip(RoundedCornerShape(9.dp)),
            ) {
                AsyncImage(
                    model = nextEpisode.thumbnail,
                    contentDescription = stringResource(Res.string.player_next_episode_thumbnail),
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (blurred) Modifier.blur(18.dp) else Modifier),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.32f),
                                ),
                            ),
                        ),
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(Res.string.player_next_episode),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        Res.string.compose_player_episode_title_format,
                        nextEpisode.season,
                        nextEpisode.episode,
                        nextEpisode.title,
                    ),
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                val autoPlayStatus = when {
                    !isPlayable -> nextEpisode.unairedMessage?.takeIf { it.isNotBlank() }
                        ?: stringResource(Res.string.player_next_episode_unaired)
                    isAutoPlaySearching -> stringResource(Res.string.player_next_episode_finding_source)
                    !autoPlaySourceName.isNullOrBlank() && autoPlayCountdownSec != null ->
                        stringResource(
                            Res.string.player_next_episode_playing_via_countdown,
                            autoPlaySourceName,
                            autoPlayCountdownSec,
                        )
                    else -> null
                }
                if (autoPlayStatus != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = autoPlayStatus,
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Play badge
            Row(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = if (isPlayable) Color.White else Color.White.copy(alpha = 0.65f),
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = if (isPlayable) {
                        stringResource(Res.string.detail_btn_play)
                    } else {
                        stringResource(Res.string.player_next_episode_unaired)
                    },
                    color = if (isPlayable) Color.White else Color.White.copy(alpha = 0.72f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 3.dp),
                )
            }
        }
    }
}
