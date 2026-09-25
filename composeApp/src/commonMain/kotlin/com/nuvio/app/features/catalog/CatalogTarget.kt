package com.nuvio.app.features.catalog

import com.nuvio.app.features.library.LibrarySortOption
import kotlinx.serialization.Serializable

sealed interface CatalogTarget {
    val contentType: String
    val supportsPagination: Boolean

    data class Addon(
        val manifestUrl: String,
        override val contentType: String,
        val catalogId: String,
        val genre: String? = null,
        override val supportsPagination: Boolean = false,
    ) : CatalogTarget

    data class Library(
        override val contentType: String,
        val sectionType: String,
        val sortOption: LibrarySortOption = LibrarySortOption.DEFAULT,
    ) : CatalogTarget {
        override val supportsPagination: Boolean = false
    }

    data class Server(
        val connectionId: String,
        val libraryId: String,
        override val contentType: String,
    ) : CatalogTarget {
        override val supportsPagination: Boolean = true
    }

    data class CollectionSource(
        val collectionId: String,
        val folderId: String,
        val sourceKey: String,
        override val contentType: String,
        override val supportsPagination: Boolean = false,
    ) : CatalogTarget
}

@Serializable
enum class CatalogTargetKind {
    ADDON,
    LIBRARY,
    COLLECTION_SOURCE,
}
