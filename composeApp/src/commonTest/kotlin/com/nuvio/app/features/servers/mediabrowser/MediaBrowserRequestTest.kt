package com.nuvio.app.features.servers.mediabrowser

import com.nuvio.app.features.servers.ServerConnection
import com.nuvio.app.features.servers.ServerLibrary
import com.nuvio.app.features.servers.ServerMediaKind
import com.nuvio.app.features.servers.ServerSession
import com.nuvio.app.features.servers.jellyfin.JellyfinProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MediaBrowserRequestTest {
    private fun session(providerId: String, address: String) = ServerSession(
        connection = ServerConnection(
            id = "cabc",
            providerId = providerId,
            name = "Home",
            address = address,
            remoteServerId = "s1",
            remoteUserId = "u1",
            userName = "viewer",
            credentialRef = "k1",
            libraries = listOf(ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE)),
        ),
        token = "secret",
    )

    @Test
    fun jellyfinUsesQueryScopedRoutes() = runTest {
        val http = TestHttp { request ->
            if (request.url.encodedPath.endsWith("/UserViews")) """{"Items": []}""" else ""
        }
        val jellyfin = JellyfinProvider(http.client)
        val session = session("jellyfin", "https://media.example.com/jellyfin")

        jellyfin.libraries(session)
        jellyfin.setPlayed(session, "i1", played = true)

        assertEquals(
            listOf(
                "GET https://media.example.com/jellyfin/UserViews?userId=u1",
                "POST https://media.example.com/jellyfin/UserPlayedItems/i1?userId=u1",
            ),
            http.requests.map { "${it.method.value} ${it.url}" },
        )
        http.requests.forEach { request ->
            assertTrue(request.headers["Authorization"].orEmpty().endsWith("Token=\"secret\""))
            assertNull(request.headers["X-Emby-Authorization"])
        }
    }
}
