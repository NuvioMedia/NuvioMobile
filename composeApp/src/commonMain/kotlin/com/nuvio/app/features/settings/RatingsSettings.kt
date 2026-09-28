package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

@OptIn(ExperimentalLayoutApi::class)
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (isTablet) 20.dp else 16.dp,
                vertical = if (isTablet) 18.dp else 16.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.layout_episode_ratings),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(Res.string.layout_episode_ratings_sub),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            EpisodeRatingsVisibility.entries.forEach { visibility ->
                FilterChip(
                    selected = uiState.episodeRatingsVisibility == visibility,
                    onClick = { MetaScreenSettingsRepository.setEpisodeRatingsVisibility(visibility) },
                    label = {
                        Text(
                            text = stringResource(
                                when (visibility) {
                                    EpisodeRatingsVisibility.SHOW_ALL -> Res.string.layout_ratings_show
                                    EpisodeRatingsVisibility.HIDE_EPISODES -> Res.string.layout_ratings_hide
                                    EpisodeRatingsVisibility.HIDE_UNWATCHED_EPISODES -> Res.string.layout_ratings_hide_unwatched
                                },
                            ),
                        )
                    },
                )
            }
        }
    }
}
