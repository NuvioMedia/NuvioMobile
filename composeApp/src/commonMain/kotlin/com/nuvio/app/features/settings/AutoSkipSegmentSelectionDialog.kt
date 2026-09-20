package com.nuvio.app.features.settings

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.skip.AutoSkipSegmentType
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun AutoSkipSegmentSelectionDialog(
    selectedTypes: Set<AutoSkipSegmentType>,
    onTypeToggled: (AutoSkipSegmentType, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_playback_auto_skip_segments),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(AutoSkipSegmentType.entries, key = { it.storedValue }) { segmentType ->
                        val isSelected = segmentType in selectedTypes
                        val containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = isSelected,
                                    role = Role.Checkbox,
                                    onValueChange = { onTypeToggled(segmentType, it) },
                                ),
                            shape = RoundedCornerShape(12.dp),
                            color = containerColor,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        text = autoSkipTypeLabel(segmentType),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = autoSkipTypeDescription(segmentType),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Box(
                                    modifier = Modifier.size(24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(Res.string.settings_playback_dialog_close),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun autoSkipSelectionSummary(selectedTypes: Set<AutoSkipSegmentType>): String {
    if (selectedTypes.isEmpty()) return stringResource(Res.string.settings_playback_auto_skip_none)
    val introLabel = stringResource(Res.string.settings_playback_auto_skip_intro)
    val recapLabel = stringResource(Res.string.settings_playback_auto_skip_recap)
    val outroLabel = stringResource(Res.string.settings_playback_auto_skip_outro)
    val movieCreditsLabel = stringResource(Res.string.settings_playback_auto_skip_movie_credits)
    return buildList {
        if (AutoSkipSegmentType.INTRO in selectedTypes) add(introLabel)
        if (AutoSkipSegmentType.RECAP in selectedTypes) add(recapLabel)
        if (AutoSkipSegmentType.OUTRO in selectedTypes) add(outroLabel)
        if (AutoSkipSegmentType.MOVIE_CREDITS in selectedTypes) add(movieCreditsLabel)
    }.joinToString(", ")
}

@Composable
private fun autoSkipTypeLabel(segmentType: AutoSkipSegmentType): String = when (segmentType) {
    AutoSkipSegmentType.INTRO -> stringResource(Res.string.settings_playback_auto_skip_intro)
    AutoSkipSegmentType.RECAP -> stringResource(Res.string.settings_playback_auto_skip_recap)
    AutoSkipSegmentType.OUTRO -> stringResource(Res.string.settings_playback_auto_skip_outro)
    AutoSkipSegmentType.MOVIE_CREDITS -> stringResource(Res.string.settings_playback_auto_skip_movie_credits)
}

@Composable
private fun autoSkipTypeDescription(segmentType: AutoSkipSegmentType): String = when (segmentType) {
    AutoSkipSegmentType.INTRO -> stringResource(Res.string.settings_playback_auto_skip_intro_description)
    AutoSkipSegmentType.RECAP -> stringResource(Res.string.settings_playback_auto_skip_recap_description)
    AutoSkipSegmentType.OUTRO -> stringResource(Res.string.settings_playback_auto_skip_outro_description)
    AutoSkipSegmentType.MOVIE_CREDITS -> stringResource(Res.string.settings_playback_auto_skip_movie_credits_description)
}
