package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.features.player.skip.AutoSkipSegmentType
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AutoSkipSegmentSelectionDialog(
    selectedTypes: Set<AutoSkipSegmentType>,
    onTypeToggled: (AutoSkipSegmentType, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    DialogSurface(
        onDismissRequest = onDismiss,
        modifier = Modifier.heightIn(max = 560.dp),
        title = stringResource(Res.string.settings_playback_auto_skip_segments),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(AutoSkipSegmentType.entries, key = { it.storedValue }) { segmentType ->
                val isSelected = segmentType in selectedTypes
                DialogOption(
                    text = autoSkipTypeLabel(segmentType),
                    description = autoSkipTypeDescription(segmentType),
                    selected = isSelected,
                    role = Role.Checkbox,
                    onClick = { onTypeToggled(segmentType, !isSelected) },
                )
            }
        }
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_done),
                onClick = onDismiss,
            )
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
