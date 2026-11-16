package com.nuvio.app.features.servers.mediabrowser

import com.nuvio.app.features.servers.ServerConnection
import com.nuvio.app.features.servers.ServerTitleQuery
import com.nuvio.app.features.servers.ServerIndexEntry
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.servers.ServerException
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.ServerItemRef
import com.nuvio.app.features.servers.ServerLibrary
import com.nuvio.app.features.servers.ServerMediaKind
import com.nuvio.app.features.servers.ServerPlaybackEvent
import com.nuvio.app.features.servers.ServerPlaybackEventType
import com.nuvio.app.features.servers.ServerPlaybackRequest
import com.nuvio.app.features.servers.ServerPlaybackTarget
import com.nuvio.app.features.servers.ServerPlayerCapabilities
import com.nuvio.app.features.servers.ServerSession
import com.nuvio.app.features.servers.emby.EmbyProvider
import com.nuvio.app.features.servers.jellyfin.JellyfinProvider
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MediaBrowserRequestTest {
    @Test
    fun embyLooksUpATitleByItsProviderIdsInOneRequest() = runTest {
        val http = TestHttp {
            """{"Items": [{"Id": "m1", "Name": "Apocalypse Now", "Type": "Movie", "ProductionYear": 1979,
                "ProviderIds": {"Imdb": "tt0078788", "Tmdb": "28"}}]}"""
        }
        val emby = EmbyProvider(http.client)
        val query = ServerTitleQuery(TrackingExternalIds(imdb = "tt0078788", tmdb = 28), name = "Apocalypse Now", year = 1979)

        val found = emby.lookupTitle(session("emby", "https://media.example.com"), ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE), query)

        assertEquals(listOf(ServerIndexEntry("m1", TrackingExternalIds(imdb = "tt0078788", tmdb = 28), year = 1979)), found)
        val request = http.requests.single()
        assertEquals("/emby/Users/u1/Items", request.url.encodedPath)
        assertEquals("imdb.tt0078788,tmdb.28", request.url.parameters.get("anyProviderIdEquals"))
        assertEquals("lib1", request.url.parameters.get("parentId"))
        assertEquals("Movie", request.url.parameters.get("includeItemTypes"))
        assertEquals("true", request.url.parameters.get("recursive"))
        assertEquals("ProviderIds,ProductionYear", request.url.parameters.get("fields"))
        assertNull(request.url.parameters.get("searchTerm"))
    }

    @Test
    fun embyCannotLookUpATitleWithoutImdbTmdbOrTvdbIds() = runTest {
        val http = TestHttp()
        val emby = EmbyProvider(http.client)
        val query = ServerTitleQuery(TrackingExternalIds(kitsu = 1), name = "Show", year = null)

        assertNull(emby.lookupTitle(session("emby", "https://media.example.com"), ServerLibrary("lib1", "Shows", ServerMediaKind.SERIES), query))
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun jellyfinLooksUpATitleByName() = runTest {
        val http = TestHttp { """{"Items": [{"Id": "m1", "ProductionYear": 1979, "ProviderIds": {"Imdb": "tt0078788"}}]}""" }
        val jellyfin = JellyfinProvider(http.client)
        val session = session("jellyfin", "https://media.example.com")
        val library = ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE)
        val ids = TrackingExternalIds(imdb = "tt0078788")

        val found = jellyfin.lookupTitle(session, library, ServerTitleQuery(ids, name = "Apocalypse Now", year = 1979))

        assertEquals(listOf(ServerIndexEntry("m1", ids, year = 1979)), found)
        val request = http.requests.single()
        assertEquals("/Items", request.url.encodedPath)
        assertEquals("u1", request.url.parameters.get("userId"))
        assertEquals("Apocalypse Now", request.url.parameters.get("searchTerm"))
        assertEquals("lib1", request.url.parameters.get("parentId"))
        assertEquals("20", request.url.parameters.get("limit"))
        assertNull(request.url.parameters.get("anyProviderIdEquals"))

        assertNull(jellyfin.lookupTitle(session, library, ServerTitleQuery(ids, name = null, year = null)))
        assertEquals(1, http.requests.size)
    }

    @Test
    fun jellyfinTriesTheOriginalTitleOnceWhenTheNameFindsNothing() = runTest {
        val http = TestHttp { request ->
            if (request.url.parameters.get("searchTerm") == "Intouchables") {
                """{"Items": [{"Id": "m1", "ProductionYear": 2011, "ProviderIds": {"Imdb": "tt1675434"}}]}"""
            } else {
                """{"Items": [{"Id": "m9", "ProductionYear": 2011, "ProviderIds": {"Imdb": "tt0000009"}}]}"""
            }
        }
        val jellyfin = JellyfinProvider(http.client)
        val session = session("jellyfin", "https://media.example.com")
        val library = ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE)
        val ids = TrackingExternalIds(imdb = "tt1675434")

        val found = jellyfin.lookupTitle(session, library, ServerTitleQuery(ids, "The Intouchables", 2011, originalName = "Intouchables"))!!

        assertTrue(ServerIndexEntry("m1", ids, year = 2011) in found)
        assertEquals(listOf("The Intouchables", "Intouchables"), http.requests.map { it.url.parameters.get("searchTerm") })

        jellyfin.lookupTitle(session, library, ServerTitleQuery(ids, "Intouchables", 2011, originalName = "The Intouchables"))
        assertEquals(3, http.requests.size)
        jellyfin.lookupTitle(session, library, ServerTitleQuery(ids, "The Intouchables", 2011))
        assertEquals(4, http.requests.size)
        jellyfin.lookupTitle(session, library, ServerTitleQuery(ids, "The Intouchables", 2011, originalName = " the intouchables "))
        assertEquals(5, http.requests.size)
    }

    @Test
    fun embyAsksOnceWhateverTheOriginalTitle() = runTest {
        val http = TestHttp { """{"Items": []}""" }
        val emby = EmbyProvider(http.client)
        val query = ServerTitleQuery(TrackingExternalIds(imdb = "tt1675434"), "The Intouchables", 2011, originalName = "Intouchables")

        val found = emby.lookupTitle(session("emby", "https://media.example.com"), ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE), query)

        assertTrue(found!!.isEmpty())
        assertEquals(1, http.requests.size)
    }

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
    fun searchesAioStreamsOnceAcrossItsCatalogs() = runTest {
        val http = TestHttp { request ->
            when (request.url.encodedPath) {
                "/jellyfin/System/Info/Public" -> """{"ServerName": "Den", "Version": "10.10.7", "aiostreams": {"features": {}}}"""
                else -> """{"Items": [{"Id": "m1", "Name": "Heat", "Type": "Movie"}]}"""
            }
        }
        val jellyfin = JellyfinProvider(http.client)
        val session = session("jellyfin", "https://media.example.com/jellyfin")
        val libraries = listOf(ServerLibrary("lib1", "Popular", ServerMediaKind.MOVIE), ServerLibrary("lib2", "Trending", ServerMediaKind.MOVIE))

        val first = jellyfin.search(session, ServerMediaKind.MOVIE, libraries, "heat", 30)
        jellyfin.search(session, ServerMediaKind.MOVIE, libraries, "heat", 30)

        assertEquals(listOf("Heat"), first.map { it.preview.name })
        val searches = http.requests.filter { it.url.encodedPath.endsWith("/Items") }
        assertEquals(1, http.requests.count { it.url.encodedPath.endsWith("/System/Info/Public") })
        assertEquals(2, searches.size)
        searches.forEach { request ->
            assertNull(request.url.parameters["parentId"])
            assertEquals("heat", request.url.parameters["searchTerm"])
            assertEquals("Movie", request.url.parameters["includeItemTypes"])
        }
    }

    @Test
    fun opensLibrariesThatNeedAGenreOnTheirFirstOne() = runTest {
        val http = TestHttp { request ->
            when {
                request.url.encodedPath.endsWith("/Genres") -> """{"Items": [{"Id": "g1", "Name": "Action"}]}"""
                request.url.parameters["genreIds"] == "g1" ->
                    """{"Items": [{"Id": "m1", "Name": "Heat", "Type": "Movie"}], "TotalRecordCount": 60}"""
                else -> """{"Items": [], "TotalRecordCount": 0}"""
            }
        }
        val jellyfin = JellyfinProvider(http.client)
        val session = session("jellyfin", "https://media.example.com/jellyfin")
        val library = ServerLibrary("lib1", "By genre", ServerMediaKind.MOVIE)

        val first = jellyfin.libraryPage(session, library, start = 0, limit = 50)
        jellyfin.libraryPage(session, library, start = 50, limit = 50)

        assertEquals(listOf("Heat"), first.items.map { it.preview.name })
        assertEquals(60, first.totalCount)
        assertEquals(1, http.requests.count { it.url.encodedPath.endsWith("/Genres") })
        assertEquals("lib1", http.requests.single { it.url.encodedPath.endsWith("/Genres") }.url.parameters["parentId"])
        assertEquals("g1", http.requests.last().url.parameters["genreIds"])
    }

    @Test
    fun listsMixedLibrariesWithMoviesAndShows() = runTest {
        val http = TestHttp { request ->
            if (request.url.encodedPath.endsWith("/UserViews")) {
                """{"Items": [{"Id": "lib1", "Name": "Movies", "Type": "CollectionFolder", "CollectionType": "movies"},
                              {"Id": "lib2", "Name": "Everything", "Type": "CollectionFolder"},
                              {"Id": "lib3", "Name": "Music", "Type": "CollectionFolder", "CollectionType": "music"}]}"""
            } else {
                """{"Items": [{"Id": "m1", "Name": "Heat", "Type": "Movie"}], "TotalRecordCount": 1}"""
            }
        }
        val jellyfin = JellyfinProvider(http.client)
        val session = session("jellyfin", "https://media.example.com/jellyfin")

        val libraries = jellyfin.libraries(session)
        jellyfin.libraryPage(session, libraries.last(), start = 0, limit = 50)

        assertEquals(listOf(ServerMediaKind.MOVIE, ServerMediaKind.MIXED), libraries.map { it.kind })
        assertEquals("Movie,Series", http.requests.last().url.parameters["includeItemTypes"])
    }

    @Test
    fun searchesEachSelectedLibraryOnJellyfin() = runTest {
        val http = TestHttp { request ->
            when (request.url.encodedPath) {
                "/jellyfin/System/Info/Public" -> """{"ServerName": "Den", "Version": "10.10.7"}"""
                else -> """{"Items": [{"Id": "m1", "Name": "Heat", "Type": "Movie"}]}"""
            }
        }
        val jellyfin = JellyfinProvider(http.client)
        val libraries = listOf(ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE), ServerLibrary("lib2", "4K", ServerMediaKind.MOVIE))

        val results = jellyfin.search(session("jellyfin", "https://media.example.com/jellyfin"), ServerMediaKind.MOVIE, libraries, "heat", 30)

        assertEquals(listOf("Heat"), results.map { it.preview.name })
        assertEquals(
            setOf("lib1", "lib2"),
            http.requests.filter { it.url.encodedPath.endsWith("/Items") }.map { it.url.parameters["parentId"] }.toSet(),
        )
    }

    @Test
    fun embySignInUsesApiRootAndEmbyHeader() = runTest {
        val http = TestHttp { request ->
            when (request.url.encodedPath) {
                "/emby/System/Info/Public" -> """{"ServerName": "Den", "Version": "4.8.10.0", "Id": "srv"}"""
                "/emby/Users/AuthenticateByName" -> """{"User": {"Id": "u1", "Name": "viewer"}, "AccessToken": "tok", "ServerId": "srv"}"""
                else -> error("Unexpected ${request.url}")
            }
        }
        val signIn = EmbyProvider(http.client).signIn("http://den.local:8096/emby/web/index.html", "viewer", "pw")

        assertEquals("http://den.local:8096", signIn.address)
        assertEquals("Den", signIn.serverName)
        assertEquals("srv", signIn.serverId)
        assertEquals("u1", signIn.userId)
        assertEquals("tok", signIn.token)
        val auth = http.requests.last()
        assertEquals(HttpMethod.Post, auth.method)
        assertTrue(auth.text.contains("\"Pw\":\"pw\""))
        val header = auth.headers["X-Emby-Authorization"].orEmpty()
        assertTrue(header.startsWith("MediaBrowser Client=\"Nuvio\""))
        assertFalse(header.contains("Token="))
        assertNull(auth.headers["Authorization"])
    }

    @Test
    fun embyRejectsJellyfinServers() = runTest {
        val http = TestHttp { """{"ServerName": "Den", "Version": "10.10.7", "ProductName": "Jellyfin Server", "Id": "srv"}""" }
        val error = assertFailsWith<ServerException> {
            EmbyProvider(http.client).signIn("http://den.local:8096", "viewer", "pw")
        }
        assertEquals(ServerFailure.UNSUPPORTED, error.failure)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun embyUsesUserScopedRoutes() = runTest {
        val http = TestHttp { request ->
            when {
                request.url.encodedPath.endsWith("/Views") ->
                    """{"Items": [{"Id": "lib1", "Name": "Movies", "CollectionType": "movies"}, {"Id": "m", "Name": "Music", "CollectionType": "music"}]}"""
                request.url.encodedPath.endsWith("/Items/Resume") -> """{"Items": []}"""
                else -> ""
            }
        }
        val emby = EmbyProvider(http.client)
        val session = session("emby", "https://media.example.com")

        val libraries = emby.libraries(session)
        emby.resumeItems(session, limit = 10)
        emby.setPlayed(session, "i1", played = true)
        emby.setPlayed(session, "i1", played = false)

        assertEquals(listOf(ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE)), libraries)
        assertEquals(
            listOf(
                "GET https://media.example.com/emby/Users/u1/Views",
                "GET https://media.example.com/emby/Users/u1/Items/Resume",
                "POST https://media.example.com/emby/Users/u1/PlayedItems/i1",
                "DELETE https://media.example.com/emby/Users/u1/PlayedItems/i1",
            ),
            http.requests.map { "${it.method.value} ${it.url.toString().substringBefore('?')}" },
        )
        http.requests.forEach { request ->
            assertTrue(request.headers["X-Emby-Authorization"].orEmpty().endsWith("Token=\"secret\""))
            assertNull(request.url.parameters["userId"])
        }
    }

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

    @Test
    fun offersEveryFormatOnlyToPlayersThatDecodeEverything() = runTest {
        val http = TestHttp {
            """{"PlaySessionId": "ps1", "MediaSources": [{"Id": "ms1", "SupportsDirectPlay": true, "TranscodingUrl": "/videos/i1/master.m3u8"}]}"""
        }
        val jellyfin = JellyfinProvider(http.client)
        val session = session("jellyfin", "https://media.example.com/jellyfin")
        val target = ServerPlaybackTarget(ServerItemRef("cabc", "i1"), mediaSourceId = "ms1")

        jellyfin.preparePlayback(session, ServerPlaybackRequest(target, ServerPlayerCapabilities(directPlayAll = true)))
        jellyfin.preparePlayback(session, ServerPlaybackRequest(target, ServerPlayerCapabilities(directPlayAll = true, allowDirectPlay = false)))
        jellyfin.preparePlayback(session, ServerPlaybackRequest(target, ServerPlayerCapabilities(directPlayAll = false)))

        val (direct, fallback, limited) = http.requests
        assertEquals("1000000000", direct.url.parameters["maxStreamingBitrate"])
        assertTrue(direct.text.contains("\"MaxStreamingBitrate\":1000000000"))
        assertTrue(direct.text.contains("\"DirectPlayProfiles\":[{\"Type\":\"Video\"}]"))
        assertFalse(fallback.text.contains("\"DirectPlayProfiles\":[{\"Type\":\"Video\"}]"))
        assertFalse(limited.text.contains("\"DirectPlayProfiles\":[{\"Type\":\"Video\"}]"))
        assertTrue(limited.text.contains("\"AudioCodec\":\"aac,mp3,ac3,eac3,flac,opus,vorbis\""))
    }

    @Test
    fun transcodesHevcAndBurnsInImageSubtitles() = runTest {
        val http = TestHttp { """{"PlaySessionId": "ps1", "MediaSources": [{"Id": "ms1", "TranscodingUrl": "/videos/i1/master.m3u8"}]}""" }
        val jellyfin = JellyfinProvider(http.client)
        val target = ServerPlaybackTarget(ServerItemRef("cabc", "i1"), mediaSourceId = "ms1")

        jellyfin.preparePlayback(
            session("jellyfin", "https://media.example.com/jellyfin"),
            ServerPlaybackRequest(target, ServerPlayerCapabilities(directPlayAll = true, allowDirectPlay = false), subtitleStreamIndex = 3),
        )

        val request = http.requests.single()
        assertEquals("3", request.url.parameters["subtitleStreamIndex"])
        assertTrue(request.text.contains("\"SubtitleStreamIndex\":3"))
        assertTrue(request.text.contains("\"VideoCodec\":\"hevc,h264\",\"AudioCodec\":\"aac,mp3,ac3\""))
        assertTrue(request.text.contains("\"BreakOnNonKeyFrames\":false"))
        assertTrue(request.text.contains("{\"Format\":\"pgssub\",\"Method\":\"Encode\"}"))
    }

    @Test
    fun embyPreparesAndReportsPlaybackThroughApiRoot() = runTest {
        val http = TestHttp { request ->
            if (request.url.encodedPath.endsWith("/PlaybackInfo")) {
                """{"PlaySessionId": "ps1", "MediaSources": [{"Id": "ms1", "SupportsDirectPlay": true}]}"""
            } else {
                ""
            }
        }
        val emby = EmbyProvider(http.client)
        val session = session("emby", "https://media.example.com")
        val playback = emby.preparePlayback(
            session,
            ServerPlaybackRequest(
                target = ServerPlaybackTarget(ServerItemRef("cabc", "i1"), mediaSourceId = "ms1"),
                capabilities = ServerPlayerCapabilities(directPlayAll = true),
                audioStreamIndex = 2,
            ),
        )
        emby.report(session, playback, ServerPlaybackEvent(ServerPlaybackEventType.PROGRESS, positionMs = 1_500, isPaused = false))

        val info = http.requests[0]
        assertEquals("/emby/Items/i1/PlaybackInfo", info.url.encodedPath)
        assertEquals("u1", info.url.parameters["userId"])
        assertEquals("ms1", info.url.parameters["mediaSourceId"])
        assertEquals("2", info.url.parameters["audioStreamIndex"])
        assertTrue(info.text.contains("\"AudioStreamIndex\":2"))
        assertTrue(info.text.contains("\"IsPlayback\":true"))
        assertTrue(info.text.contains("{\"Format\":\"subrip\",\"Method\":\"Embed\"}"))
        assertTrue(info.text.contains("{\"Format\":\"subrip\",\"Method\":\"External\"}"))
        assertTrue(playback.url.startsWith("https://media.example.com/emby/Videos/i1/stream?static=true"))
        val report = http.requests[1]
        assertEquals("/emby/Sessions/Playing/Progress", report.url.encodedPath)
        assertTrue(report.text.contains("\"PositionTicks\":15000000"))
        assertTrue(report.text.contains("\"EventName\":\"TimeUpdate\""))
        assertTrue(report.text.contains("\"PlaySessionId\":\"ps1\""))
    }
}
