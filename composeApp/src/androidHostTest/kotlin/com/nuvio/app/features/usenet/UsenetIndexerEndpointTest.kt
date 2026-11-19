package com.nuvio.app.features.usenet

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsenetIndexerEndpointTest {
    private fun endpoint(url: String, path: String) = UsenetIndexer(name = "Indexer", apiUrl = url, apiPath = path).endpoint

    @Test fun `the api path is appended to the url`() {
        assertEquals("https://indexer.example/api", endpoint("https://indexer.example/", "/api"))
        assertEquals("https://indexer.example/api", endpoint("https://indexer.example", "api"))
        assertEquals("http://prowlarr.local:9696/1/api", endpoint("http://prowlarr.local:9696/1", "/api"))
        // Query parameters stay on the endpoint.
        assertEquals("https://indexer.test/api?custom=1", endpoint("https://indexer.test/?custom=1", "/api"))
        assertEquals("https://indexer.test/v2/api", endpoint("https://indexer.test", "/v2/api/"))
    }

    @Test fun `a url that is already the complete endpoint is used as is`() {
        assertEquals("https://indexer.test/api", endpoint("https://indexer.test/api", "/api"))
        assertEquals("http://prowlarr.local:9696/1/api", endpoint("http://prowlarr.local:9696/1/api", "/api"))
        assertEquals("http://hydra.local:5076/api?x=1", endpoint("http://hydra.local:5076/api?x=1", ""))
    }

    @Test fun `indexers saved before the field existed keep their complete url`() {
        val saved = Json { ignoreUnknownKeys = true }.decodeFromString<UsenetIndexer>(
            """{"id":"1","name":"Old","apiUrl":"https://indexer.test/newznab","apiKey":"k"}""")
        assertEquals("", saved.apiPath)
        assertEquals("https://indexer.test/newznab", saved.endpoint)
    }

    @Test fun `invalid api paths are rejected`() {
        for (path in listOf("/api?t=caps", "/api#x", "/../api", "/a pi")) {
            assertTrue(path, runCatching { UsenetIndexer(name = "I", apiUrl = "https://i.test", apiPath = path).validate() }.isFailure)
        }
        UsenetIndexer(name = "I", apiUrl = "https://i.test", apiPath = DEFAULT_API_PATH).validate()
    }
}
