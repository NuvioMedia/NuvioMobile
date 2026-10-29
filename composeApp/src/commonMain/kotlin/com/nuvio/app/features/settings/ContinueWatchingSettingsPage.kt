package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.features.home.components.ContinueWatchingStylePreview
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import com.nuvio.app.features.watchprogress.ContinueWatchingSortMode
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_continue_watching_resume_prompt_description
import nuvio.composeapp.generated.resources.settings_continue_watching_resume_prompt_title
import nuvio.composeapp.generated.resources.settings_continue_watching_blur_next_up_description
import nuvio.composeapp.generated.resources.settings_continue_watching_blur_next_up_title
import nuvio.composeapp.generated.resources.settings_continue_watching_show_unaired_next_up_description
import nuvio.composeapp.generated.resources.settings_continue_watching_show_unaired_next_up_title
import nuvio.composeapp.generated.resources.settings_continue_watching_section_card_style
import nuvio.composeapp.generated.resources.settings_continue_watching_section_on_launch
import nuvio.composeapp.generated.resources.settings_continue_watching_section_sort_order
import nuvio.composeapp.generated.resources.settings_continue_watching_section_up_next_behavior
import nuvio.composeapp.generated.resources.settings_continue_watching_section_visibility
import nuvio.composeapp.generated.resources.settings_continue_watching_show_description
import nuvio.composeapp.generated.resources.settings_continue_watching_show_title
import nuvio.composeapp.generated.resources.settings_continue_watching_sort_mode_default
import nuvio.composeapp.generated.resources.settings_continue_watching_sort_mode_default_desc
import nuvio.composeapp.generated.resources.settings_continue_watching_sort_mode_streaming
import nuvio.composeapp.generated.resources.settings_continue_watching_sort_mode_streaming_desc
import nuvio.composeapp.generated.resources.settings_continue_watching_sort_mode_split_upcoming
import nuvio.composeapp.generated.resources.settings_continue_watching_sort_mode_split_upcoming_desc
import nuvio.composeapp.generated.resources.settings_continue_watching_sort_mode_title
import nuvio.composeapp.generated.resources.settings_continue_watching_style_card
import nuvio.composeapp.generated.resources.settings_continue_watching_style_card_description
import nuvio.composeapp.generated.resources.settings_continue_watching_style_poster
import nuvio.composeapp.generated.resources.settings_continue_watching_style_poster_description
import nuvio.composeapp.generated.resources.settings_continue_watching_style_wide
import nuvio.composeapp.generated.resources.settings_continue_watching_style_wide_description
import nuvio.composeapp.generated.resources.settings_continue_watching_up_next_description
import nuvio.composeapp.generated.resources.settings_continue_watching_up_next_title
import nuvio.composeapp.generated.resources.settings_continue_watching_use_episode_thumbnails_description
import nuvio.composeapp.generated.resources.settings_continue_watching_use_episode_thumbnails_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.continueWatchingSettingsContent(
    isTablet: Boolean,
    isVisible: Boolean,
    style: ContinueWatchingSectionStyle,
    upNextFromFurthestEpisode: Boolean,
    useEpisodeThumbnails: Boolean,
    showUnairedNextUp: Boolean,
    blurNextUp: Boolean,
    showResumePromptOnLaunch: Boolean,
    sortMode: ContinueWatchingSortMode,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_continue_watching_section_visibility),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_continue_watching_show_title),
                    description = stringResource(Res.string.settings_continue_watching_show_description),
                    checked = isVisible,
                    isTablet = isTablet,
                    onCheckedChange = ContinueWatchingPreferencesRepository::setVisible,
                )
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_continue_watching_section_card_style),
            isTablet = isTablet,
        ) {
            ContinueWatchingStyleSelector(
                isTablet = isTablet,
                selectedStyle = style,
                onStyleSelected = ContinueWatchingPreferencesRepository::setStyle,
            )
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_continue_watching_section_up_next_behavior),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                if (style != ContinueWatchingSectionStyle.Poster) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_continue_watching_use_episode_thumbnails_title),
                        description = stringResource(Res.string.settings_continue_watching_use_episode_thumbnails_description),
                        checked = useEpisodeThumbnails,
                        isTablet = isTablet,
                        onCheckedChange = ContinueWatchingPreferencesRepository::setUseEpisodeThumbnails,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                }
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_continue_watching_up_next_title),
                    description = stringResource(Res.string.settings_continue_watching_up_next_description),
                    checked = upNextFromFurthestEpisode,
                    isTablet = isTablet,
                    onCheckedChange = ContinueWatchingPreferencesRepository::setUpNextFromFurthestEpisode,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_continue_watching_show_unaired_next_up_title),
                    description = stringResource(Res.string.settings_continue_watching_show_unaired_next_up_description),
                    checked = showUnairedNextUp,
                    isTablet = isTablet,
                    onCheckedChange = ContinueWatchingPreferencesRepository::setShowUnairedNextUp,
                )
                if (style != ContinueWatchingSectionStyle.Poster && useEpisodeThumbnails) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_continue_watching_blur_next_up_title),
                        description = stringResource(Res.string.settings_continue_watching_blur_next_up_description),
                        checked = blurNextUp,
                        isTablet = isTablet,
                        onCheckedChange = ContinueWatchingPreferencesRepository::setBlurNextUp,
                    )
                }
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_continue_watching_section_on_launch),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_continue_watching_resume_prompt_title),
                    description = stringResource(Res.string.settings_continue_watching_resume_prompt_description),
                    checked = showResumePromptOnLaunch,
                    isTablet = isTablet,
                    onCheckedChange = ContinueWatchingPreferencesRepository::setShowResumePromptOnLaunch,
                )
            }
        }
    }
    item {
        var showSortModeSheet by remember { mutableStateOf(false) }
        SettingsSection(
            title = stringResource(Res.string.settings_continue_watching_section_sort_order),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                val currentModeLabel = stringResource(
                    when (sortMode) {
                        ContinueWatchingSortMode.DEFAULT -> Res.string.settings_continue_watching_sort_mode_default
                        ContinueWatchingSortMode.STREAMING_STYLE -> Res.string.settings_continue_watching_sort_mode_streaming
                        ContinueWatchingSortMode.SPLIT_UPCOMING -> Res.string.settings_continue_watching_sort_mode_split_upcoming
                    }
                )
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_continue_watching_sort_mode_title),
                    description = currentModeLabel,
                    isTablet = isTablet,
                    onClick = { showSortModeSheet = true },
                )
            }
        }

        if (showSortModeSheet) {
            ContinueWatchingSortModeDialog(
                currentMode = sortMode,
                onModeSelected = { mode ->
                    ContinueWatchingPreferencesRepository.setSortMode(mode)
                    showSortModeSheet = false
                },
                onDismiss = { showSortModeSheet = false },
            )
        }
    }
}

