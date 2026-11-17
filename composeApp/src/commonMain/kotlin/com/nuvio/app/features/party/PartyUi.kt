package com.nuvio.app.features.party

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.parseHexColor
import com.nuvio.app.features.profiles.profileAvatarImageUrl
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.party_episode_format
import nuvio.composeapp.generated.resources.party_error_ended
import nuvio.composeapp.generated.resources.party_error_full
import nuvio.composeapp.generated.resources.party_error_network
import nuvio.composeapp.generated.resources.party_error_not_found
import nuvio.composeapp.generated.resources.party_error_rate_limited
import nuvio.composeapp.generated.resources.party_error_removed
import nuvio.composeapp.generated.resources.party_sign_in_required
import nuvio.composeapp.generated.resources.party_status_buffering
import nuvio.composeapp.generated.resources.party_status_choosing
import nuvio.composeapp.generated.resources.party_status_elsewhere
import nuvio.composeapp.generated.resources.party_status_loading
import nuvio.composeapp.generated.resources.party_status_offline
import nuvio.composeapp.generated.resources.party_status_ready
import nuvio.composeapp.generated.resources.party_unavailable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal enum class PartyPresenceState {
    Watching,
    Busy,
    Away,
    Offline,
}

internal data class ProfileAvatar(
    val name: String,
    val imageUrl: String?,
    val color: String?,
)

@Composable
internal fun PartyAvatar(
    member: PartyMember,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    presence: PartyPresenceState? = null,
    ringColor: Color = MaterialTheme.nuvio.colors.background,
) {
    val storage = "${ServerConfigurationRepository.active.value.backendUrl}/storage/v1/object/"
    PartyAvatar(
        name = member.displayName,
        imageUrl = member.avatarUrl?.takeIf { it.startsWith(storage) },
        color = member.avatarColor,
        modifier = modifier,
        size = size,
        presence = presence,
        ringColor = ringColor,
    )
}

@Composable
internal fun PartyAvatar(
    name: String,
    imageUrl: String?,
    color: String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    presence: PartyPresenceState? = null,
    ringColor: Color = MaterialTheme.nuvio.colors.background,
) {
    val tokens = MaterialTheme.nuvio
    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(color?.let(::parseHexColor) ?: tokens.colors.surfaceCard),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.trim().take(1).uppercase(),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = (size.value * 0.4f).sp),
                fontWeight = FontWeight.SemiBold,
            )
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        if (presence != null) {
            PresenceDot(
                state = presence,
                ringColor = ringColor,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.3f),
            )
        }
    }
}

@Composable
private fun PresenceDot(
    state: PartyPresenceState,
    ringColor: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (state == PartyPresenceState.Busy) {
            val transition = rememberInfiniteTransition()
            val pulse by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1f + pulse
                        scaleY = 1f + pulse
                        alpha = 1f - pulse
                    }
                    .clip(CircleShape)
                    .background(tokens.colors.accent),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(ringColor),
        )
        Box(
            modifier = Modifier
                .fillMaxSize(0.62f)
                .clip(CircleShape)
                .then(
                    when (state) {
                        PartyPresenceState.Watching, PartyPresenceState.Busy -> Modifier.background(tokens.colors.accent)
                        PartyPresenceState.Away -> Modifier.background(tokens.colors.textMuted)
                        PartyPresenceState.Offline -> Modifier.border(1.5.dp, tokens.colors.textMuted, CircleShape)
                    },
                ),
        )
    }
}

