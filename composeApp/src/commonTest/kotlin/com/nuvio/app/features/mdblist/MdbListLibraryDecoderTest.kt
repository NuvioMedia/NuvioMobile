package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.LibraryListPrivacy
import com.nuvio.app.features.tracking.supportsContentType
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class MdbListLibraryDecoderTest {
    @Test
    fun `only owned explicitly static movie and show lists become editable tabs`() {
        val rows = listOf(
            """{"id":1,"user_id":42,"type":"static","private":false,"name":"Movies","mediatype":"movie"}""",
            """{"ids":[2],"user_id":42,"type":"static","dynamic":false,"private":true,"name":"Mixed"}""",
            """{"id":3,"user_id":43,"type":"static","private":false,"name":"Another user"}""",
            """{"id":4,"type":"dynamic","dynamic":true,"name":"Rules"}""",
            """{"id":5,"type":"feed","dynamic":false,"name":"Feed"}""",
            """{"id":6,"type":"external","name":"External"}""",
            """{"id":7,"type":"official","name":"Official"}""",
            """{"id":8,"dynamic":false,"name":"Unknown"}""",
            """{"id":9,"type":"static","mediatype":"episode","name":"Episodes"}""",
            """{"id":10,"type":"static","dynamic":true,"name":"Inconsistent"}"""
        )
        val lists = decodeMdbListLibraryLists(rows.joinToString(",", "[", "]"), 42)
        assertEquals(listOf(1L, 2L), lists.map { it.id })
        assertEquals(setOf("movie"), lists[0].tab().supportedContentTypes)
        assertTrue(listOf("movie", "series", "show", "anime", "tv").all(lists[1].tab()::supportsContentType))
        assertFalse(lists[0].tab().supportsContentType("anime"))
        assertFalse(lists[1].tab().supportsContentType("episode"))
        assertEquals(LibraryListPrivacy.PUBLIC, lists[0].tab().privacy)
        assertEquals(LibraryListPrivacy.PRIVATE, lists[1].tab().privacy)
    }

    @Test
    fun `sparse invalid or ambiguous static list responses cannot clear the cache`() = runTest {
        for (body in listOf("{}", "null", """[{"type":"static","private":true,"name":"Missing ID"}]""",
            """[{"id":7,"type":"static","name":"Missing privacy"}]""",
            """[{"ids":[7,8],"type":"static","private":true,"name":"Ambiguous"}]""")) {
            expectMdbListFailure<MdbListDecodingException> { decodeMdbListLibraryLists(body, 42) }
        }
        assertTrue(decodeMdbListLibraryLists("[]", 42).isEmpty())
    }

    @Test
    fun `list payloads merge nested and legacy external IDs with bundled metadata`() {
        val page = decodeMdbListLibraryPage("""{
          "movies":[{"id":278,"imdb_id":"tt0111161","ids":{"mdblist":"a0","tvdb":190,"imdb":null},
          "title":"Shawshank","release_year":1994,"poster":"/poster.jpg","description":"Plot","genres":["crime",{"name":"Drama"}],"rank":2}],
          "shows":[{"id":1396,"imdb_id":"tt0903747","mediatype":"show","title":"Breaking Bad","rank":1}],
          "episodes":[{}],"seasons":[{}],"pagination":{"next_cursor":"cursor-one","has_more":true}
        }""")
        assertEquals(2, page.items.size)
        val movie = page.items.first()
        assertEquals(MdbListIds("tt0111161", 278, 190, mdblist = "a0"), movie.media.ids)
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", movie.media.poster)
        assertEquals(1994, movie.media.year)
        assertEquals(listOf("crime", "Drama"), movie.genres)
        assertEquals("Plot", movie.description)
        assertEquals(MdbListItemType.SHOW, page.items.last().type)
        assertEquals("cursor-one", page.nextCursor)
    }

    @Test
    fun `unified items retain their media namespaces and ignore episodes`() {
        for (body in listOf("""[{"id":1,"mediatype":"movie"},{"id":1,"mediatype":"show"},{"mediatype":"episode"}]""",
            """{"items":[{"id":1,"mediatype":"movie"},{"id":1,"mediatype":"show"}]}""")) {
            val page = decodeMdbListLibraryPage(body)
            assertEquals(listOf(MdbListItemType.MOVIE, MdbListItemType.SHOW), page.items.map { it.type })
            assertFalse(page.items[0].matches(page.items[1]))
        }
    }

    @Test
    fun `malformed pages and conflicting media types fail without inventing items`() = runTest {
        for (body in listOf("{}", "null", "[] junk", """{"movies":[{}]}""", """{"movies":[{"id":0}]}""",
            """[{"id":1}]""", """{"movies":[{"id":1,"mediatype":"show"}]}""", """{"movies":[{"id":1,"mediatype":"unknown"}]}""")) {
            expectMdbListFailure<MdbListDecodingException> { decodeMdbListLibraryPage(body) }
        }
    }

    @Test
    fun `pagination derives a next offset only when the server proves more items exist`() = runTest {
        val prefix = """{"movies":[],"pagination": """
        assertEquals(1000, decodeMdbListLibraryPage(prefix + """{"offset":0,"limit":1000,"total":1001}}""").nextOffset)
        assertEquals(null, decodeMdbListLibraryPage(prefix + """{"offset":0,"limit":1000,"total":1000}}""").nextOffset)
        expectMdbListFailure<MdbListDecodingException> {
            decodeMdbListLibraryPage(prefix + """{"has_more":true}}""")
        }
    }

    @Test
    fun `projection merges aliases across lists while preserving per-list order and movie show identity`() {
        val movie = MdbListLibraryItem(MdbListItemType.MOVIE, MdbListMedia(MdbListIds(tmdb = 1), "Movie"), rank = 4)
        val aliased = movie.copy(media = movie.media.copy(ids = MdbListIds("tt1", 1, tvdb = 3, trakt = 4, mdblist = "a1")), rank = 8)
        val show = movie.copy(type = MdbListItemType.SHOW, media = movie.media.copy(title = "Show"))
        val projection = MdbListLibraryProjection(MdbListLibrarySnapshot(itemsByList = mapOf(
            MDBLIST_WATCHLIST_KEY to listOf(movie, show), MDBLIST_TEST_LIST_KEY to listOf(aliased)
        )))
        assertEquals(2, projection.entries.size)
        val entry = projection.entries.first { it.type == "movie" }
        assertEquals("tt1", entry.id)
        assertEquals("a1", entry.trackingProviderItemId)
        assertEquals(mapOf(MDBLIST_WATCHLIST_KEY to 4, MDBLIST_TEST_LIST_KEY to 8), entry.listRanks)
        for (alias in listOf("tt1", "imdb:tt1", "tmdb:1", "tvdb:3", "trakt:4", "mdblist:a1")) {
            assertEquals(setOf(MDBLIST_WATCHLIST_KEY, MDBLIST_TEST_LIST_KEY), projection.membership(alias, "movie"))
            assertEquals(entry, projection.find(alias, "movie"))
            assertEquals(null, projection.find(alias, "episode"))
        }
        assertEquals(setOf(MDBLIST_WATCHLIST_KEY), projection.membership("tmdb:1", "anime"))
        assertTrue(projection.membership("tt1", "series").isEmpty())
        assertEquals("Show", projection.find("tmdb:1", "anime")?.name)
    }
}
