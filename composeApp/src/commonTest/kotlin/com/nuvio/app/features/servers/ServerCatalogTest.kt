package com.nuvio.app.features.servers

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.MetaPreview
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
    fun loadsNativeDetailsWithoutExternalIds() = runTest {
        val connection = installFakeServer()
        val details = ServerCatalog.details(ServerItemRef(connection.id, FakeServerProvider.SHOW_ID))
        assertEquals("series", details.meta.type)
        assertEquals(false, details.externalIds.hasAny)
        assertEquals(ServerItemRef(connection.id, "901"), ServerItemRef.parse(details.meta.videos.single().id))
    }
}
