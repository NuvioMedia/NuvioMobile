package com.nuvio.app.features.debrid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalDebridServiceTest {
    @Test
    fun `maps valid Torbox cache response and normalizes hashes`() {
        val response = torboxResponse(
            data = mapOf(
                "ABC123" to TorboxCachedItemDto(
                    name = "Movie.2026.mkv",
                    size = 1234L,
                ),
            ),
        )

        assertEquals(
            mapOf(
                "abc123" to LocalDebridCachedItem(
                    name = "Movie.2026.mkv",
                    size = 1234L,
                ),
            ),
            torboxCachedItems(response),
        )
    }

    @Test
    fun `maps valid empty Torbox cache response to no cached items`() {
        assertEquals(emptyMap(), torboxCachedItems(torboxResponse(data = emptyMap())))
    }

    @Test
    fun `rejects successful HTTP response with missing Torbox envelope`() {
        val response = DebridApiResponse<TorboxEnvelopeDto<Map<String, TorboxCachedItemDto>>>(
            status = 200,
            body = null,
            rawBody = "not-json",
        )

        assertNull(torboxCachedItems(response))
    }

    @Test
    fun `rejects Torbox success response with missing cache data`() {
        assertNull(torboxCachedItems(torboxResponse(data = null)))
    }

    @Test
    fun `rejects unsuccessful Torbox response`() {
        val response = DebridApiResponse(
            status = 503,
            body = TorboxEnvelopeDto<Map<String, TorboxCachedItemDto>>(
                success = false,
                data = emptyMap(),
            ),
            rawBody = "",
        )

        assertNull(torboxCachedItems(response))
    }

    private fun torboxResponse(
        data: Map<String, TorboxCachedItemDto>?,
    ): DebridApiResponse<TorboxEnvelopeDto<Map<String, TorboxCachedItemDto>>> =
        DebridApiResponse(
            status = 200,
            body = TorboxEnvelopeDto(
                success = true,
                data = data,
            ),
            rawBody = "",
        )
}
