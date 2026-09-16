package com.nuvio.app.features.profiles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import dev.chrisbanes.haze.HazeState
import com.nuvio.app.isIos
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.min

@Composable
fun ProfileSwitcherTab(
    selected: Boolean,
    onClick: () -> Unit,
    onProfileSelected: (NuvioProfile) -> Unit,
    onAddProfileRequested: () -> Unit,
    triggerContent: (@Composable (selected: Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    popupBelowAnchor: Boolean = false,
) {
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val activeProfile = profileState.activeProfile
    val profiles = profileState.profiles
    val avatars by AvatarRepository.avatars.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        AvatarRepository.refreshAvatars()
    }

    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    var showPopup by remember { mutableStateOf(false) }
    // Keep popup composed while exit animation plays
    var popupVisible by remember { mutableStateOf(false) }
    var pinProfile by remember { mutableStateOf<NuvioProfile?>(null) }
    var dragTargetProfileIndex by remember { mutableStateOf<Int?>(null) }
    var triggerCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val profileBubbleBounds = remember(profiles.map { it.profileIndex }) {
        mutableStateMapOf<Int, Rect>()
    }

    fun performProfileHoldHaptic() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun performProfileHoverHaptic() {
        if (isIos) {
            ProfileHoverHapticFeedback.perform()
        } else {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun updateDragTarget(localPosition: Offset) {
        if (pinProfile != null) return
        val trigger = triggerCoordinates ?: return
        val screenPosition = trigger.localToScreen(localPosition)
        val nextTargetProfileIndex = profileBubbleBounds.entries
            .firstOrNull { (_, bounds) -> bounds.contains(screenPosition) }
            ?.key
        if (nextTargetProfileIndex != null && nextTargetProfileIndex != dragTargetProfileIndex) {
            performProfileHoverHaptic()
        }
        dragTargetProfileIndex = nextTargetProfileIndex
    }

    fun chooseProfile(profile: NuvioProfile) {
        routeProfileSelection(
            profile = profile,
            isEditMode = false,
            activeProfileIndex = ProfileRepository.state.value.activeProfile?.profileIndex,
            onEditProfile = {},
            onActiveProfileSelected = {
                scope.launch {
                    showAlreadyActiveProfileToast(it)
                    showPopup = false
                }
            },
            onPinRequired = { pinProfile = it },
            onProfileSelected = {
                showPopup = false
                onProfileSelected(it)
            },
        )
    }

    fun chooseDragTarget() {
        val profile = profiles.firstOrNull { it.profileIndex == dragTargetProfileIndex }
        dragTargetProfileIndex = null
        if (profile != null) {
            chooseProfile(profile)
        }
    }

    // Popup entrance/exit animation
    val popupAlpha = remember { Animatable(0f) }
    val popupScale = remember { Animatable(0.96f) }

    LaunchedEffect(showPopup) {
        if (showPopup) {
            popupVisible = true
            launch { popupAlpha.animateTo(1f, tween(180, easing = FastOutSlowInEasing)) }
            launch {
                popupScale.animateTo(
                    1f,
                    tween(180, easing = FastOutSlowInEasing),
                )
            }
        } else {
            ProfileHoverHapticFeedback.release()
            // Animate out
            launch { popupAlpha.animateTo(0f, tween(140, easing = FastOutSlowInEasing)) }
            launch {
                popupScale.animateTo(0.96f, tween(160, easing = FastOutSlowInEasing))
                // Remove from composition after animation completes
                popupVisible = false
                pinProfile = null
                dragTargetProfileIndex = null
            }
        }
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { triggerCoordinates = it }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .pointerInput(profiles, activeProfile?.profileIndex) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { startOffset ->
                        if (profiles.isNotEmpty()) {
                            performProfileHoldHaptic()
                            ProfileHoverHapticFeedback.prepare()
                            profileBubbleBounds.clear()
                            dragTargetProfileIndex = null
                            showPopup = true
                            updateDragTarget(startOffset)
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        updateDragTarget(change.position)
                    },
                    onDragEnd = {
                        ProfileHoverHapticFeedback.release()
                        chooseDragTarget()
                    },
                    onDragCancel = {
                        ProfileHoverHapticFeedback.release()
                        dragTargetProfileIndex = null
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (triggerContent != null) {
            triggerContent(selected)
        } else {
            ActiveProfileMiniAvatar(
                profile = activeProfile,
                avatars = avatars,
                selected = selected,
                size = 28,
            )
        }

        // Floating profile popup (stays composed during exit animation)
        if (popupVisible && profiles.isNotEmpty()) {
            var opensBelow by remember { mutableStateOf(popupBelowAnchor) }
            var availableHeight by remember { mutableStateOf<Dp?>(null) }
            val dismissPopup = { if (pinProfile != null) pinProfile = null else showPopup = false }
            Popup(
                popupPositionProvider = remember(density, popupBelowAnchor) {
                    ProfilePopupPositionProvider(
                        margin = with(density) { 16.dp.roundToPx() },
                        gap = with(density) { 18.dp.roundToPx() },
                        preferBelow = popupBelowAnchor,
                        onPositioned = { below, height ->
                            opensBelow = below
                            availableHeight = with(density) { height.toDp() }
                        },
                    )
                },
                properties = PopupProperties(focusable = true),
                onDismissRequest = dismissPopup,
            ) {
                ProfilePopupContent(
                    profiles = profiles,
                    avatars = avatars,
                    activeProfileIndex = activeProfile?.profileIndex,
                    hoveredProfileIndex = dragTargetProfileIndex,
                    pinProfile = pinProfile,
                    hazeState = hazeState,
                    opensBelow = opensBelow,
                    availableHeight = availableHeight,
                    modifier = Modifier.graphicsLayer {
                        alpha = popupAlpha.value
                        scaleX = popupScale.value
                        scaleY = popupScale.value
                        transformOrigin = TransformOrigin(
                            0.5f,
                            if (opensBelow) 0f else 1f,
                        )
                    },
                    onBoundsChanged = { index, bounds -> profileBubbleBounds[index] = bounds },
                    onDismissRequest = dismissPopup,
                    onProfileSelected = ::chooseProfile,
                    onAddProfileRequested = {
                        showPopup = false
                        onAddProfileRequested()
                    },
                    onPinCancelled = { pinProfile = null },
                    onPinVerified = { profile ->
                        if (
                            showPopup && pinProfile?.profileIndex == profile.profileIndex &&
                            profile.profileIndex != ProfileRepository.state.value.activeProfile?.profileIndex
                        ) {
                            onProfileSelected(profile)
                            showPopup = false
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun NativeProfileSwitcherPopup(
    visible: Boolean,
    isSwitchingProfile: Boolean,
    onDismissRequest: () -> Unit,
    onProfileSelected: (NuvioProfile) -> Unit,
    onAddProfileRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val activeProfile = profileState.activeProfile
    val profiles = profileState.profiles
    val avatars by AvatarRepository.avatars.collectAsStateWithLifecycle()
    val tokens = MaterialTheme.nuvio
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    var showPopup by remember { mutableStateOf(false) }
    var popupVisible by remember { mutableStateOf(false) }
    var pinProfile by remember { mutableStateOf<NuvioProfile?>(null) }

    LaunchedEffect(Unit) {
        AvatarRepository.refreshAvatars()
    }

    LaunchedEffect(visible, isSwitchingProfile, profiles.isNotEmpty()) {
        if (visible && !isSwitchingProfile) {
            if (profiles.isNotEmpty()) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showPopup = true
            } else {
                onDismissRequest()
                showPopup = false
            }
        } else {
            showPopup = false
        }
    }

    fun chooseProfile(profile: NuvioProfile) {
        routeProfileSelection(
            profile = profile,
            isEditMode = false,
            activeProfileIndex = ProfileRepository.state.value.activeProfile?.profileIndex,
            onEditProfile = {},
            onActiveProfileSelected = {
                scope.launch {
                    showAlreadyActiveProfileToast(it)
                    showPopup = false
                    onDismissRequest()
                }
            },
            onPinRequired = { pinProfile = it },
            onProfileSelected = {
                showPopup = false
                onDismissRequest()
                onProfileSelected(it)
            },
        )
    }

    val popupAlpha = remember { Animatable(0f) }
    val popupScale = remember { Animatable(0.5f) }
    val popupTranslateY = remember { Animatable(40f) }

    LaunchedEffect(showPopup) {
        if (showPopup) {
            popupVisible = true
            launch { popupAlpha.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }
            launch {
                popupScale.animateTo(
                    1f,
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                )
            }
            launch {
                popupTranslateY.animateTo(
                    0f,
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                )
            }
        } else if (popupVisible) {
            launch { popupAlpha.animateTo(0f, tween(180, easing = FastOutSlowInEasing)) }
            launch { popupScale.animateTo(0.85f, tween(200, easing = FastOutSlowInEasing)) }
            launch {
                popupTranslateY.animateTo(30f, tween(200, easing = FastOutSlowInEasing))
                popupVisible = false
                pinProfile = null
            }
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val anchorWidth = maxWidth / 4
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .fillMaxHeight()
                .width(anchorWidth),
        ) {
            if (popupVisible && profiles.isNotEmpty() && !isSwitchingProfile) {
                Popup(
                    alignment = Alignment.BottomCenter,
                    offset = IntOffset(0, with(density) { -NuvioTokens.Space.s64.roundToPx() }),
                    properties = PopupProperties(focusable = true),
                    onDismissRequest = onDismissRequest,
                ) {
                    Box(
                        modifier = Modifier
                            .imePadding()
                            .graphicsLayer {
                                alpha = popupAlpha.value
                                scaleX = popupScale.value
                                scaleY = popupScale.value
                                translationY = popupTranslateY.value
                            }
                            .shadow(tokens.elevation.overlay, tokens.shapes.sheet)
                            .background(
                                tokens.colors.surfaceSheet,
                                tokens.shapes.sheet,
                            )
                            .padding(tokens.spacing.sheetPadding),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.cardPadding),
                                verticalAlignment = Alignment.Top,
                            ) {
                                profiles.forEachIndexed { index, profile ->
                                    PopupProfileBubble(
                                        profile = profile,
                                        avatars = avatars,
                                        isActive = profile.profileIndex == activeProfile?.profileIndex,
                                        isSelected = pinProfile?.profileIndex == profile.profileIndex,
                                        delayMs = index * 50,
                                        onBoundsChanged = {},
                                        onClick = { chooseProfile(profile) },
                                    )
                                }

                                if (profiles.size < MAX_PROFILES) {
                                    PopupAddProfileBubble(
                                        delayMs = profiles.size * 50,
                                        onClick = {
                                            showPopup = false
                                            onDismissRequest()
                                            onAddProfileRequested()
                                        },
                                    )
                                }
                            }

                            AnimatedVisibility(
                                visible = pinProfile != null,
                                enter = expandVertically(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                ) + fadeIn(tween(200)),
                                exit = shrinkVertically(tween(150)) + fadeOut(tween(100)),
                            ) {
                                pinProfile?.let { profile ->
                                    InlinePinEntry(
                                        profileName = profile.name,
                                        onVerified = {
                                            showPopup = false
                                            onDismissRequest()
                                            if (profile.profileIndex != ProfileRepository.state.value.activeProfile?.profileIndex) {
                                                onProfileSelected(profile)
                                            }
                                        },
                                        onCancel = { pinProfile = null },
                                        verifyPin = { pin ->
                                            ProfileRepository.verifyPin(profile.profileIndex, pin)
                                        },
                                    )
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
private fun PopupAddProfileBubble(
    delayMs: Int,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val itemAlpha = remember { Animatable(0f) }
    val itemScale = remember { Animatable(0.4f) }

    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        launch { itemAlpha.animateTo(1f, tween(200, easing = FastOutSlowInEasing)) }
        launch {
            itemScale.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .graphicsLayer {
                alpha = itemAlpha.value
                scaleX = itemScale.value
                scaleY = itemScale.value
            }
            .clip(tokens.shapes.compactCard)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(NuvioTokens.Space.s4),
    ) {
        Box(
            modifier = Modifier
                .size(tokens.components.avatarSize)
                .clip(tokens.shapes.avatar)
                .background(tokens.colors.surfaceCard)
                .border(tokens.borders.thin + NuvioTokens.Space.hairline, tokens.colors.borderDefault.copy(alpha = tokens.opacity.medium), tokens.shapes.avatar),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(Res.string.compose_profile_add_profile),
                tint = tokens.colors.textMuted,
                modifier = Modifier.size(tokens.icons.md + NuvioTokens.Space.s2),
            )
        }

        Spacer(modifier = Modifier.height(NuvioTokens.Space.s4))

        Text(
            text = stringResource(Res.string.compose_profile_add_profile),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = NuvioTokens.Type.labelXs),
            color = tokens.colors.textMuted,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.width(56.dp),
        )
    }
}

@Composable
private fun PopupProfileBubble(
    profile: NuvioProfile,
    avatars: List<AvatarCatalogItem>,
    isActive: Boolean,
    isSelected: Boolean,
    delayMs: Int,
    onBoundsChanged: (Rect) -> Unit,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val avatarColor = remember(profile.avatarColorHex) { parseHexColor(profile.avatarColorHex) }
    val avatarItem = remember(profile.avatarId, avatars) {
        profile.avatarId?.let { id -> avatars.find { it.id == id } }
    }
    val avatarImageUrl = remember(profile.avatarUrl, avatarItem) {
        profileAvatarImageUrl(profile, avatarItem)
    }

    // Per-item entrance animation
    val itemAlpha = remember { Animatable(0f) }
    val itemScale = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        launch { itemAlpha.animateTo(1f, tween(200, easing = FastOutSlowInEasing)) }
        launch {
            itemScale.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        }
    }

    val pressScale by animateFloatAsState(
        targetValue = if (isSelected) 1.08f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "pressScale",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .onGloballyPositioned { coordinates ->
                onBoundsChanged(coordinates.boundsOnScreen())
            }
            .graphicsLayer {
                alpha = itemAlpha.value
                scaleX = itemScale.value * pressScale
                scaleY = itemScale.value * pressScale
            }
            .clip(tokens.shapes.compactCard)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(NuvioTokens.Space.s4),
    ) {
        Box(
            modifier = Modifier.size(52.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(tokens.shapes.avatar)
                    .background(
                        if (avatarImageUrl != null) {
                            avatarItem?.bgColor?.let { parseHexColor(it) } ?: avatarColor
                        } else {
                            avatarColor.copy(alpha = 0.15f)
                        },
                    )
                    .then(
                        when {
                            isSelected -> Modifier.border(
                                tokens.borders.medium + NuvioTokens.Space.hairline,
                                tokens.colors.borderSelected,
                                tokens.shapes.avatar,
                            )
                            isActive -> Modifier.border(
                                tokens.borders.medium,
                                avatarColor.copy(alpha = 0.6f),
                                tokens.shapes.avatar,
                            )
                            avatarImageUrl == null -> Modifier.border(
                                tokens.borders.thin + NuvioTokens.Space.hairline,
                                avatarColor.copy(alpha = 0.3f),
                                tokens.shapes.avatar,
                            )
                            else -> Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarImageUrl != null) {
                    AsyncImage(
                        model = avatarImageUrl,
                        contentDescription = profile.name,
                        modifier = Modifier.size(48.dp).clip(tokens.shapes.avatar),
                        contentScale = ContentScale.Crop,
                    )
                } else if (profile.name.isNotBlank()) {
                    Text(
                        text = profile.name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = NuvioTokens.Type.titleSm),
                        color = avatarColor,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = avatarColor,
                        modifier = Modifier.size(tokens.icons.lg),
                    )
                }
            }

            // Lock badge for PIN-protected profiles
            if (profile.pinEnabled) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(18.dp)
                        .clip(tokens.shapes.avatar)
                        .background(tokens.colors.surfacePopover)
                        .border(tokens.borders.thin + NuvioTokens.Space.hairline, tokens.colors.surfaceSheet, tokens.shapes.avatar),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = tokens.colors.textMuted,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(NuvioTokens.Space.s4))

        Text(
            text = profile.name.ifBlank {
                stringResource(Res.string.profile_label_number, profile.profileIndex)
            },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = NuvioTokens.Type.labelXs),
            color = if (isSelected) tokens.colors.accent else tokens.colors.textMuted,
            fontWeight = if (isActive || isSelected) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(56.dp),
        )
    }
}

internal fun LayoutCoordinates.boundsOnScreen(): Rect {
    val topLeft = localToScreen(Offset.Zero)
    val bottomRight = localToScreen(Offset(size.width.toFloat(), size.height.toFloat()))
    return Rect(
        left = min(topLeft.x, bottomRight.x),
        top = min(topLeft.y, bottomRight.y),
        right = max(topLeft.x, bottomRight.x),
        bottom = max(topLeft.y, bottomRight.y),
    )
}

@Composable
fun ActiveProfileMiniAvatar(
    profile: NuvioProfile?,
    avatars: List<AvatarCatalogItem>,
    selected: Boolean,
    size: Int = 24,
) {
    val tokens = MaterialTheme.nuvio
    if (profile == null) {
        Icon(
            imageVector = Icons.Rounded.Person,
            contentDescription = stringResource(Res.string.compose_nav_profile),
            modifier = Modifier.size(size.dp),
        )
        return
    }

    val avatarColor = remember(profile.avatarColorHex) { parseHexColor(profile.avatarColorHex) }
    val avatarItem = remember(profile.avatarId, avatars) {
        profile.avatarId?.let { id -> avatars.find { it.id == id } }
    }
    val avatarImageUrl = remember(profile.avatarUrl, avatarItem) {
        profileAvatarImageUrl(profile, avatarItem)
    }

    val borderColor = if (selected) {
        tokens.colors.borderSelected
    } else {
        avatarColor.copy(alpha = 0.5f)
    }

    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(tokens.shapes.avatar)
            .background(
                if (avatarImageUrl != null) {
                    avatarItem?.bgColor?.let { parseHexColor(it) } ?: avatarColor
                } else {
                    avatarColor.copy(alpha = 0.15f)
                },
            )
            .border(tokens.borders.thin + NuvioTokens.Space.hairline, borderColor, tokens.shapes.avatar),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarImageUrl != null) {
            AsyncImage(
                model = avatarImageUrl,
                contentDescription = profile.name,
                modifier = Modifier.size(size.dp).clip(tokens.shapes.avatar),
                contentScale = ContentScale.Crop,
            )
        } else if (profile.name.isNotBlank()) {
            Text(
                text = profile.name.take(1).uppercase(),
                fontSize = (size * 0.45f).sp,
                color = avatarColor,
                fontWeight = FontWeight.Bold,
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = null,
                tint = avatarColor,
                modifier = Modifier.size((size * 0.6f).dp),
            )
        }
    }
}
