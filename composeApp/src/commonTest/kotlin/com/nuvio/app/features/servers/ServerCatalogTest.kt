package com.nuvio.app.features.servers

import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tracking.TrackingExternalIds
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerCatalogTest {
    @AfterTest
    fun tearDown() = removeFakeServer()

    @Test
    fun paginatesLibraryWithOpaqueNumericIds() = runTest {
        val connection = installFakeServer()
        val target = CatalogTarget.Server(connection.id, FakeServerProvider.MOVIE_LIBRARY.id, "movie")

        val first = ServerCatalog.page(target, skip = 0)
        assertEquals(SERVER_CATALOG_PAGE_SIZE, first.items.size)
        assertEquals(SERVER_CATALOG_PAGE_SIZE, first.nextSkip)
        assertEquals(ServerItemRef(connection.id, "0"), ServerItemRef.parse(first.items.first().id))

        val last = ServerCatalog.page(target, skip = 100)
        assertEquals(20, last.items.size)
        assertNull(last.nextSkip)
    }

    @Test
    fun buildsAttributedHomeRowsForSelectedLibraries() {
        val connection = installFakeServer()
        ServerRepository.setLibrarySelected(connection.id, FakeServerProvider.SERIES_LIBRARY.id, false)

        val rows = ServerCatalog.homeDefinitions().filter { it.serverConnectionId == connection.id }
        assertEquals(listOf("Box · Movies"), rows.map { it.defaultTitle })
        assertEquals("Fake · Box", rows.single().addonName)
        assertTrue(ServerCatalog.isServerKey(rows.single().key))
    }

    @Test
    fun disabledConnectionsProvideNoLibraries() {
        val connection = installFakeServer()
        ServerRepository.setEnabled(connection.id, false)
        assertTrue(ServerCatalog.libraries().none { it.connection.id == connection.id })
    }

    @Test
    fun collectionCardsOpenTheirContentsAsACatalog() = runTest {
        val connection = installFakeServer(
            libraries = listOf(FakeServerProvider.MOVIE_LIBRARY, FakeServerProvider.COLLECTION_LIBRARY),
        )
        assertTrue(ServerCatalog.homeDefinitions().any { it.defaultTitle == "Box · Featured" && it.type == "collection" })
        assertTrue(ServerCatalog.titleLibraries().none { it.library.kind == ServerMediaKind.COLLECTION })
        assertTrue(ServerMatcher.supports(connection, ServerMediaKind.MOVIE))

        val card = MetaPreview(id = ServerItemRef(connection.id, "c-popular").encode(), type = "collection", name = "Popular")
        val target = assertNotNull(ServerCatalog.collectionTarget(card))
        assertEquals("c-popular", target.collectionId)
        assertEquals("Fake · Box", ServerCatalog.sourceLabel(target))
        assertNull(ServerCatalog.collectionTarget(card.copy(type = "movie")))

        val page = ServerCatalog.page(target, skip = 0)
        assertEquals(listOf("movie", "series"), page.items.map { it.type })
        assertNull(page.nextSkip)
    }

    @Test
    fun catalogDetailsSettingUsesCompatibleIdsOnly() = runTest {
        val provider = FakeServerProvider().apply {
            indexedIds["0"] = TrackingExternalIds(imdb = "tt0111161", tmdb = 278)
            indexedIds["1"] = TrackingExternalIds(tmdb = 550)
        }
        val connection = installFakeServer(provider)
        val target = CatalogTarget.Server(connection.id, FakeServerProvider.MOVIE_LIBRARY.id, "movie")

        val native = ServerCatalog.page(target, skip = 0).items.take(3).map { it.id }
        assertTrue(native.all(ServerItemRef::isServerId))

        ServerRepository.setCatalogMetadata(connection.id, true)
        val catalog = ServerCatalog.page(target, skip = 0).items.take(3)
        assertEquals(listOf("tmdb:278", "tmdb:550", ServerItemRef(connection.id, "2").encode()), catalog.map { it.id })
        assertEquals("Item 0", catalog.first().name)
    }

    @Test
    fun collectionsKeepServerIdentity() {
        val collection = ServerTitle(
            MetaPreview(id = ServerItemRef("c1", "b1").encode(), type = "collection", name = "Popular"),
            TrackingExternalIds(imdb = "tt1"),
        )
        assertEquals(collection.preview, collection.catalogPreview(listOf(metaAddon("any", emptyList()))))
    }

    @Test
    fun catalogIdFollowsInstalledMetaAddonsInOrder() {
        val title = ServerTitle(
            MetaPreview(id = ServerItemRef("c1", "7").encode(), type = "series", name = "Show"),
            TrackingExternalIds(imdb = "tt1", tmdb = 2, kitsu = 3),
        )
        val kitsu = metaAddon("kitsu", listOf("kitsu:"))
        val cinemeta = metaAddon("cinemeta", listOf("tt"))
        assertEquals("kitsu:3", title.catalogPreview(listOf(kitsu, cinemeta)).id)
        assertEquals("tt1", title.catalogPreview(listOf(cinemeta, kitsu)).id)
        assertEquals("tmdb:2", title.catalogPreview(listOf(metaAddon("mal", listOf("mal:")))).id)
        assertEquals(title.preview.id, title.copy(externalIds = TrackingExternalIds(kitsu = 3)).catalogPreview(emptyList()).id)
    }

    private fun metaAddon(id: String, prefixes: List<String>) = AddonManifest(
        id = id,
        name = id,
        description = "",
        version = "1",
        resources = listOf(AddonResource(name = "meta", types = listOf("movie", "series"), idPrefixes = prefixes)),
        types = listOf("movie", "series"),
        idPrefixes = prefixes,
        transportUrl = "https://$id.example/manifest.json",
    )

    @Test
    fun loadsNativeDetailsWithoutExternalIds() = runTest {
        val connection = installFakeServer()
        val details = ServerCatalog.details(ServerItemRef(connection.id, FakeServerProvider.SHOW_ID))
        assertEquals("series", details.meta.type)
        assertEquals(false, details.externalIds.hasAny)
        assertEquals(ServerItemRef(connection.id, "901"), ServerItemRef.parse(details.meta.videos.single().id))
    }
}
