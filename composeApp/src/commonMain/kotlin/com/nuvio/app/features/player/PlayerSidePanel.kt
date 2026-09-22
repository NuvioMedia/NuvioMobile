package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.streams.ProviderFilterRow
import com.nuvio.app.features.streams.StreamsUiState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.collections_tab_all
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlayerSidePanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    width: Dp = 520.dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val backgroundInteraction = remember { MutableInteractionSource() }
    val panelInteraction = remember { MutableInteractionSource() }

    PlatformBackHandler(enabled = visible, onBack = onDismiss)

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(160)),
        modifier = modifier,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.34f))
                .clickable(
                    interactionSource = backgroundInteraction,
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            val resolvedWidth = minOf(maxWidth, width)
            val shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)

            AnimatedVisibility(
                visible = visible,
                enter = slideInHorizontally(tween(250)) { it },
                exit = slideOutHorizontally(tween(200)) { it },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Column(
                    modifier = Modifier
                        .width(resolvedWidth)
                        .fillMaxHeight()
                        .clip(shape)
                        .background(tokens.colors.surfaceElevated)
                        .clickable(
                            interactionSource = panelInteraction,
                            indication = null,
                            onClick = {},
                        ),
                    content = content,
                )
            }
        }
    }
}

@Composable
internal fun PlayerPanelHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            color = tokens.colors.textPrimary,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = actions,
        )
    }
}

@Composable
internal fun PlayerDialogButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tokens = MaterialTheme.nuvio

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else tokens.opacity.disabled)
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.surfaceCard)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = tokens.colors.textSecondary,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun PlayerModalLoading(
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        NuvioLoadingIndicator(
            color = tokens.colors.accent,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
internal fun PlayerProviderFilterRow(
    streamsUiState: StreamsUiState,
    onFilterSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    ProviderFilterRow(
        groups = streamsUiState.groups,
        selectedFilter = streamsUiState.selectedFilter,
        onFilterSelected = onFilterSelected,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        spacing = 16.dp,
    ) { group, isSelected, onClick ->
        AddonFilterChip(
            label = group?.addonName ?: stringResource(Res.string.collections_tab_all),
            isSelected = isSelected,
            isLoading = group?.isLoading == true,
            hasError = group?.error != null,
            onClick = onClick,
        )
    }
}

@Composable
private fun AddonFilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    hasError: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    val containerColor = when {
        hasError -> tokens.colors.danger.copy(alpha = 0.06f)
        isSelected -> tokens.colors.accent
        else -> tokens.colors.surfaceCard
    }
    val contentColor = when {
        hasError -> tokens.colors.danger
        isSelected -> tokens.colors.onAccent
        else -> tokens.colors.textSecondary
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(containerColor)
            .border(
                1.dp,
                if (hasError) tokens.colors.danger.copy(alpha = 0.7f) else tokens.colors.borderDefault,
                RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isLoading) {
                NuvioLoadingIndicator(
                    color = contentColor,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(
                text = label,
                color = contentColor,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}
