package com.nuvio.app.features.streams

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.collections_tab_all
import nuvio.composeapp.generated.resources.streams_refresh
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ProviderFilterRow(
    groups: List<AddonStreamGroup>,
    selectedFilter: String?,
    onFilterSelected: (String?) -> Unit,
    onRefresh: (() -> Unit)? = null,
    isRefreshing: Boolean = groups.any { it.isLoading },
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    spacing: Dp = 8.dp,
    refreshChip: @Composable (Boolean, () -> Unit) -> Unit = { isLoading, onClick ->
        RefreshChip(isLoading = isLoading, onClick = onClick)
    },
    filterChip: @Composable (AddonStreamGroup?, Boolean, () -> Unit) -> Unit = { group, isSelected, onClick ->
        FilterChip(
            label = group?.addonName ?: stringResource(Res.string.collections_tab_all),
            isSelected = isSelected,
            onClick = onClick,
        )
    },
) {
    val addonGroups = groups.filter { it.streams.isNotEmpty() || it.isLoading }
    LaunchedEffect(addonGroups, selectedFilter) {
        if (selectedFilter != null && addonGroups.none { it.addonId == selectedFilter }) {
            onFilterSelected(null)
        }
    }
    if (addonGroups.isEmpty() && onRefresh == null) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        if (onRefresh != null) {
            refreshChip(isRefreshing, onRefresh)
        }
        filterChip(null, selectedFilter == null) { onFilterSelected(null) }
        addonGroups.forEach { group ->
            filterChip(group, selectedFilter == group.addonId) { onFilterSelected(group.addonId) }
        }
    }
}

@Composable
internal fun rememberRefreshRotation(isLoading: Boolean): Animatable<Float, AnimationVector1D> {
    val rotation = remember { Animatable(0f) }
    var hasCompletedFullRotation by remember { mutableStateOf(false) }
    LaunchedEffect(isLoading) {
        if (isLoading) {
            delay(100)
            hasCompletedFullRotation = false
            rotation.animateTo(360f, remainingTurn(rotation.value))
            hasCompletedFullRotation = true
            rotation.snapTo(0f)
            rotation.animateTo(360f, infiniteRepeatable(tween(durationMillis = 1000, easing = LinearEasing)))
        } else {
            if (hasCompletedFullRotation && rotation.value > 0f) {
                rotation.animateTo(360f, remainingTurn(rotation.value))
            }
            rotation.snapTo(0f)
            hasCompletedFullRotation = false
        }
    }
    return rotation
}

private fun remainingTurn(rotation: Float) =
    tween<Float>(durationMillis = ((360f - rotation) / 360f * 1000).toInt(), easing = LinearEasing)

@Composable
private fun RefreshChip(
    isLoading: Boolean,
    onClick: () -> Unit,
) {
    val rotation = rememberRefreshRotation(isLoading)
    FilterChip(
        icon = Icons.Rounded.Refresh,
        contentDescription = stringResource(Res.string.streams_refresh),
        iconModifier = Modifier.graphicsLayer { rotationZ = rotation.value },
        isSelected = false,
        onClick = onClick,
    )
}

@Composable
private fun FilterChip(
    label: String? = null,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    iconModifier: Modifier = Modifier,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "filter_chip_scale",
    )
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        animationSpec = tween(durationMillis = 180),
        label = "filter_chip_container",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(durationMillis = 180),
        label = "filter_chip_content",
    )
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(36.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = contentColor,
                    modifier = Modifier.size(20.dp).then(iconModifier),
                )
            }
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        letterSpacing = 0.1.sp,
                    ),
                    color = contentColor,
                    maxLines = 1,
                )
            }
        }
    }
}
