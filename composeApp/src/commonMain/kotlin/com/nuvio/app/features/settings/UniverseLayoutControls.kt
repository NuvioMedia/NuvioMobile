package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.details.UniverseSortOrder
import com.nuvio.app.features.details.UniversePosterStyle
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun UniverseLayoutControls(isTablet: Boolean) {
    val settings by MetaScreenSettingsRepository.uiState.collectAsStateWithLifecycle()
    Column(Modifier.padding(bottom = 12.dp)) {
        SettingsChipRow(
            title = stringResource(Res.string.universe_sort_title),
            description = stringResource(Res.string.universe_sort_description),
            isTablet = isTablet,
        ) {
            UniverseSortOrder.entries.forEach { order ->
                val label = when (order) {
                    UniverseSortOrder.AiredOldest -> Res.string.universe_sort_oldest
                    UniverseSortOrder.AiredNewest -> Res.string.universe_sort_newest
                    UniverseSortOrder.MostPopular -> Res.string.universe_sort_popular
                }
                Chip(label = stringResource(label), selected = settings.universeSortOrder == order,
                    onClick = { MetaScreenSettingsRepository.setUniverseSortOrder(order) })
            }
        }
        SettingsChipRow(
            title = stringResource(Res.string.universe_style_title),
            description = stringResource(Res.string.universe_style_description),
            isTablet = isTablet,
        ) {
            UniversePosterStyle.entries.forEach { style ->
                Chip(label = stringResource(if (style == UniversePosterStyle.Landscape) Res.string.universe_style_landscape else Res.string.universe_style_posters),
                    selected = settings.universePosterStyle == style,
                    onClick = { MetaScreenSettingsRepository.setUniversePosterStyle(style) })
            }
        }
    }
}
