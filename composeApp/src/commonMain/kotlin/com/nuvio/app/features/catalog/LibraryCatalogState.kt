package com.nuvio.app.features.catalog

import com.nuvio.app.features.library.LibraryUiState
import com.nuvio.app.features.library.LibraryProviderOrders
import com.nuvio.app.features.library.LibrarySortOption
import com.nuvio.app.features.library.librarySorter
import com.nuvio.app.features.library.observeLibraryProviderOrders
import com.nuvio.app.features.library.sortLibraryItems
import com.nuvio.app.features.library.toMetaPreview
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.library_error_sort_failed
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal fun Flow<LibraryUiState>.libraryCatalogStates(
    target: CatalogTarget.Library,
    providerOrders: Flow<LibraryProviderOrders>? = null,
): Flow<CatalogUiState> = if (providerOrders == null) {
    map { it.libraryCatalogState(target, LibraryProviderOrders()) }
} else {
    combine(providerOrders) { libraryState, orders -> libraryState.libraryCatalogState(target, orders) }
}.distinctUntilChanged()

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun Flow<LibraryUiState>.libraryCatalogOrders(target: CatalogTarget.Library): Flow<LibraryProviderOrders> =
    combine(ProfileRepository.state) { library, profile ->
        library.sourceMode to (profile.activeProfile?.profileIndex ?: ProfileRepository.activeProfileId)
    }.distinctUntilChanged().flatMapLatest { (source, _) ->
        observeLibraryProviderOrders(source.librarySorter(), listOf(target.sectionType), target.sortOption)
    }

private suspend fun LibraryUiState.libraryCatalogState(
    target: CatalogTarget.Library,
    orders: LibraryProviderOrders,
): CatalogUiState {
    val items = sections.firstOrNull { it.type == target.sectionType }?.items.orEmpty()
    return CatalogUiState(
        items = sortLibraryItems(
            items = items,
            selected = if (orders.failed) LibrarySortOption.DEFAULT else target.sortOption,
            sourceMode = sourceMode,
            listKey = target.sectionType,
            providerOrder = orders.ranks[target.sectionType],
        ).map { it.toMetaPreview() }.let(::dedupeCatalogItems),
        isLoading = isLoading,
        errorMessage = if (orders.failed) getString(Res.string.library_error_sort_failed) else errorMessage,
    )
}
