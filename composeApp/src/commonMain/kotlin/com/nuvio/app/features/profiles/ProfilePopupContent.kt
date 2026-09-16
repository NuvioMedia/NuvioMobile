package com.nuvio.app.features.profiles

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ProfilePopupContent(
    profiles: List<NuvioProfile>,
    avatars: List<AvatarCatalogItem>,
    activeProfileIndex: Int?,
    hoveredProfileIndex: Int?,
    pinProfile: NuvioProfile?,
    hazeState: HazeState?,
    opensBelow: Boolean,
    availableHeight: Dp?,
    modifier: Modifier = Modifier,
    onBoundsChanged: (Int, Rect) -> Unit,
    onDismissRequest: () -> Unit,
    onProfileSelected: (NuvioProfile) -> Unit,
    onAddProfileRequested: () -> Unit,
    onPinCancelled: () -> Unit,
    onPinVerified: (NuvioProfile) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(22.dp)
    val panelAlignment = if (opensBelow) Alignment.TopCenter else Alignment.BottomCenter
    val dismissPopup by rememberUpdatedState(onDismissRequest)
    var panelBounds by remember { mutableStateOf(Rect.Zero) }
    val items: List<NuvioProfile?> = if (profiles.size < MAX_PROFILES) profiles + listOf(null) else profiles
    BoxWithConstraints(modifier.widthIn(max = 336.dp)) {
        val availableWidth = (maxWidth - 32.dp).coerceAtLeast(0.dp)
        val preferredColumns = if (items.size == 4) 2 else items.size.coerceAtMost(3)
        val columns = ((availableWidth - 24.dp).value / 80f).toInt().coerceIn(1, preferredColumns.coerceAtLeast(1))
        val width = (24.dp + 80.dp * columns).coerceAtMost(availableWidth)
        val panelWidth = if (profiles.any { it.pinEnabled }) 264.dp.coerceAtMost(availableWidth) else width
        val popupHeight = (maxHeight * 0.65f).coerceAtMost(420.dp).coerceAtMost(availableHeight ?: maxHeight)
        Box(
            Modifier.width(panelWidth).height(popupHeight).pointerInput(Unit) {
                detectTapGestures { position ->
                    if (!panelBounds.contains(position)) dismissPopup()
                }
            },
            contentAlignment = panelAlignment,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { panelBounds = it.boundsInParent() }
                    .shadow(12.dp, shape)
                    .clip(shape)
                    .then(
                        if (hazeState != null) {
                            Modifier.hazeEffect(hazeState) {
                                blurRadius = 24.dp
                                backgroundColor = Color(0xFF1C1C1E)
                                tints = listOf(HazeTint(Color(0xFF1C1C1E).copy(alpha = 0.55f)))
                                fallbackTint = HazeTint(Color(0xFF1C1C1E).copy(alpha = 0.94f))
                                noiseFactor = 0f
                            }
                        } else {
                            Modifier.background(tokens.colors.background)
                        },
                    )
                    .border(0.5.dp, tokens.colors.borderDefault, shape)
                    .padding(12.dp),
            ) {
                AnimatedContent(
                    targetState = pinProfile,
                    transitionSpec = {
                        (fadeIn(tween(180, delayMillis = 20, easing = LinearOutSlowInEasing)) togetherWith
                            fadeOut(tween(120, easing = FastOutSlowInEasing)))
                            .using(SizeTransform(sizeAnimationSpec = { _, _ ->
                                tween(220, easing = FastOutSlowInEasing)
                            }))
                    },
                    contentAlignment = panelAlignment,
                    label = "profile_popup_content",
                ) { lockedProfile ->
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (lockedProfile != null) {
                            InlinePinEntry(
                                profileName = lockedProfile.name,
                                onVerified = { onPinVerified(lockedProfile) },
                                onCancel = onPinCancelled,
                                verifyPin = { ProfileRepository.verifyPin(lockedProfile.profileIndex, it) },
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items.chunked(columns).forEach { row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        row.forEach { profile ->
                                            val isActive = profile != null && profile.profileIndex == activeProfileIndex
                                            val isHovered = profile != null && !isActive && profile.profileIndex == hoveredProfileIndex
                                            ProfilePopupItem(
                                                name = profile?.name?.ifBlank { stringResource(Res.string.profile_label_number, profile.profileIndex) }
                                                    ?: stringResource(Res.string.compose_profile_add_profile),
                                                isActive = isActive,
                                                isHovered = isHovered,
                                                modifier = Modifier.weight(1f).onGloballyPositioned { coordinates ->
                                                    if (profile != null) onBoundsChanged(profile.profileIndex, coordinates.boundsOnScreen())
                                                },
                                                onClick = { if (profile != null) onProfileSelected(profile) else onAddProfileRequested() },
                                            ) {
                                                if (profile == null) {
                                                    Box(Modifier.size(40.dp).clip(tokens.shapes.avatar).background(tokens.colors.surfaceCard), contentAlignment = Alignment.Center) {
                                                        Icon(Icons.Rounded.Add, null, tint = tokens.colors.textMuted, modifier = Modifier.size(22.dp))
                                                    }
                                                } else {
                                                    Box(Modifier.size(40.dp)) {
                                                        ActiveProfileMiniAvatar(profile, avatars, isActive || isHovered, size = 40)
                                                        if (isActive || profile.pinEnabled) {
                                                            Box(
                                                                Modifier.align(Alignment.BottomEnd).size(16.dp).clip(tokens.shapes.avatar)
                                                                    .background(if (isActive) tokens.colors.accent else tokens.colors.surfacePopover),
                                                                contentAlignment = Alignment.Center,
                                                            ) {
                                                                Icon(
                                                                    if (isActive) Icons.Rounded.Check else Icons.Rounded.Lock,
                                                                    null,
                                                                    tint = if (isActive) tokens.colors.onAccent else tokens.colors.textMuted,
                                                                    modifier = Modifier.size(11.dp),
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfilePopupItem(
    name: String,
    isActive: Boolean,
    isHovered: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    avatar: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scale by animateFloatAsState(if (isHovered) 1.04f else 1f, tween(90), label = "profile_hover")
    Column(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(tokens.shapes.compactCard)
            .background(if (isHovered) tokens.colors.accent.copy(alpha = 0.14f) else Color.Transparent)
            .selectable(selected = isActive, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        avatar()
        Spacer(Modifier.height(6.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
            color = if (isActive || isHovered) tokens.colors.textPrimary else tokens.colors.textMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
