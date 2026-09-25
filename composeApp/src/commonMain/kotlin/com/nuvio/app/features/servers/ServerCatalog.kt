package com.nuvio.app.features.servers

import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogDefinition
import com.nuvio.app.features.home.HomeCatalogSection

internal const val SERVER_CATALOG_PAGE_SIZE = 50
private const val SERVER_SEARCH_LIMIT = 30

internal data class ServerLibraryRef(
    val connection: ServerConnection,
    val library: ServerLibrary,
) {
    val title: String
        get() = "${connection.name} · ${library.name}"

    val target: CatalogTarget.Server
        get() = CatalogTarget.Server(connection.id, library.id, library.kind.contentType)
}

internal object ServerCatalog {
    fun libraries(): List<ServerLibraryRef> =
        ServerRepository.enabledConnections().flatMap { connection ->
            connection.selectedLibraries.map { ServerLibraryRef(connection, it) }
        }

    fun homeDefinitions(): List<HomeCatalogDefinition> = libraries().map { ref ->
        HomeCatalogDefinition(
            key = homeKey(ref.connection.id, ref.library.id),
            defaultTitle = ref.title,
            catalogName = ref.title,
            addonName = ServerRepository.sourceLabel(ref.connection),
            manifestUrl = "",
            type = ref.library.kind.contentType,
            catalogId = ref.library.id,
            supportsPagination = true,
            descriptorSignature = "${ref.connection.address}|${ref.connection.name}|${ref.library.name}",
            serverConnectionId = ref.connection.id,
        )
    }

    fun isServerKey(key: String): Boolean = key.startsWith(HOME_KEY_PREFIX)

    suspend fun page(target: CatalogTarget.Server, skip: Int, limit: Int = SERVER_CATALOG_PAGE_SIZE): CatalogPage {
        val library = ServerRepository.connection(target.connectionId)
            ?.libraries
            ?.firstOrNull { it.id == target.libraryId }
            ?: throw ServerException(ServerFailure.NOT_FOUND)
        val page = ServerRepository.call(target.connectionId) { provider, session ->
            provider.libraryPage(session, library, skip, limit)
        }
        val loaded = skip + page.items.size
        val hasMore = page.items.isNotEmpty() && (page.totalCount?.let { loaded < it } ?: (page.items.size >= limit))
        return CatalogPage(
            items = page.items,
            rawItemCount = page.items.size,
            nextSkip = loaded.takeIf { hasMore },
        )
    }

    suspend fun searchSection(ref: ServerLibraryRef, query: String): HomeCatalogSection {
        val items = ServerRepository.call(ref.connection.id) { provider, session ->
            if (!provider.supports(ServerCapability.SEARCH)) throw ServerException(ServerFailure.UNSUPPORTED)
            provider.search(session, ref.library, query, SERVER_SEARCH_LIMIT)
        }
        val label = ServerRepository.sourceLabel(ref.connection)
        return HomeCatalogSection(
            key = "${homeKey(ref.connection.id, ref.library.id)}:search:${query.lowercase()}",
            title = ref.title,
            subtitle = label,
            addonName = label,
            target = ref.target,
            items = items,
        )
    }

    suspend fun details(ref: ServerItemRef): ServerItemDetails =
        ServerRepository.call(ref.connectionId) { provider, session -> provider.details(session, ref.itemId) }

    private fun homeKey(connectionId: String, libraryId: String) = "$HOME_KEY_PREFIX$connectionId:$libraryId"

    private const val HOME_KEY_PREFIX = "server:"
}
