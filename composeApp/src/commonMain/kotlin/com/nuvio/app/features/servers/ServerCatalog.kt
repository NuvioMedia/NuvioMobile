package com.nuvio.app.features.servers

import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogDefinition
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.servers_resume_row
import org.jetbrains.compose.resources.getString

internal const val SERVER_CATALOG_PAGE_SIZE = 50
private const val SERVER_SEARCH_LIMIT = 30

private fun ServerConnection.present(title: ServerTitle): MetaPreview =
    if (useCatalogMetadata) title.catalogPreview() else title.preview

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

    fun titleLibraries(): List<ServerLibraryRef> =
        libraries().filter { it.library.kind != ServerMediaKind.COLLECTION }

    fun collectionTarget(meta: MetaPreview): CatalogTarget.Server? {
        if (meta.type != ServerMediaKind.COLLECTION.contentType) return null
        val ref = ServerItemRef.parse(meta.id) ?: return null
        return CatalogTarget.Server(ref.connectionId, null, meta.type, collectionId = ref.itemId)
    }

    fun sourceLabel(target: CatalogTarget.Server): String =
        ServerRepository.connection(target.connectionId)?.let(ServerRepository::sourceLabel).orEmpty()

    fun homeDefinitions(): List<HomeCatalogDefinition> = resumeDefinitions() + libraries().map { ref ->
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

    private fun resumeDefinitions(): List<HomeCatalogDefinition> {
        val connections = ServerRepository.enabledConnections().filter { connection ->
            connection.selectedLibraries.isNotEmpty() &&
                ServerRepository.provider(connection)?.supports(ServerCapability.USER_STATE_READ) == true
        }
        if (connections.isEmpty()) return emptyList()
        val rowName = runBlocking { getString(Res.string.servers_resume_row) }
        return connections.map { connection ->
            val title = "${connection.name} · $rowName"
            HomeCatalogDefinition(
                key = homeKey(connection.id, RESUME_ID),
                defaultTitle = title,
                catalogName = title,
                addonName = ServerRepository.sourceLabel(connection),
                manifestUrl = "",
                type = ServerMediaKind.MOVIE.contentType,
                catalogId = RESUME_ID,
                supportsPagination = false,
                descriptorSignature = "${connection.address}|${connection.name}|resume",
                serverConnectionId = connection.id,
            )
        }
    }

    fun isServerKey(key: String): Boolean = key.startsWith(HOME_KEY_PREFIX)

    suspend fun page(target: CatalogTarget.Server, skip: Int, limit: Int = SERVER_CATALOG_PAGE_SIZE): CatalogPage {
        val connection = ServerRepository.connection(target.connectionId) ?: throw ServerException(ServerFailure.NOT_FOUND)
        target.collectionId?.let { collectionId ->
            return pageOf(connection, skip, limit) {
                ServerRepository.call(target.connectionId) { provider, session ->
                    provider.collectionPage(session, collectionId, skip, limit)
                }
            }
        }
        if (target.libraryId == RESUME_ID) {
            val items = if (skip > 0) {
                emptyList()
            } else {
                ServerRepository.call(target.connectionId) { provider, session -> provider.resumeItems(session, limit) }
            }.map(connection::present)
            return CatalogPage(items = items, rawItemCount = items.size, nextSkip = null)
        }
        val library = connection.libraries.firstOrNull { it.id == target.libraryId }
            ?: throw ServerException(ServerFailure.NOT_FOUND)
        return pageOf(connection, skip, limit) {
            ServerRepository.call(target.connectionId) { provider, session ->
                provider.libraryPage(session, library, skip, limit)
            }
        }
    }

    private suspend fun pageOf(
        connection: ServerConnection,
        skip: Int,
        limit: Int,
        load: suspend () -> ServerPage<ServerTitle>,
    ): CatalogPage {
        val page = load()
        val loaded = skip + page.items.size
        val hasMore = page.items.isNotEmpty() && (page.totalCount?.let { loaded < it } ?: (page.items.size >= limit))
        return CatalogPage(
            items = page.items.map(connection::present),
            rawItemCount = page.items.size,
            nextSkip = loaded.takeIf { hasMore },
        )
    }

    suspend fun searchSection(ref: ServerLibraryRef, query: String): HomeCatalogSection {
        val items = ServerRepository.call(ref.connection.id) { provider, session ->
            if (!provider.supports(ServerCapability.SEARCH)) throw ServerException(ServerFailure.UNSUPPORTED)
            provider.search(session, ref.library, query, SERVER_SEARCH_LIMIT)
        }.map(ref.connection::present)
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
    private const val RESUME_ID = "resume"
}
