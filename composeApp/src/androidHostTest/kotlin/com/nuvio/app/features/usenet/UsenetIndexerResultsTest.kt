package com.nuvio.app.features.usenet

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UsenetIndexerResultsTest {
    private val unlimited = UsenetSourceConfiguration.UNLIMITED_RESULTS

    @Test fun `auto reads as many results per indexer as before`() {
        val auto = UsenetSourceConfiguration(indexerResultsLimit = UsenetSourceConfiguration.AUTO_INDEXER_RESULTS)
        assertEquals(UsenetSourceConfiguration.AUTO_INDEXER_RESULTS, auto.indexerResultsLimit)
        assertEquals(200, auto.copy(maxResults = 50).indexerResults)
        assertEquals(200, auto.copy(maxResults = 200).indexerResults)
        assertEquals(500, auto.copy(maxResults = 500).indexerResults)
        assertEquals(1000, auto.copy(maxResults = unlimited).indexerResults)
    }

    @Test fun `a chosen number is read as it is, whatever is listed`() {
        val config = UsenetSourceConfiguration(indexerResultsLimit = 100)
        assertEquals(100, config.copy(maxResults = 50).indexerResults)
        assertEquals(100, config.copy(maxResults = 500).indexerResults)
        assertEquals(100, config.copy(maxResults = unlimited).indexerResults)
        assertEquals(1000, config.copy(indexerResultsLimit = 1000, maxResults = 10).indexerResults)
    }

    @Test fun `settings without a fetch choice default to 200 independently of display limit`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString<UsenetSourceConfiguration>("""{"maxResults":500}""")
        assertEquals(200, old.indexerResultsLimit)
        assertEquals(200, old.indexerResults)
    }

    @Test fun `per indexer choices survive storage and override only that indexer`() {
        val hydra = UsenetIndexer(name = "Hydra", apiUrl = "https://hydra.test/api", fetchResultsLimit = 0)
        val direct = UsenetIndexer(name = "Direct", apiUrl = "https://direct.test/api", fetchResultsLimit = 100)
        val inherited = UsenetIndexer(name = "Inherited", apiUrl = "https://inherited.test/api")
        val original = UsenetSourceConfiguration(indexers = listOf(hydra, direct, inherited))
        val saved = Json.decodeFromString<UsenetSourceConfiguration>(Json.encodeToString(original))
        saved.validate()
        assertEquals(original, saved)
        assertEquals(listOf(0, 100, 200), saved.indexers.map(saved::indexerResults))
        assertEquals(listOf(0, 100, 500), saved.indexers.map(saved.copy(indexerResultsLimit = 500)::indexerResults))
        assertEquals(listOf(0, 100, 200), saved.indexers.map(saved.copy(maxResults = unlimited)::indexerResults))
        assertEquals(50, saved.maxResults)
        assertThrows(IllegalArgumentException::class.java) { hydra.copy(fetchResultsLimit = -1).validate() }
        assertThrows(IllegalArgumentException::class.java) { hydra.copy(fetchResultsLimit = 1001).validate() }
    }

    @Test fun `only auto or a count up to the indexer bound is valid`() {
        UsenetSourceConfiguration(indexerResultsLimit = 0).validate()
        UsenetSourceConfiguration.INDEXER_RESULT_CHOICES.forEach { UsenetSourceConfiguration(indexerResultsLimit = it).validate() }
        assertThrows(IllegalArgumentException::class.java) { UsenetSourceConfiguration(indexerResultsLimit = -1).validate() }
        assertThrows(IllegalArgumentException::class.java) { UsenetSourceConfiguration(indexerResultsLimit = 1001).validate() }
    }
}
