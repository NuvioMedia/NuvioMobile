package com.nuvio.app.features.search

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.HomeCatalogSection

enum class SearchEmptyStateReason {
    NoActiveAddons,
    NoSearchCatalogs,
    NoResults,
    RequestFailed,
}

data class SearchUiState(
    val isLoading: Boolean = false,
    val sections: List<HomeCatalogSection> = emptyList(),
    val emptyStateReason: SearchEmptyStateReason? = null,
    val errorMessage: String? = null,
)

enum class DiscoverEmptyStateReason {
    NoActiveAddons,
    NoDiscoverCatalogs,
    NoResults,
    RequestFailed,
}

data class DiscoverCatalogOption(
    val key: String,
    val addonName: String,
    val manifestUrl: String,
    val type: String,
    val catalogId: String,
    val catalogName: String,
    val genreOptions: List<String> = emptyList(),
    val genreRequired: Boolean = false,
    val supportsPagination: Boolean = false,
    val server: CatalogTarget.Server? = null,
)

data class DiscoverUiState(
    val typeOptions: List<String> = emptyList(),
    val selectedType: String? = null,
    val catalogOptions: List<DiscoverCatalogOption> = emptyList(),
    val selectedCatalogKey: String? = null,
    val selectedGenre: String? = null,
    val items: List<MetaPreview> = emptyList(),
    val isLoading: Boolean = false,
    val nextSkip: Int? = null,
    val consecutiveDuplicatePages: Int = 0,
    val emptyStateReason: DiscoverEmptyStateReason? = null,
    val errorMessage: String? = null,
) {
    val selectedCatalog: DiscoverCatalogOption?
        get() = catalogOptions.firstOrNull { it.key == selectedCatalogKey }

    val genreOptions: List<String>
        get() = selectedCatalog?.genreOptions.orEmpty()

    val canLoadMore: Boolean
        get() = nextSkip != null
}