@Composable
internal fun AvatarStack(
    members: List<PartyMember>,
    size: Dp,
    modifier: Modifier = Modifier,
    ringColor: Color = MaterialTheme.nuvio.colors.background,
    animateIn: Boolean = false,
) {
    val shown = members.take(3)
    val extra = members.size - shown.size
    val slots = shown.size + if (extra > 0) 1 else 0
    val step = size * 0.7f
    Box(modifier = modifier.width(size + step * (slots - 1).coerceAtLeast(0))) {
        shown.forEachIndexed { index, member ->
            key(member.id) {
                StackSlot(index = index, slots = slots, step = step, ringColor = ringColor, animateIn = animateIn) {
                    PartyAvatar(member = member, size = size)
                }
            }
        }
        if (extra > 0) {
            StackSlot(index = shown.size, slots = slots, step = step, ringColor = ringColor, animateIn = animateIn) {
                Box(
                    modifier = Modifier
                        .size(size)
                        .clip(CircleShape)
                        .background(MaterialTheme.nuvio.colors.surfaceCard),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "+$extra",
                        color = MaterialTheme.nuvio.colors.textPrimary,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = (size.value * 0.36f).sp),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun StackSlot(
    index: Int,
    slots: Int,
    step: Dp,
    ringColor: Color,
    animateIn: Boolean,
    content: @Composable () -> Unit,
) {
    val scale = remember { Animatable(if (animateIn) 0f else 1f) }
    if (animateIn) {
        LaunchedEffect(Unit) {
            delay(index * 90L)
            scale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
        }
    }
    Box(
        modifier = Modifier
            .offset(x = step * index)
            .zIndex((slots - index).toFloat())
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .border(2.dp, ringColor, CircleShape),
    ) {
        content()
    }
}

@Composable
internal fun OpenSeat(
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                drawCircle(
                    color = tokens.colors.textMuted,
                    radius = this.size.minDimension / 2f - 1.dp.toPx(),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = null,
            tint = tokens.colors.textSecondary,
            modifier = Modifier.size(size * 0.42f),
        )
    }
}

@Composable
internal fun rememberProfileAvatar(): ProfileAvatar {
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val avatars by AvatarRepository.avatars.collectAsStateWithLifecycle()
    val profile = profileState.activeProfile
    val avatar = profile?.avatarId?.let { id -> avatars.firstOrNull { it.id == id } }
    return ProfileAvatar(
        name = profile?.name.orEmpty(),
        imageUrl = profile?.let { profileAvatarImageUrl(it, avatar) },
        color = profile?.avatarColorHex,
    )
}

private fun ActiveParty.presenceOf(member: PartyMember): PartyPresence? =
    presence[member.id] ?: PartyPresence(memberId = member.id).takeIf { member.id == memberId }

internal fun ActiveParty.presenceState(member: PartyMember): PartyPresenceState {
    val presence = presenceOf(member) ?: return PartyPresenceState.Offline
    if (presence.mediaKey != media.key) return PartyPresenceState.Away
    return when (presence.status) {
        PartyMemberStatus.Ready -> PartyPresenceState.Watching
        PartyMemberStatus.Loading, PartyMemberStatus.Buffering -> PartyPresenceState.Busy
        PartyMemberStatus.Browsing, PartyMemberStatus.Behind -> PartyPresenceState.Away
    }
}

internal fun ActiveParty.behindMs(member: PartyMember): Long? =
    presenceOf(member)
        ?.takeIf { it.status == PartyMemberStatus.Behind && it.mediaKey == media.key }
        ?.let { it.behindMs ?: 0L }

internal val PartyError.messageRes: StringResource
    get() = when (this) {
        PartyError.NotFound -> Res.string.party_error_not_found
        PartyError.Full -> Res.string.party_error_full
        PartyError.Removed -> Res.string.party_error_removed
        PartyError.Ended -> Res.string.party_error_ended
        PartyError.RateLimited -> Res.string.party_error_rate_limited
        PartyError.SignInRequired -> Res.string.party_sign_in_required
        PartyError.Unavailable -> Res.string.party_unavailable
        PartyError.Network -> Res.string.party_error_network
    }

internal fun ActiveParty.statusRes(member: PartyMember): StringResource {
    val presence = presenceOf(member) ?: return Res.string.party_status_offline
    if (presence.mediaKey != media.key) {
        return if (presence.status == PartyMemberStatus.Browsing) {
            Res.string.party_status_choosing
        } else {
            Res.string.party_status_elsewhere
        }
    }
    return when (presence.status) {
        PartyMemberStatus.Ready, PartyMemberStatus.Behind -> Res.string.party_status_ready
        PartyMemberStatus.Buffering -> Res.string.party_status_buffering
        PartyMemberStatus.Loading -> Res.string.party_status_loading
        PartyMemberStatus.Browsing -> Res.string.party_status_choosing
    }
}

@Composable
internal fun PartyMedia.episodeLabel(): String? {
    val season = season ?: return null
    val episode = episode ?: return null
    val label = stringResource(Res.string.party_episode_format, season, episode)
    return episodeTitle?.takeIf { it.isNotBlank() }?.let { "$label · $it" } ?: label
}

@Composable
internal fun PartyMedia.displayTitle(): String {
    val season = season
    val episode = episode
    if (season == null || episode == null) return title
    val label = stringResource(Res.string.party_episode_format, season, episode)
    return if (title.isBlank()) label else "$title · $label"
}
