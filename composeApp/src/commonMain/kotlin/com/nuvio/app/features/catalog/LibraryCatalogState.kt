package com.nuvio.app.features.catalog

import com.nuvio.app.features.library.LibraryUiState
import com.nuvio.app.features.library.sortLibraryItems
import com.nuvio.app.features.library.toMetaPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal fun Flow<LibraryUiState>.libraryCatalogStates(
    target: CatalogTarget.Library,
): Flow<CatalogUiState> =
    map { libraryState ->
        val items = libraryState.sections
            .firstOrNull { it.type == target.sectionType }
            ?.items
            .orEmpty()
        CatalogUiState(
            items = sortLibraryItems(
                items = items,
                selected = target.sortOption,
                sourceMode = libraryState.sourceMode,
                listKey = target.sectionType,
            ).map { it.toMetaPreview() }.let(::dedupeCatalogItems),
            isLoading = libraryState.isLoading,
            errorMessage = libraryState.errorMessage,
        )
    }.distinctUntilChanged()
