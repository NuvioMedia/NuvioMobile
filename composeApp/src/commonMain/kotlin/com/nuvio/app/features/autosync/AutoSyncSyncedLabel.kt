package com.nuvio.app.features.autosync

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.autosync_label_synced
import nuvio.composeapp.generated.resources.autosync_status_failed
import nuvio.composeapp.generated.resources.autosync_status_in_progress
import nuvio.composeapp.generated.resources.autosync_status_no_fit
import nuvio.composeapp.generated.resources.autosync_status_no_reference
import nuvio.composeapp.generated.resources.autosync_status_not_needed
import nuvio.composeapp.generated.resources.autosync_status_synced
import nuvio.composeapp.generated.resources.autosync_status_title
import org.jetbrains.compose.resources.stringResource

internal enum class AutoSyncState { IN_PROGRESS, SYNCED, NOT_NEEDED, NO_FIT, NO_REFERENCE, FAILED }

internal fun AutoSyncAnalysisOutcome?.toFailureState(): AutoSyncState =
    when (this) {
        AutoSyncAnalysisOutcome.NO_FIT -> AutoSyncState.NO_FIT
        AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS,
        AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE,
        -> AutoSyncState.NO_REFERENCE
        AutoSyncAnalysisOutcome.SUBTITLE_UNAVAILABLE,
        AutoSyncAnalysisOutcome.INCONCLUSIVE,
        null,
        -> AutoSyncState.FAILED
    }

/**
 * Where AutoSync, or the audio sync after it, stands with the add-on subtitle it last handled.
 * Cleared whenever the sidecar starts or stops a subtitle, since that renders the original timing again.
 */
internal object AutoSyncSyncedSubtitle {
    class Status(val url: String, val state: AutoSyncState)

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    fun start(subtitleUrl: String): Status =
        Status(subtitleUrl, AutoSyncState.IN_PROGRESS).also { _status.value = it }

    fun mark(subtitleUrl: String, state: AutoSyncState = AutoSyncState.SYNCED) {
        _status.value = Status(subtitleUrl, state)
    }

    fun fail(run: Status?) {
        if (run != null) _status.compareAndSet(run, Status(run.url, AutoSyncState.FAILED))
    }

    fun end(run: Status?) {
        if (run != null) _status.compareAndSet(run, null)
    }

    fun clear() {
        _status.value = null
    }
}

/** "Auto synced" chip for the selected add-on subtitle in the subtitle panel; empty otherwise. */
@Composable
internal fun AutoSyncedChip(subtitleUrl: String, selected: Boolean) {
    val status by AutoSyncSyncedSubtitle.status.collectAsState()
    val synced = status?.takeIf { it.url == subtitleUrl }?.state == AutoSyncState.SYNCED
    if (!selected || !synced) return

    val tokens = MaterialTheme.nuvio
    val contentColor = tokens.colors.onAccent.copy(alpha = 0.9f)
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(tokens.colors.onAccent.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Sync,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = stringResource(Res.string.autosync_label_synced),
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private val AutoSyncNoFitViolet = Color(0xFFAB47BC)

@Composable
internal fun AutoSyncStatusIndicator(subtitleUrl: String?, modifier: Modifier = Modifier) {
    val status by AutoSyncSyncedSubtitle.status.collectAsState()
    val state = status?.takeIf { subtitleUrl != null && it.url == subtitleUrl }?.state ?: return

    val colors = MaterialTheme.nuvio.colors
    val (icon, color, label) = when (state) {
        AutoSyncState.SYNCED -> Triple(Icons.Rounded.Check, colors.success, Res.string.autosync_status_synced)
        AutoSyncState.NOT_NEEDED -> Triple(
            Icons.AutoMirrored.Rounded.ArrowForward,
            colors.textSecondary,
            Res.string.autosync_status_not_needed,
        )
        AutoSyncState.IN_PROGRESS -> Triple(Icons.Rounded.Sync, colors.warning, Res.string.autosync_status_in_progress)
        AutoSyncState.NO_FIT -> Triple(
            Icons.Rounded.PriorityHigh,
            AutoSyncNoFitViolet,
            Res.string.autosync_status_no_fit,
        )
        AutoSyncState.NO_REFERENCE -> Triple(
            Icons.Rounded.Remove,
            colors.textSecondary,
            Res.string.autosync_status_no_reference,
        )
        AutoSyncState.FAILED -> Triple(Icons.Rounded.Close, colors.danger, Res.string.autosync_status_failed)
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(color.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (state == AutoSyncState.IN_PROGRESS) {
                SpinningStatusIcon(icon, color)
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
        Column {
            Text(
                text = stringResource(Res.string.autosync_status_title),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
                color = colors.textMuted,
            )
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = color,
            )
        }
    }
}

@Composable
private fun SpinningStatusIcon(icon: ImageVector, tint: Color) {
    val angle by rememberInfiniteTransition(label = "autosync_status").animateFloat(
        initialValue = 0f,
        targetValue = -360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1_200, easing = LinearEasing)),
        label = "autosync_status_angle",
    )
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .size(10.dp)
            .graphicsLayer { rotationZ = angle },
    )
}
