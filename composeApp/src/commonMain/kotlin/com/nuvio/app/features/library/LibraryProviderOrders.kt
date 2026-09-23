package com.nuvio.app.features.library

import com.nuvio.app.features.tracking.TrackingLibrarySorter
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.providerId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

internal data class LibraryProviderOrders(
    val ranks: Map<String, Map<String, Int>> = emptyMap(),
    val failed: Boolean = false,
)

internal fun LibrarySourceMode.librarySorter(): TrackingLibrarySorter? =
    providerId?.let(TrackingProviderRegistry::libraryProvider)?.listSorter

internal fun observeLibraryProviderOrders(
    sorter: TrackingLibrarySorter?,
    listKeys: List<String>,
    sortOption: LibrarySortOption,
): Flow<LibraryProviderOrders> {
    if (sorter == null || listKeys.isEmpty() ||
        sortOption !in listOf(LibrarySortOption.ADDED_ASC, LibrarySortOption.ADDED_DESC)) {
        return flowOf(LibraryProviderOrders())
    }
    return combine(listKeys.distinct().map { key ->
        sorter.observeAddedOrder(key, sortOption == LibrarySortOption.ADDED_DESC).map { keys ->
            key to keys?.withIndex()?.associate { (index, item) -> item to index }
        }.onStart { emit(key to null) }
    }) { orders ->
        LibraryProviderOrders(orders.mapNotNull { (key, ranks) -> ranks?.let { key to it } }.toMap())
    }.catch { emit(LibraryProviderOrders(failed = true)) }.distinctUntilChanged()
}
