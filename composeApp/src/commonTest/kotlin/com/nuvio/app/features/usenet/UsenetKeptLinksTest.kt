package com.nuvio.app.features.usenet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsenetKeptLinksTest {
    private val first = UsenetIndexer(id = "idx-a", name = "A", apiUrl = "https://a.test", apiKey = "aaaaaaaa1111")
    private val second = UsenetIndexer(id = "idx-b", name = "B", apiUrl = "https://b.test", apiKey = "bbbbbbbbbbbb2222")
    private val config = UsenetSourceConfiguration(indexers = listOf(first, second))

    @Test
    fun keysAreReplacedWhereverTheIndexerPutThem() {
        val link = "https://a.test/getnzb/abc.nzb?r=aaaaaaaa1111&i=7"
        val kept = UsenetKeptLinks.withoutApiKeys(link, config)
        assertFalse("aaaaaaaa1111" in kept)
        assertEquals("https://a.test/getnzb/abc.nzb?r={indexer-key:idx-a}&i=7", kept)
        val inPath = UsenetKeptLinks.withoutApiKeys("https://b.test/api/bbbbbbbbbbbb2222/get/1", config)
        assertEquals("https://b.test/api/{indexer-key:idx-b}/get/1", inPath)
    }

    @Test
    fun todaysKeyIsPutBack() {
        val kept = "https://a.test/getnzb/abc.nzb?r={indexer-key:idx-a}&i=7"
        assertEquals("https://a.test/getnzb/abc.nzb?r=aaaaaaaa1111&i=7", UsenetKeptLinks.withApiKeys(kept, config))
        val newKey = config.copy(indexers = listOf(first.copy(apiKey = "cccccccc3333")))
        assertEquals("https://a.test/getnzb/abc.nzb?r=cccccccc3333&i=7", UsenetKeptLinks.withApiKeys(kept, newKey))
        assertEquals(first, UsenetKeptLinks.indexerOf(kept, config))
    }

    @Test
    fun aRemovedIndexerOrEmptyKeyMeansTheLinkCannotOpen() {
        val kept = "https://a.test/getnzb/abc.nzb?r={indexer-key:idx-a}"
        assertNull(UsenetKeptLinks.withApiKeys(kept, config.copy(indexers = listOf(second))))
        assertNull(UsenetKeptLinks.withApiKeys(kept, config.copy(indexers = listOf(first.copy(apiKey = "")))))
        assertNull(UsenetKeptLinks.indexerOf(kept, config.copy(indexers = listOf(second))))
    }

    @Test
    fun shortKeysAndLinksWithoutKeysAreKeptAsTheyAre() {
        val short = config.copy(indexers = listOf(first.copy(apiKey = "abc")))
        val link = "https://a.test/getnzb/abc.nzb?apikey=abc"
        assertEquals(link, UsenetKeptLinks.withoutApiKeys(link, short))
        val plain = "https://addon.test/nzb/1"
        assertEquals(plain, UsenetKeptLinks.withoutApiKeys(plain, config))
        assertEquals(plain, UsenetKeptLinks.withApiKeys(plain, config))
    }

    @Test
    fun aKeyHoldingReplacementCharactersIsPutBackLiterally() {
        val dollar = config.copy(indexers = listOf(first.copy(apiKey = "key\$1withdollar")))
        val kept = UsenetKeptLinks.withoutApiKeys("https://a.test/get?r=key\$1withdollar", dollar)
        assertTrue("{indexer-key:idx-a}" in kept)
        assertEquals("https://a.test/get?r=key\$1withdollar", UsenetKeptLinks.withApiKeys(kept, dollar))
    }
}