@Composable
private fun ContinueWatchingStyleSelector(
    isTablet: Boolean,
    selectedStyle: ContinueWatchingSectionStyle,
    onStyleSelected: (ContinueWatchingSectionStyle) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (isTablet) 12.dp else 8.dp),
    ) {
        ContinueWatchingSectionStyle.entries.forEach { style ->
            Box(modifier = Modifier.weight(1f)) {
                ContinueWatchingStyleOption(
                    style = style,
                    selected = selectedStyle == style,
                    isTablet = isTablet,
                    onClick = { onStyleSelected(style) },
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingStyleOption(
    style: ContinueWatchingSectionStyle,
    selected: Boolean,
    isTablet: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isTablet) 12.dp else 8.dp, vertical = if (isTablet) 14.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (isTablet) 8.dp else 6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (isTablet) 4.dp else 0.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(if (isTablet) 24.dp else 18.dp)
                        .alpha(if (selected) 1f else 0f),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isTablet) 96.dp else 66.dp),
                contentAlignment = Alignment.Center,
            ) {
                ContinueWatchingStylePreview(
                    style = style,
                    isSelected = selected,
                )
            }
            Text(
                text = stringResource(style.labelRes),
                style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(style.descriptionRes),
                style = if (isTablet) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val ContinueWatchingSectionStyle.labelRes: StringResource
    get() = when (this) {
        ContinueWatchingSectionStyle.Card -> Res.string.settings_continue_watching_style_card
        ContinueWatchingSectionStyle.Wide -> Res.string.settings_continue_watching_style_wide
        ContinueWatchingSectionStyle.Poster -> Res.string.settings_continue_watching_style_poster
    }

private val ContinueWatchingSectionStyle.descriptionRes: StringResource
    get() = when (this) {
        ContinueWatchingSectionStyle.Card -> Res.string.settings_continue_watching_style_card_description
        ContinueWatchingSectionStyle.Wide -> Res.string.settings_continue_watching_style_wide_description
        ContinueWatchingSectionStyle.Poster -> Res.string.settings_continue_watching_style_poster_description
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContinueWatchingSortModeDialog(
    currentMode: ContinueWatchingSortMode,
    onModeSelected: (ContinueWatchingSortMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        Triple(
            ContinueWatchingSortMode.DEFAULT,
            Res.string.settings_continue_watching_sort_mode_default,
            Res.string.settings_continue_watching_sort_mode_default_desc,
        ),
        Triple(
            ContinueWatchingSortMode.STREAMING_STYLE,
            Res.string.settings_continue_watching_sort_mode_streaming,
            Res.string.settings_continue_watching_sort_mode_streaming_desc,
        ),
        Triple(
            ContinueWatchingSortMode.SPLIT_UPCOMING,
            Res.string.settings_continue_watching_sort_mode_split_upcoming,
            Res.string.settings_continue_watching_sort_mode_split_upcoming_desc,
        ),
    )

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_continue_watching_sort_mode_title),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (mode, titleRes, descriptionRes) ->
                val isSelected = mode == currentMode
                DialogOption(
                    text = stringResource(titleRes),
                    description = stringResource(descriptionRes),
                    selected = isSelected,
                    onClick = { onModeSelected(mode) },
                )
            }
        }
    }
}
