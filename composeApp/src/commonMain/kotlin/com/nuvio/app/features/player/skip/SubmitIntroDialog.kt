package com.nuvio.app.features.player.skip

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.StopCircle
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.submit_intro_button_submit
import nuvio.composeapp.generated.resources.submit_intro_capture_button
import nuvio.composeapp.generated.resources.submit_intro_end_time_label
import nuvio.composeapp.generated.resources.submit_intro_segment_intro
import nuvio.composeapp.generated.resources.submit_intro_segment_outro
import nuvio.composeapp.generated.resources.submit_intro_segment_recap
import nuvio.composeapp.generated.resources.submit_intro_segment_type_label
import nuvio.composeapp.generated.resources.submit_intro_start_time_label
import nuvio.composeapp.generated.resources.submit_intro_title
import org.jetbrains.compose.resources.stringResource
import kotlin.math.floor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubmitIntroDialog(
    imdbId: String,
    season: Int,
    episode: Int,
    currentTimeSec: Double,
    segmentType: String,
    onSegmentTypeChange: (String) -> Unit,
    startTimeStr: String,
    onStartTimeChange: (String) -> Unit,
    endTimeStr: String,
    onEndTimeChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var isSubmitting by remember { mutableStateOf(false) }

    DialogSurface(
        onDismissRequest = onDismiss,
        modifier = Modifier.heightIn(max = 560.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.submit_intro_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(Res.string.action_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Segment Type
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(Res.string.submit_intro_segment_type_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SegmentTypeButton(
                        label = stringResource(Res.string.submit_intro_segment_intro),
                        icon = Icons.Rounded.PlayCircleOutline,
                        selected = segmentType == "intro",
                        onClick = { onSegmentTypeChange("intro") },
                        modifier = Modifier.weight(1f)
                    )
                    SegmentTypeButton(
                        label = stringResource(Res.string.submit_intro_segment_recap),
                        icon = Icons.Rounded.Replay,
                        selected = segmentType == "recap",
                        onClick = { onSegmentTypeChange("recap") },
                        modifier = Modifier.weight(1f)
                    )
                    SegmentTypeButton(
                        label = stringResource(Res.string.submit_intro_segment_outro),
                        icon = Icons.Rounded.StopCircle,
                        selected = segmentType == "outro",
                        onClick = { onSegmentTypeChange("outro") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Start Time
            TimeInputRow(
                label = stringResource(Res.string.submit_intro_start_time_label),
                value = startTimeStr,
                onValueChange = onStartTimeChange,
                onCapture = { onStartTimeChange(formatSecondsToMMSS(currentTimeSec)) }
            )

            // End Time
            TimeInputRow(
                label = stringResource(Res.string.submit_intro_end_time_label),
                value = endTimeStr,
                onValueChange = onEndTimeChange,
                onCapture = { onEndTimeChange(formatSecondsToMMSS(currentTimeSec)) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = !isSubmitting, onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(Res.string.action_cancel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(2f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(enabled = !isSubmitting) {
                            val start = parseTimeToSeconds(startTimeStr)
                            val end = parseTimeToSeconds(endTimeStr)
                            if (start != null && end != null && end > start) {
                                isSubmitting = true
                                scope.launch {
                                    val result = SkipIntroRepository.submitIntro(
                                        imdbId = imdbId,
                                        season = season,
                                        episode = episode,
                                        startSec = start,
                                        endSec = end,
                                        segmentType = segmentType,
                                    )
                                    isSubmitting = false
                                    if (result) {
                                        onSuccess()
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSubmitting) {
                        NuvioLoadingIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp),
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.Send, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                            Text(
                                text = stringResource(Res.string.submit_intro_button_submit),
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentTypeButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun TimeInputRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onCapture: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                )
            }
        }
        Box(
            modifier = Modifier
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onCapture)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Rounded.GpsFixed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(Res.string.submit_intro_capture_button),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private fun formatSecondsToMMSS(seconds: Double): String {
    val mins = floor(seconds / 60).toInt()
    val secs = floor(seconds % 60).toInt()
    return "${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
}

private fun parseTimeToSeconds(input: String): Double? {
    if (input.isBlank()) return null

    // Check for separator (colon or dot)
    val separator = when {
        input.contains(':') -> ":"
        input.contains('.') -> "."
        else -> null
    }

    if (separator != null) {
        val parts = input.split(separator)
        if (parts.size == 2) {
            val mins = parts[0].toIntOrNull() ?: return null
            val secs = parts[1].toIntOrNull() ?: return null
            // If the user uses a dot, we assume they mean MM.SS (e.g. 1.24 = 1m 24s)
            // But we only treat it as minutes if seconds are 0-59.
            if (secs in 0..59) {
                return (mins * 60 + secs).toDouble()
            }
        }
    }

    return input.toDoubleOrNull()
}
