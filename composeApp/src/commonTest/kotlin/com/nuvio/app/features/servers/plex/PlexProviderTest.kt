package com.nuvio.app.features.servers.plex

import com.nuvio.app.features.servers.ServerConnection
import com.nuvio.app.features.servers.ServerException
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.ServerItemRef
import com.nuvio.app.features.servers.ServerLibrary
import com.nuvio.app.features.servers.ServerMediaKind
import com.nuvio.app.features.servers.ServerPlayMethod
import com.nuvio.app.features.servers.ServerPlaybackEvent
import com.nuvio.app.features.servers.ServerPlaybackEventType
import com.nuvio.app.features.servers.ServerPlaybackRequest
import com.nuvio.app.features.servers.ServerPlaybackTarget
import com.nuvio.app.features.servers.ServerPlayerCapabilities
import com.nuvio.app.features.servers.ServerSession
import com.nuvio.app.features.servers.ServerTitleQuery
import com.nuvio.app.features.tracking.TrackingExternalIds
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlexProviderTest {
    private val movies = ServerLibrary("1", "Movies", ServerMediaKind.MOVIE)
    private val shows = ServerLibrary("2", "TV Shows", ServerMediaKind.SERIES)
    private val session = ServerSession(
        connection = ServerConnection(
            id = "c1",
            providerId = PlexProvider.ID,
            name = "Home",
            address = "http://192.168.1.20:32400",
            remoteServerId = "machine-1",
            remoteUserId = "1",
            userName = "viewer",
            credentialRef = "k1",
            libraries = listOf(movies, shows),
        ),
        token = "server-token",
    )

    private fun provider(route: (HttpRequestData) -> Pair<Int, String>): Pair<PlexProvider, PlexTestHttp> {
        val http = PlexTestHttp(route)
        return plexProvider(http) to http
    }

    private fun ok(body: String) = 200 to body

    @Test
    fun listsMovieAndShowLibrariesOnly() = runTest {
        val (plex, http) = provider {
            ok(
                """{"MediaContainer":{"Directory":[
                {"key":"1","title":"Movies","type":"movie"},
                {"key":"2","title":"TV Shows","type":"show"},
                {"key":"3","title":"Music","type":"artist"}]}}""",
            )
        }

        val libraries = plex.libraries(session)

        assertEquals(listOf(movies, shows), libraries)
        assertEquals("server-token", http.requests.single().headers["X-Plex-Token"])
        assertEquals("device-1", http.requests.single().headers["X-Plex-Client-Identifier"])
    }

    @Test
    fun readsLibraryPagesWithNumericTagIdsAndUnsignedArtwork() = runTest {
        val (plex, http) = provider {
            ok(
                """{"MediaContainer":{"totalSize":120,"Metadata":[{
                "ratingKey":"10","type":"movie","title":"The Matrix","year":1999,"thumb":"/library/metadata/10/thumb/1",
                "art":"/library/metadata/10/art/1","audienceRating":8.66,
                "Genre":[{"id":7,"tag":"Action"}],
                "Guid":[{"id":"imdb://tt0133093"},{"id":"tmdb://603"},{"id":"tvdb://169"}]}]}}""",
            )
        }

        val page = plex.libraryPage(session, movies, start = 50, limit = 50)

        val title = page.items.single()
        assertEquals(120, page.totalCount)
        assertEquals(ServerItemRef("c1", "10").encode(), title.preview.id)
        assertEquals("movie", title.preview.type)
        assertEquals(listOf("Action"), title.preview.genres)
        assertEquals("8.7", title.preview.imdbRating)
        assertEquals(TrackingExternalIds(imdb = "tt0133093", tmdb = 603, tvdb = "169"), title.externalIds)
        val poster = title.preview.poster.orEmpty()
        assertTrue(poster.startsWith("http://192.168.1.20:32400/photo/:/transcode?"))
        assertTrue(poster.endsWith("#nuvio-plex=c1"))
        assertFalse(poster.contains("server-token"))
        val url = http.requests.single().url
        assertEquals("/library/sections/1/all", url.encodedPath)
        assertEquals("1", url.parameters["type"])
        assertEquals("50", url.parameters["X-Plex-Container-Start"])
        assertEquals("addedAt:desc", url.parameters["sort"])
    }

    @Test
    fun showDetailsCarryEveryEpisodeAndItsWatchState() = runTest {
        val (plex, _) = provider { request ->
            when (request.url.encodedPath) {
                "/library/metadata/20" ->
                    ok("""{"MediaContainer":{"Metadata":[{"ratingKey":"20","type":"show","title":"Severance"}]}}""")
                "/library/metadata/20/allLeaves" -> ok(
                    """{"MediaContainer":{"Metadata":[
                    {"ratingKey":"21","type":"episode","title":"Good News","parentIndex":1,"index":1,"duration":3000000,"viewCount":1},
                    {"ratingKey":"22","type":"episode","title":"Half Loop","parentIndex":1,"index":2,"duration":3000000,"viewOffset":60000,"lastViewedAt":1700000000}]}}""",
                )
                else -> error("Unexpected ${request.url}")
            }
        }

        val details = plex.details(session, "20")

        assertEquals("series", details.meta.type)
        assertEquals(listOf(1 to 1, 1 to 2), details.meta.videos.map { it.season to it.episode })
        val (first, second) = details.userStates
        assertTrue(first.played)
        assertFalse(second.played)
        assertEquals(60_000L, second.positionMs)
        assertEquals(1_700_000_000_000L, second.lastPlayedEpochMs)
    }

    @Test
    fun resumeRowShowsStartedItemsFromChosenLibraries() = runTest {
        val (plex, _) = provider {
            ok(
                """{"MediaContainer":{"Metadata":[
                {"ratingKey":"31","type":"episode","title":"Pilot","grandparentRatingKey":"30","grandparentTitle":"Lost","viewOffset":5000,"librarySectionID":2},
                {"ratingKey":"40","type":"movie","title":"Heat","viewOffset":0,"librarySectionID":1},
                {"ratingKey":"50","type":"movie","title":"Other","viewOffset":9000,"librarySectionID":9}]}}""",
            )
        }

        val resume = plex.resumeItems(session, limit = 10)

        assertEquals(listOf(ServerItemRef("c1", "30").encode()), resume.map { it.preview.id })
        assertEquals("Lost", resume.single().preview.name)
    }

    @Test
    fun findsATitleByNameAndChecksItsIds() = runTest {
        val (plex, http) = provider {
            ok(
                """{"MediaContainer":{"Metadata":[
                {"ratingKey":"10","type":"movie","title":"The Matrix","year":1999,"Guid":[{"id":"tmdb://603"}]},
                {"ratingKey":"11","type":"movie","title":"The Matrix Reloaded","year":2003,"Guid":[{"id":"tmdb://604"}]}]}}""",
            )
        }

        val found = plex.lookupTitle(session, movies, ServerTitleQuery(TrackingExternalIds(tmdb = 603), "The Matrix", 1999))

        assertEquals(listOf("10", "11"), found?.map { it.itemId })
        assertEquals("1999", http.requests.single().url.parameters["year"])
    }

    @Test
    fun findsAnEpisodeByItsNumbers() = runTest {
        val (plex, _) = provider {
            ok(
                """{"MediaContainer":{"Metadata":[
                {"ratingKey":"21","type":"episode","parentIndex":1,"index":1,"originallyAvailableAt":"2022-02-18"},
                {"ratingKey":"22","type":"episode","parentIndex":1,"index":2}]}}""",
            )
        }

        assertEquals("21", plex.findEpisode(session, "20", 1, 1)?.itemId)
        assertEquals("2022-02-18", plex.findEpisode(session, "20", 1, 1)?.premiereDate)
        assertNull(plex.findEpisode(session, "20", 2, 1))
    }

    @Test
    fun marksWatchedWithTheLibraryScrobble() = runTest {
        val (plex, http) = provider { ok("") }

        plex.setPlayed(session, "10", played = true)
        plex.setPlayed(session, "10", played = false)

        assertEquals(listOf("/:/scrobble", "/:/unscrobble"), http.requests.map { it.url.encodedPath })
        assertEquals("10", http.requests.first().url.parameters["key"])
        assertEquals("com.plexapp.plugins.library", http.requests.first().url.parameters["identifier"])
    }

    private val movieItem = """{"MediaContainer":{"Metadata":[{"ratingKey":"10","key":"/library/metadata/10","type":"movie","title":"Heat","duration":600000,
        "Media":[{"id":1,"videoResolution":"4k","videoCodec":"hevc","audioCodec":"truehd","audioChannels":8,"container":"mkv",
          "Part":[{"id":5,"key":"/library/parts/5/123/file.mkv","file":"/media/Heat.mkv","size":1000,
            "Stream":[
              {"id":100,"streamType":2,"codec":"truehd","languageCode":"eng","displayTitle":"English (TRUEHD 7.1)","selected":true},
              {"id":101,"streamType":3,"codec":"srt","key":"/library/streams/101","languageCode":"eng","displayTitle":"English (SRT External)"},
              {"id":102,"streamType":3,"codec":"pgs","languageCode":"fre","displayTitle":"Français (PGS)"}]}]}]}]}}"""

    private val request = ServerPlaybackRequest(
        target = ServerPlaybackTarget(ServerItemRef("c1", "10"), "5"),
        capabilities = ServerPlayerCapabilities(directPlayAll = true),
    )

    @Test
    fun describesEachVersion() = runTest {
        val (plex, _) = provider { ok(movieItem) }

        val candidate = plex.candidates(session, "10").single()

        assertEquals("5", candidate.target.mediaSourceId)
        assertEquals("4K", candidate.title)
        assertEquals("HEVC • TrueHD 7.1 • MKV", candidate.description)
        assertEquals("Heat.mkv", candidate.filename)
    }

    @Test
    fun namesDtsByItsUsualName() = runTest {
        val (plex, _) = provider {
            ok(movieItem.replace("\"audioCodec\":\"truehd\",\"audioChannels\":8", "\"audioCodec\":\"dca\",\"audioChannels\":6"))
        }

        assertEquals("HEVC • DTS 5.1 • MKV", plex.candidates(session, "10").single().description)
    }

    @Test
    fun directPlayStreamsThePartWithTheTokenInHeaders() = runTest {
        val (plex, http) = provider { request ->
            if (request.url.encodedPath.startsWith("/library/parts/")) 206 to "x" else ok(movieItem)
        }

        val playback = plex.preparePlayback(session, request)

        assertEquals(ServerPlayMethod.DIRECT_PLAY, playback.playMethod)
        assertTrue(playback.url.startsWith("http://192.168.1.20:32400/library/parts/5/123/file.mkv?"))
        assertEquals(playback.playSessionId, Url(playback.url).parameters["X-Plex-Session-Identifier"])
        assertEquals(32, playback.playSessionId?.length)
        assertEquals("server-token", playback.headers["X-Plex-Token"])
        assertFalse(playback.url.contains("server-token"))
        val subtitle = playback.subtitles.single()
        assertEquals("http://192.168.1.20:32400/library/streams/101", subtitle.url)
        assertEquals("eng", subtitle.language)
        assertEquals("server-token", subtitle.headers?.get("X-Plex-Token"))
        assertEquals("bytes=0-0", http.requests("/library/parts/5/123/file.mkv").single().headers["Range"])
    }

    @Test
    fun aFormatExoPlayerCannotPlayGoesStraightToPlexsConversion() = runTest {
        val (plex, http) = provider { request ->
            when (request.url.encodedPath) {
                "/video/:/transcode/universal/decision" ->
                    ok("""{"MediaContainer":{"generalDecisionCode":1001,"transcodeDecisionText":"Audio is transcoding"}}""")
                else -> ok(movieItem)
            }
        }

        val playback = plex.preparePlayback(session, request.copy(capabilities = ServerPlayerCapabilities(directPlayAll = false)))

        assertEquals(ServerPlayMethod.TRANSCODE, playback.playMethod)
        assertTrue(http.requests.none { it.url.encodedPath.startsWith("/library/parts/") })
    }

    @Test
    fun aFormatExoPlayerCanPlayIsPlayedDirectly() = runTest {
        val (plex, _) = provider { request ->
            if (request.url.encodedPath.startsWith("/library/parts/")) {
                206 to "x"
            } else {
                ok(movieItem.replace("\"audioCodec\":\"truehd\"", "\"audioCodec\":\"eac3\""))
            }
        }

        val playback = plex.preparePlayback(session, request.copy(capabilities = ServerPlayerCapabilities(directPlayAll = false)))

        assertEquals(ServerPlayMethod.DIRECT_PLAY, playback.playMethod)
    }

    @Test
    fun aRefusedFileMeansPlexBlocksPlayingAwayFromHome() = runTest {
        val (plex, _) = provider { request ->
            if (request.url.encodedPath.startsWith("/library/parts/")) 403 to "" else ok(movieItem)
        }

        val error = runCatching { plex.preparePlayback(session, request) }.exceptionOrNull() as ServerException

        assertEquals(ServerFailure.REMOTE_PLAYBACK_BLOCKED, error.failure)
    }

    @Test
    fun fallbackAsksPlexToConvertAndStopsTheConversionAtTheEnd() = runTest {
        val (plex, http) = provider { request ->
            when (request.url.encodedPath) {
                "/video/:/transcode/universal/decision" ->
                    ok("""{"MediaContainer":{"generalDecisionCode":1001,"transcodeDecisionText":"Video is transcoding"}}""")
                "/library/parts/5" -> ok("")
                "/:/timeline", "/video/:/transcode/universal/stop" -> ok("")
                else -> ok(movieItem)
            }
        }

        val playback = plex.preparePlayback(
            session,
            request.copy(
                capabilities = ServerPlayerCapabilities(directPlayAll = true, allowDirectPlay = false),
                subtitleStreamIndex = 102,
            ),
        )
        plex.report(session, playback, ServerPlaybackEvent(ServerPlaybackEventType.STOP, positionMs = 120_000L, isPaused = true))

        assertEquals(ServerPlayMethod.TRANSCODE, playback.playMethod)
        assertTrue(playback.url.startsWith("http://192.168.1.20:32400/video/:/transcode/universal/start.m3u8?"))
        assertEquals(listOf("Video is transcoding"), playback.transcodeReasons)
        assertEquals(listOf(101 to false, 102 to true), playback.burnInSubtitles.map { it.index to it.selected })
        val choice = http.requests("/library/parts/5").single()
        assertEquals(HttpMethod.Put, choice.method)
        assertEquals("102", choice.url.parameters["subtitleStreamID"])
        val timeline = http.requests("/:/timeline").single().url
        assertEquals("stopped", timeline.parameters["state"])
        assertEquals("120000", timeline.parameters["time"])
        assertEquals(
            playback.playSessionId,
            http.requests("/video/:/transcode/universal/stop").single().url.parameters["session"],
        )
    }

    @Test
    fun aConversionThatCopiesTheVideoIsADirectStream() = runTest {
        val (plex, _) = provider { request ->
            if (request.url.encodedPath == "/video/:/transcode/universal/decision") {
                ok(
                    """{"MediaContainer":{"generalDecisionCode":1001,"transcodeDecisionText":"Direct play not available; Conversion OK.",
                    "Metadata":[{"Media":[{"Part":[{"Stream":[{"streamType":1,"decision":"copy"},{"streamType":2,"decision":"transcode"}]}]}]}]}}""",
                )
            } else {
                ok(movieItem)
            }
        }

        val playback = plex.preparePlayback(
            session,
            request.copy(capabilities = ServerPlayerCapabilities(directPlayAll = true, allowDirectPlay = false)),
        )

        assertEquals(ServerPlayMethod.DIRECT_STREAM, playback.playMethod)
        assertTrue(playback.transcodeReasons.isEmpty())
    }

    @Test
    fun aRefusedConversionFails() = runTest {
        val (plex, _) = provider { request ->
            if (request.url.encodedPath == "/video/:/transcode/universal/decision") {
                ok("""{"MediaContainer":{"generalDecisionCode":2000,"generalDecisionText":"Not allowed"}}""")
            } else {
                ok(movieItem)
            }
        }

        val error = runCatching {
            plex.preparePlayback(
                session,
                request.copy(capabilities = ServerPlayerCapabilities(directPlayAll = true, allowDirectPlay = false)),
            )
        }.exceptionOrNull() as ServerException

        assertEquals(ServerFailure.UNSUPPORTED, error.failure)
    }

    @Test
    fun readsIdsFromOlderAgents() {
        assertEquals(TrackingExternalIds(imdb = "tt0133093"), legacyGuidIds("com.plexapp.agents.imdb://tt0133093?lang=en"))
        assertEquals(TrackingExternalIds(tmdb = 603), legacyGuidIds("com.plexapp.agents.themoviedb://603?lang=en"))
        assertEquals(TrackingExternalIds(tvdb = "121361"), legacyGuidIds("com.plexapp.agents.thetvdb://121361?lang=en"))
        assertEquals(TrackingExternalIds(anidb = 23), legacyGuidIds("com.plexapp.agents.hama://anidb-23?lang=en"))
        assertEquals(TrackingExternalIds(), legacyGuidIds("plex://movie/5d776825880197001ec967c6"))
    }
}
