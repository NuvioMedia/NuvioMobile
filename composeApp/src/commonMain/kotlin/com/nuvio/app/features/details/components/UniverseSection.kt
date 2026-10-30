package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.poster.CustomPosterScreen
import com.nuvio.app.core.poster.CustomPosterUrlRepository
import com.nuvio.app.core.poster.withCustomPosterUrls
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.UniverseConnection
import com.nuvio.app.features.details.UniverseRepository
import com.nuvio.app.features.details.UniverseLoadState
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.details.UniversePosterStyle
import com.nuvio.app.features.details.sortUniverse
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.filterReleasedItems
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun UniverseSection(
    meta: MetaDetails,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    showHeader: Boolean,
    horizontalScrollPadding: Dp,
    onPosterClick: ((MetaPreview) -> Unit)?,
) {
    val states by UniverseRepository.states.collectAsStateWithLifecycle()
    val options by MetaScreenSettingsRepository.uiState.collectAsStateWithLifecycle()
    val state = states[UniverseRepository.key(meta)] ?: UniverseLoadState(loading = true)
    val items = state.items
    val loading = state.loading
    LaunchedEffect(meta.id, meta.imdbId) {
        UniverseRepository.prefetch(meta)
    }
    val catalogSettings by remember {
        HomeCatalogSettingsRepository.snapshot()
        HomeCatalogSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val connectionItems = sortUniverse(items.orEmpty(), options.universeSortOrder).map { it.item }
    val visibleItems = if (catalogSettings.hideUnreleasedContent) {
        connectionItems.filterReleasedItems(CurrentDateProvider.todayIsoDate())
    } else connectionItems
    val pattern = CustomPosterUrlRepository.let {
        it.ensureLoaded()
        it.patternForScreen(CustomPosterScreen.DETAILS)
    }
    val cards = visibleItems.withCustomPosterUrls(pattern)
    if (cards.isEmpty()) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            if (showHeader) Text(stringResource(Res.string.universe_title), style = MaterialTheme.typography.titleLarge)
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
            } else if (items == null) {
                Text(stringResource(Res.string.universe_unavailable), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { UniverseRepository.prefetch(meta, refresh = true) }) { Text(stringResource(Res.string.universe_retry)) }
            } else {
                Text(stringResource(Res.string.universe_empty), style = MaterialTheme.typography.bodyMedium)
            }
        }
    } else {
        val relations = items.orEmpty().associate { it.item.id to it.relations.joinToString(" · ") }
        DetailPosterRailSection(
            title = stringResource(Res.string.universe_title),
            items = cards,
            watchedKeys = watchedKeys,
            fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
            showHeader = showHeader,
            horizontalScrollPadding = horizontalScrollPadding,
            sourceLabel = stringResource(Res.string.universe_source),
            itemSubtitle = { relations[it.id] },
            landscapeOverride = options.universePosterStyle == UniversePosterStyle.Landscape,
            waitForBackdrop = false,
            onPosterClick = onPosterClick,
        )
    }
}
