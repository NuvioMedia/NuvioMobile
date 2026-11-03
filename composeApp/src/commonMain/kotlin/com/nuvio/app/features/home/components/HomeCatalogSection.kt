package com.nuvio.app.features.home.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioViewAllPillSize
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.stableKey
import com.nuvio.app.features.home.cleanMergedSourceTabTitle
import com.nuvio.app.features.watching.application.WatchingState

@Composable
fun HomeCatalogRowSection(
    section: HomeCatalogSection,
    modifier: Modifier = Modifier,
    entries: List<MetaPreview> = section.items,
    watchedKeys: Set<String> = emptySet(),
    fullyWatchedSeriesKeys: Set<String> = emptySet(),
    sectionPadding: Dp? = null,
    onViewAllClick: (() -> Unit)? = null,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
) {
    val catalogSettings by HomeCatalogSettingsRepository.uiState.collectAsStateWithLifecycle()
    val hideMergedTitlesWithoutPosters = catalogSettings.hideMergedTitlesWithoutPosters
    if (sectionPadding != null) {
        HomeCatalogRowSectionContent(
            section = section,
            entries = entries,
            hideMergedTitlesWithoutPosters = hideMergedTitlesWithoutPosters,
            watchedKeys = watchedKeys,
            fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
            modifier = modifier.fillMaxWidth(),
            sectionPadding = sectionPadding,
            onViewAllClick = onViewAllClick,
            onPosterClick = onPosterClick,
            onPosterLongClick = onPosterLongClick,
        )
    } else {
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            HomeCatalogRowSectionContent(
                section = section,
                entries = entries,
                hideMergedTitlesWithoutPosters = hideMergedTitlesWithoutPosters,
                watchedKeys = watchedKeys,
                fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                modifier = Modifier.fillMaxWidth(),
                sectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value),
                onViewAllClick = onViewAllClick,
                onPosterClick = onPosterClick,
                onPosterLongClick = onPosterLongClick,
            )
        }
    }
}

private const val HOME_MERGED_ROW_TAB_PREVIEW_LIMIT = 18

@Composable
private fun HomeCatalogRowSectionContent(
    section: HomeCatalogSection,
    entries: List<MetaPreview>,
    hideMergedTitlesWithoutPosters: Boolean,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    modifier: Modifier,
    sectionPadding: Dp,
    onViewAllClick: (() -> Unit)?,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
) {
    val posterCardStyle = rememberPosterCardStyleUiState()
    val sourceTabs = if (section.isMergedRow && section.sourceTabs.isNotEmpty()) {
        section.sourceTabs
    } else {
        emptyList()
    }
    var selectedTabKey by rememberSaveable(section.key) { mutableStateOf("all") }
    LaunchedEffect(section.key, sourceTabs.map { it.key }) {
        if (sourceTabs.none { it.key == selectedTabKey }) {
            selectedTabKey = sourceTabs.firstOrNull()?.key.orEmpty()
        }
    }
    val selectedTab = sourceTabs.firstOrNull { it.key == selectedTabKey }
        ?: sourceTabs.firstOrNull()
    val selectedItems = selectedTab?.items ?: entries
    val posterEligibleItems = if (hideMergedTitlesWithoutPosters) {
        selectedItems.filterNot { it.poster.isNullOrBlank() }
    } else {
        selectedItems
    }
    val visibleEntries = if (selectedTab != null) {
        posterEligibleItems.take(HOME_MERGED_ROW_TAB_PREVIEW_LIMIT)
    } else {
        posterEligibleItems
    }
    val sourceTabsHeader: (@Composable () -> Unit)? = if (sourceTabs.isNotEmpty()) {
        {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = sectionPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sourceTabs.forEach { tab ->
                    FilterChip(
                        selected = tab.key == selectedTab?.key,
                        onClick = { selectedTabKey = tab.key },
                        label = { Text(cleanMergedSourceTabTitle(tab.title)) },
                    )
                }
            }
        }
    } else {
        null
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NuvioShelfSection(
            title = section.title,
            entries = visibleEntries,
            modifier = Modifier.fillMaxWidth(),
            headerHorizontalPadding = sectionPadding,
            rowContentPadding = PaddingValues(horizontal = sectionPadding),
            onViewAllClick = onViewAllClick,
            headerContent = sourceTabsHeader,
            viewAllPillSize = NuvioViewAllPillSize.Compact,
            key = { item -> item.stableKey() },
        ) { item ->
            HomePosterCard(
                item = item,
                hideWithoutPoster = hideMergedTitlesWithoutPosters,
                useLandscapeBackdropMode = posterCardStyle.catalogLandscapeModeEnabled,
                hideLabels = posterCardStyle.hideLabelsEnabled,
                alwaysShowLandscapeClearlogo = posterCardStyle.alwaysShowLandscapeClearlogo,
                isWatched = WatchingState.isPosterWatched(
                    watchedKeys = watchedKeys,
                    item = item,
                    fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                ),
                onClick = onPosterClick?.let { { it(item) } },
                onLongClick = onPosterLongClick?.let { { it(item) } },
            )
        }
    }
}
