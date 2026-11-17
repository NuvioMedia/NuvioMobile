package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.features.details.EpisodeRatingsVisibility
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.details.MetaScreenSettingsUiState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.layout_episode_ratings
import nuvio.composeapp.generated.resources.layout_episode_ratings_sub
import nuvio.composeapp.generated.resources.layout_overall_ratings
import nuvio.composeapp.generated.resources.layout_overall_ratings_sub_off
import nuvio.composeapp.generated.resources.layout_overall_ratings_sub_on
import nuvio.composeapp.generated.resources.layout_ratings_hide
import nuvio.composeapp.generated.resources.layout_ratings_hide_unwatched
import nuvio.composeapp.generated.resources.layout_ratings_show
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RatingsSettings(
    isTablet: Boolean,
    uiState: MetaScreenSettingsUiState,
) {
    SettingsSwitchRow(
        title = stringResource(Res.string.layout_overall_ratings),
        description = stringResource(
            if (uiState.showOverallRatings) Res.string.layout_overall_ratings_sub_on
            else Res.string.layout_overall_ratings_sub_off,
        ),
        checked = uiState.showOverallRatings,
        isTablet = isTablet,
        onCheckedChange = MetaScreenSettingsRepository::setShowOverallRatings,
    )
    SettingsGroupDivider(isTablet = isTablet)
    SettingsChipRow(
        title = stringResource(Res.string.layout_episode_ratings),
        description = stringResource(Res.string.layout_episode_ratings_sub),
        isTablet = isTablet,
    ) {
        EpisodeRatingsVisibility.entries.forEach { visibility ->
            Chip(
                label = stringResource(
                    when (visibility) {
                        EpisodeRatingsVisibility.SHOW_ALL -> Res.string.layout_ratings_show
                        EpisodeRatingsVisibility.HIDE_EPISODES -> Res.string.layout_ratings_hide
                        EpisodeRatingsVisibility.HIDE_UNWATCHED_EPISODES -> Res.string.layout_ratings_hide_unwatched
                    },
                ),
                selected = uiState.episodeRatingsVisibility == visibility,
                onClick = { MetaScreenSettingsRepository.setEpisodeRatingsVisibility(visibility) },
            )
        }
    }
}
