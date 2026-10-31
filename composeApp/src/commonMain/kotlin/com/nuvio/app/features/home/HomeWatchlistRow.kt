package com.nuvio.app.features.home

import com.nuvio.app.core.poster.CustomPosterScreen
import com.nuvio.app.core.poster.CustomPosterUrlRepository
import com.nuvio.app.core.poster.withCustomPosterUrls
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.catalog.dedupeCatalogItems
import com.nuvio.app.features.library.LOCAL_LIBRARY_LIST_KEY
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.LibrarySortOption
import com.nuvio.app.features.library.LibraryUiState
import com.nuvio.app.features.library.isRemoteTrackingSource
import com.nuvio.app.features.library.sortLibraryItems
import com.nuvio.app.features.library.toMetaPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

private const val HOME_WATCHLIST_SECTION_KEY = "home_watchlist"

internal data class HomeWatchlistSelection(
    val sectionType: String,
    val items: List<LibraryItem>,
)

/** Remote sources expose a watchlist section. The local library has none, so it uses all saved items. */
internal fun LibraryUiState.homeWatchlistSelection(): HomeWatchlistSelection? {
    val isRemote = sourceMode.isRemoteTrackingSource
    val sectionType = (if (isRemote) watchlistSectionType else LOCAL_LIBRARY_LIST_KEY) ?: return null
    val selected = if (isRemote) sections.firstOrNull { it.type == sectionType }?.items.orEmpty() else items
    if (selected.isEmpty()) return null
    return HomeWatchlistSelection(
        sectionType = sectionType,
        items = sortLibraryItems(
            items = selected,
            selected = LibrarySortOption.DEFAULT,
            sourceMode = sourceMode,
            listKey = sectionType,
        ),
    )
}

internal fun LibraryUiState.homeWatchlistSection(
    title: String,
    posterPattern: String,
): HomeCatalogSection? {
    val selection = homeWatchlistSelection() ?: return null
    val previews = dedupeCatalogItems(selection.items.map { it.toMetaPreview() })
    return HomeCatalogSection(
        key = HOME_WATCHLIST_SECTION_KEY,
        title = title,
        subtitle = "",
        addonName = "",
        target = CatalogTarget.Library(
            contentType = previews.first().type,
            sectionType = selection.sectionType,
        ),
        items = previews.withCustomPosterUrls(posterPattern),
    )
}

internal fun homeWatchlistSections(title: String): Flow<HomeCatalogSection?> {
    LibraryRepository.ensureLoaded()
    CustomPosterUrlRepository.ensureLoaded()
    return combine(
        LibraryRepository.uiState,
        CustomPosterUrlRepository.pattern,
        CustomPosterUrlRepository.enabledScreens,
    ) { library, _, _ ->
        library.homeWatchlistSection(
            title = title,
            posterPattern = CustomPosterUrlRepository.patternForScreen(CustomPosterScreen.HOME),
        )
    }.distinctUntilChanged().flowOn(Dispatchers.Default)
}
