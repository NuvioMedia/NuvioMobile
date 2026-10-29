package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.cloud.cloudLibraryDisplayArtworkUrl
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.cw_action_go_to_details
import nuvio.composeapp.generated.resources.cw_action_remove
import nuvio.composeapp.generated.resources.cw_action_start_from_beginning
import nuvio.composeapp.generated.resources.play_manually
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NuvioContinueWatchingActionSheet(
    item: ContinueWatchingItem?,
    showManualPlayOption: Boolean,
    showDetailsOption: Boolean = true,
    onDismiss: () -> Unit,
    onOpenDetails: () -> Unit,
    onStartFromBeginning: (() -> Unit)? = null,
    onPlayManually: (() -> Unit)? = null,
    onRemove: () -> Unit,
) {
    if (item == null) return
    val tokens = MaterialTheme.nuvio
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    fun dismissAfter(action: () -> Unit) {
        action()
        coroutineScope.launch {
            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
        }
    }

    NuvioModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch {
                dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
            }
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(tokens.spacing.screenHorizontal)),
        ) {
            ContinueWatchingSheetHeader(item = item)
            NuvioBottomSheetDivider()
            if (showDetailsOption) {
                NuvioBottomSheetActionRow(
                    icon = Icons.Default.Info,
                    title = stringResource(Res.string.cw_action_go_to_details),
                    onClick = { dismissAfter(onOpenDetails) },
                )
            }
            if (showManualPlayOption && onPlayManually != null) {
                NuvioBottomSheetActionRow(
                    icon = Icons.Default.PlayArrow,
                    title = stringResource(Res.string.play_manually),
                    onClick = { dismissAfter(onPlayManually) },
                )
            }
            if (!item.isNextUp && onStartFromBeginning != null) {
                NuvioBottomSheetActionRow(
                    icon = Icons.Default.Replay,
                    title = stringResource(Res.string.cw_action_start_from_beginning),
                    onClick = { dismissAfter(onStartFromBeginning) },
                )
            }
            NuvioBottomSheetActionRow(
                icon = Icons.Default.DeleteOutline,
                title = stringResource(Res.string.cw_action_remove),
                onClick = { dismissAfter(onRemove) },
            )
        }
    }
}

@Composable
private fun ContinueWatchingSheetHeader(
    item: ContinueWatchingItem,
) {
    val posterCardStyle = rememberPosterCardStyleUiState()
    val tokens = MaterialTheme.nuvio

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.screenHorizontal, vertical = NuvioTokens.Space.s14),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s14),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = NuvioTokens.Space.s64, height = NuvioTokens.Space.s80 + NuvioTokens.Space.s12)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(posterCardStyle.cornerRadiusDp.dp))
                .background(tokens.colors.surfaceCard),
            contentAlignment = Alignment.Center,
        ) {
            val artwork = item.poster ?: item.imageUrl
            if (artwork != null) {
                AsyncImage(
                    model = cloudLibraryDisplayArtworkUrl(artwork),
                    contentDescription = item.title,
                    modifier = Modifier.matchParentSize(),
                    contentScale = if (item.isCloudLibraryItem()) ContentScale.Fit else ContentScale.Crop,
                )
            } else {
                Text(
                    text = item.title,
                    modifier = Modifier.padding(tokens.spacing.listGap),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = localizedContinueWatchingSubtitle(item),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun ContinueWatchingItem.isCloudLibraryItem(): Boolean =
    parentMetaType.equals(CloudLibraryContentType, ignoreCase = true)
