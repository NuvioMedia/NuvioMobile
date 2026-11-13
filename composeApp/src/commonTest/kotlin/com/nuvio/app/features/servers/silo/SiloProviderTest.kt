package com.nuvio.app.features.servers.silo

import com.nuvio.app.features.tracking.TrackingExternalIds
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SiloProviderTest {
    private var clock = 1_000_000L
    private val address = "https://silo.example.com"

    private fun provider(route: (HttpRequestData) -> Pair<Int, String>): Pair<SiloProvider, SiloTestHttp> {
        val http = SiloTestHttp(route)
        return SiloProvider(http.client, now = { clock }, deviceId = { "device-1" }) to http
    }

    private fun ok(body: String) = 200 to body

    private fun problem(status: Int, code: String) = status to """{"type":"https://silo.example/problems/$code","title":"x"}"""

    private val credential = SiloCredential(
        access = "access-1",
        refresh = "refresh-1",
        issuedAtMs = 1_000_000L,
        accessExpiresAtMs = 1_000_000L + 8 * 3_600_000L,
        profileId = "p1",
        profileToken = "pin-token"
    )

    private fun session(credential: SiloCredential = this.credential, libraries: List<ServerLibrary> = emptyList()) = ServerSession(
        connection = ServerConnection(
            id = "c1",
            providerId = SiloProvider.ID,
            name = "Silo",
            address = address,
            remoteServerId = "server-1",
            remoteUserId = "u1",
            userName = "viewer",
            credentialRef = "k1",
            libraries = libraries,
        ),
        token = Json.encodeToString(SiloCredential.serializer(), credential)
    )

    @Test
    fun findsTheServerAndHowItSignsIn() = runTest {
        val (silo, _) = provider { request ->
            when (request.url.encodedPath) {
                "/api/v2/system/info" -> ok("""{"api_major":2}""")
                "/api/v2/system/identity" -> ok("""{"server_id":"server-1"}""")
                "/api/v2/auth/providers" -> ok("""{"password_login":true,"items":[]}""")
                "/api/v2/theme/branding" -> ok("""{"server_name":"Paul's Silo"}""")
                "/api/v2/auth/device/capability" -> ok("""{"state":"available","cancel":true}""")
                else -> error("Unexpected ${request.url}")
            }
        }

        val server = silo.findServer("silo.example.com/")

        assertEquals("http://silo.example.com", server.address)
        assertEquals("server-1", server.serverId)
        assertEquals("Paul's Silo", server.name)
        assertTrue(server.passwordSignIn)
        assertTrue(server.codeSignIn)
    }

    @Test
    fun aWebsiteThatIsNotSiloSaysSo() = runTest {
        val (silo, _) = provider { ok("<html>Welcome</html>") }

        val error = runCatching { silo.findServer(address) }.exceptionOrNull()

        assertEquals(SiloSignInProblem.NOT_SILO, SiloProvider.signInProblem(error!!))
    }

    @Test
    fun aCodeMovesItsDeadlineWhileSomeoneHasItOpen() = runTest {
        val (silo, _) = provider { request ->
            when (request.url.encodedPath) {
                "/api/v2/auth/device/start" -> ok(
                    """{"device_code":"dev","user_code":"48217730","verification_uri":"https://silo.example.com/activate",
                       "verification_uri_complete":"https://silo.example.com/activate?code=48217730","expires_in":900,
                       "expires_at":"2026-11-08T10:15:00Z","interval":3}"""
                )
                else -> ok("""{"status":"pending","opened":true,"expires_at":"2026-11-08T10:20:00Z","poll_after":5}""")
            }
        }
        val server = SiloServer(address, "server-1", "Silo", passwordSignIn = true, codeSignIn = true, cancelsCodes = true)

        val code = silo.startCode(server)
        val status = silo.pollCode(server, code) as SiloCodeStatus.Waiting

        assertEquals("4821 7730", code.shownCode)
        assertEquals(clock + 900_000L + 300_000L, status.expiresAtMs)
        assertTrue(status.opened)
        assertEquals(5_000L, status.pollAfterMs)
    }

    @Test
    fun anApprovedCodeHandsOverTheTokens() = runTest {
        val (silo, _) = provider {
            ok("""{"status":"approved","tokens":{"access_token":"a","refresh_token":"r","expires_in":3600,"user":{"id":"u1","username":"paul"}}}""")
        }
        val server = SiloServer(address, "server-1", "Silo", passwordSignIn = true, codeSignIn = true, cancelsCodes = true)
        val code = SiloCode("dev", "48217730", "", "", clock + 1_000L, null, 3_000L)

        val tokens = (silo.pollCode(server, code) as SiloCodeStatus.Approved).tokens

        assertEquals("paul", tokens.userName)
        assertEquals(clock + 3_600_000L, tokens.accessExpiresAtMs)
    }

    @Test
    fun aTemporaryPasswordIsRefusedAndTheSignInEnded() = runTest {
        val (silo, http) = provider { request ->
            when (request.url.encodedPath) {
                "/api/v2/auth/login" -> ok(
                    """{"access_token":"a","refresh_token":"r","expires_in":3600,"user":{"id":"u1","username":"paul","password_change_required":true}}"""
                )
                else -> ok("")
            }
        }
        val server = SiloServer(address, "server-1", "Silo", passwordSignIn = true, codeSignIn = false, cancelsCodes = false)

        val error = runCatching { silo.passwordSignIn(server, "paul", "secret") }.exceptionOrNull()!!

        assertEquals(SiloSignInProblem.TEMPORARY_PASSWORD, SiloProvider.signInProblem(error))
        assertEquals("Bearer a", http.requests("/api/v2/auth/logout").single().headers["Authorization"])
    }

    @Test
    fun passwordSignInSwitchedOffIsNamed() = runTest {
        val (silo, _) = provider { problem(403, "local_login_disabled") }
        val server = SiloServer(address, "server-1", "Silo", passwordSignIn = true, codeSignIn = true, cancelsCodes = true)

        val error = runCatching { silo.passwordSignIn(server, "paul", "secret") }.exceptionOrNull() as ServerException

        assertEquals(ServerFailure.FORBIDDEN, error.failure)
        assertEquals(SiloSignInProblem.PASSWORD_SIGN_IN_OFF, SiloProvider.signInProblem(error))
    }

    @Test
    fun theSavedTokenHoldsTheProfile() {
        val (silo, _) = provider { error("No request expected") }
        val server = SiloServer(address, "server-1", "Paul's Silo", passwordSignIn = true, codeSignIn = true, cancelsCodes = true)
        val tokens = SiloTokens("a", "r", clock, clock + 3_600_000L, "u1", "paul", passwordChangeNeeded = false)
        val kids = SiloProfile("p2", "Kids", hasPin = true, isChild = true, isPrimary = false)

        val signIn = silo.signIn(server, tokens, kids, "pin-token", profileCount = 2)

        assertEquals("paul · Kids", signIn.userName)
        assertEquals("u1/p2", signIn.userId)
        val saved = silo.credentialOrNull(signIn.token)!!
        assertEquals("p2", saved.profileId)
        assertEquals("pin-token", saved.profileToken)
        assertEquals("r", saved.refresh)
    }

    @Test
    fun requestsCarryTheTokenAndProfile() = runTest {
        val (silo, http) = provider {
            ok("""{"items":[
                {"id":"l1","name":"Movies","type":"movies","sort_order":2},
                {"id":"l2","name":"Shows","type":"series","sort_order":1},
                {"id":"l3","name":"Everything","type":"mixed","sort_order":3},
                {"id":"l4","name":"Music","type":"music","sort_order":4}]}""")
        }

        val libraries = silo.libraries(session())

        assertEquals(listOf("l2", "l1", "l3"), libraries.map { it.id })
        assertEquals(ServerMediaKind.MIXED, libraries.last().kind)
        val request = http.requests.single()
        assertEquals("Bearer access-1", request.headers["Authorization"])
        assertEquals("p1", request.headers["X-Profile-Id"])
        assertEquals("pin-token", request.headers["X-Profile-Token"])
    }

    @Test
    fun pagesThroughALibraryWithSilosCursors() = runTest {
        val movies = ServerLibrary("l1", "Movies", ServerMediaKind.MOVIE)
        val (silo, http) = provider { request ->
            val cursor = request.url.parameters["cursor"]
            ok(
                """{"items":[{"content_id":"movie-tmdb-${if (cursor == null) 1 else 2}","type":"movie","title":"T",
                   "poster_url":"/api/v2/artwork/p.jpg?exp=2000000000&sig=x"}],
                   "page":{"has_more":true,"next_cursor":"next-1"},"total":2,"total_exact":true,"window_cursor":"w"}"""
            )
        }

        val first = silo.libraryPage(session(), movies, start = 0, limit = 1)
        val second = silo.libraryPage(session(), movies, start = 1, limit = 1)

        assertEquals(2, first.totalCount)
        assertEquals(ServerItemRef("c1", "movie-tmdb-1").encode(), first.items.single().preview.id)
        assertEquals(TrackingExternalIds(tmdb = 1), first.items.single().externalIds)
        assertEquals("next-1", http.requests[1].url.parameters["cursor"])
        assertEquals(ServerItemRef("c1", "movie-tmdb-2").encode(), second.items.single().preview.id)
        assertEquals("movie", http.requests[0].url.parameters["type"])
        assertEquals("-added_at", http.requests[0].url.parameters["sort"])
        val poster = first.items.single().preview.poster.orEmpty()
        assertTrue(poster.startsWith("https://silo.example.com/api/v2/artwork/p.jpg?exp=2000000000"))
        assertTrue(poster.endsWith("#nuvio-silo=c1.movie-tmdb-1.poster"))
    }

    @Test
    fun aTitleNamedAfterItsIdIsOneReadAway() = runTest {
        val movies = ServerLibrary("l1", "Movies", ServerMediaKind.MOVIE)
        val (silo, http) = provider { request ->
            when (request.url.encodedPath) {
                "/api/v2/catalog/items/movie-tmdb-603" -> ok("""{"content_id":"movie-tmdb-603","type":"movie","title":"The Matrix","year":1999,"imdb_id":"tt0133093"}""")
                else -> 404 to ""
            }
        }

        val found = silo.lookupTitle(session(), movies, ServerTitleQuery(TrackingExternalIds(tmdb = 603), "The Matrix", 1999))

        assertEquals(listOf("movie-tmdb-603"), found.map { it.itemId })
        assertEquals("tt0133093", found.single().ids.imdb)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun findsAnEpisodeUnderASeriesNamedAfterItsId() = runTest {
        val (silo, _) = provider { request ->
            when (request.url.encodedPath) {
                "/api/v2/catalog/items/episode-tvdb-121361-1-2" ->
                    ok("""{"content_id":"episode-tvdb-121361-1-2","type":"episode","season_number":1,"episode_number":2,"air_date":"2011-04-24"}""")
                else -> 404 to ""
            }
        }

        val episode = silo.findEpisode(session(), "series-tvdb-121361", 1, 2)

        assertEquals("episode-tvdb-121361-1-2", episode?.itemId)
        assertEquals("2011-04-24", episode?.premiereDate)
    }

    private val playbackRoute: (HttpRequestData) -> Pair<Int, String> = { request ->
        when (request.url.encodedPath) {
            "/api/v2/playback/capabilities" -> ok(
                """{"installation_id":"inst-1","state":"available","allowed":true,"protocol_versions":[3],"features":["sequenced_progress_v1"]}"""
            )
            "/api/v2/watch/movie-tmdb-603" -> ok(
                """{"content_id":"movie-tmdb-603","versions":[{"file_id":"f1","resolution":"2160p","codec_video":"hevc","container":"mkv",
                   "video_tracks":[{"dv_profile":7,"dv_bl_compat_id":6}],
                   "audio_tracks":[{"codec":"truehd","profile":"Dolby TrueHD + Dolby Atmos","layout":"7.1","language":"eng","default":true}]}]}"""
            )
            "/api/v2/playback/start" -> ok(
                """{"outcome":"playable","session_id":"s1","playback_plan":{"plan_id":"p","plan_attempt_key":"k","delivery":"original_http",
                   "stream":{"url":"/api/v2/stream/s1/original"},
                   "selected_tracks":{"audio":{"id":"a0","index":0}},
                   "subtitle":{"inventory":[{"track_id":"t1","combined_index":3,"source":"sidecar","language":"eng","label":"English","delivery":"sidecar","url":"/api/v2/subs/t1.vtt"}]},
                   "timeline":{"timeline_offset_seconds":0,"can_seek_anywhere":true}}}"""
            )
            else -> ok("{}")
        }
    }

    private val request = ServerPlaybackRequest(
        target = ServerPlaybackTarget(ServerItemRef("c1", "movie-tmdb-603"), "f1"),
        capabilities = ServerPlayerCapabilities(directPlayAll = true)
    )

    @Test
    fun describesEachVersion() = runTest {
        val (silo, _) = provider(playbackRoute)

        val candidate = silo.candidates(session(), "movie-tmdb-603").single()

        assertEquals("2160p", candidate.title)
        assertEquals("HEVC • DV P7.6 • eng TrueHD Atmos 7.1 • MKV", candidate.description)
    }

    @Test
    fun leavesOutLanguageCodesThatAreNotALanguage() = runTest {
        val (silo, _) = provider { request ->
            if (request.url.encodedPath == "/api/v2/watch/movie-tmdb-603") {
                ok(
                    """{"content_id":"movie-tmdb-603","versions":[{"file_id":"f1","resolution":"1080p","codec_video":"h264","container":"mkv",
                       "audio_tracks":[{"codec":"eac3","layout":"5.1(side)","language":"mul","default":true}]}]}"""
                )
            } else {
                playbackRoute(request)
            }
        }

        assertEquals("AVC • EAC3 5.1 • MKV", silo.candidates(session(), "movie-tmdb-603").single().description)
    }

    @Test
    fun startsPlaybackFromSilosPlan() = runTest {
        val (silo, http) = provider(playbackRoute)

        val playback = silo.preparePlayback(session(), request)

        assertEquals(ServerPlayMethod.DIRECT_PLAY, playback.playMethod)
        assertEquals("https://silo.example.com/api/v2/stream/s1/original", playback.url)
        assertEquals("Bearer access-1", playback.headers["Authorization"])
        assertEquals("https://silo.example.com/api/v2/subs/t1.vtt", playback.subtitles.single().url)
        assertEquals(listOf(0 to true), playback.audioTracks.map { it.index to it.selected })
        val start = Json.parseToJsonElement(http.requests("/api/v2/playback/start").single().text).jsonObject
        assertEquals("inst-1", start["installation_id"]?.jsonPrimitive?.content)
        assertEquals("f1", start["file_id"]?.jsonPrimitive?.content)
        assertEquals("p1", start["profile_id"]?.jsonPrimitive?.content)
        assertEquals("auto", start["quality_preference"]?.jsonPrimitive?.content)
    }

    @Test
    fun stopsThePlayWithItsSequenceAndPosition() = runTest {
        val (silo, http) = provider(playbackRoute)
        val playback = silo.preparePlayback(session(), request)

        silo.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.PROGRESS, 10_000L, isPaused = false))
        silo.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.STOP, 20_000L, isPaused = true))

        val progress = Json.parseToJsonElement(http.requests("/api/v2/playback/s1/progress").single().text).jsonObject
        val stop = http.requests("/api/v2/playback/s1").single()
        val stopBody = Json.parseToJsonElement(stop.text).jsonObject
        assertEquals("1", progress["sequence"]?.jsonPrimitive?.content)
        assertEquals(HttpMethod.Delete, stop.method)
        assertEquals("2", stopBody["sequence"]?.jsonPrimitive?.content)
        assertEquals("20.0", stopBody["position"]?.jsonPrimitive?.content)
    }

    @Test
    fun aRefusedPlayFails() = runTest {
        val (silo, _) = provider { request ->
            if (request.url.encodedPath == "/api/v2/playback/start") {
                ok("""{"outcome":"terminal","terminal":{"reason":"stream_limit_reached","message":"Too many streams"}}""")
            } else {
                playbackRoute(request)
            }
        }

        val error = runCatching { silo.preparePlayback(session(), request) }.exceptionOrNull() as ServerException

        assertEquals(ServerFailure.UNSUPPORTED, error.failure)
    }

    @Test
    fun renewsTheTokenOnceHalfItsLifeHasPassed() {
        val (silo, _) = provider { error("No request expected") }

        assertFalse(silo.renewalDue(session()))
        clock += 4 * 3_600_000L
        assertTrue(silo.renewalDue(session()))
    }

    @Test
    fun renewalKeepsTheProfileAndStoresTheNewPair() = runTest {
        val (silo, http) = provider { ok("""{"access_token":"access-2","refresh_token":"refresh-2","expires_in":28800}""") }

        val renewed = silo.credentialOrNull(silo.renewToken(session(), null)!!)!!

        assertEquals("access-2", renewed.access)
        assertEquals("refresh-2", renewed.refresh)
        assertEquals("p1", renewed.profileId)
        assertEquals("pin-token", renewed.profileToken)
        assertEquals(clock + 28_800_000L, renewed.accessExpiresAtMs)
        assertTrue(http.requests.single().text.contains("refresh-1"))
    }

    @Test
    fun anEndedSignInCannotBeRenewed() = runTest {
        val (silo, _) = provider { problem(401, "session_expired") }

        val error = runCatching { silo.renewToken(session(), null) }.exceptionOrNull() as ServerException

        assertEquals(ServerFailure.AUTH_REQUIRED, error.failure)
    }

    @Test
    fun aProfileThatNeedsItsPinAgainAsksForANewSignIn() = runTest {
        val (silo, _) = provider { problem(403, "profile_verification_required") }

        val error = runCatching { silo.libraries(session()) }.exceptionOrNull() as ServerException

        assertEquals(ServerFailure.AUTH_REQUIRED, error.failure)
    }

    @Test
    fun theAccountCopyLeavesOutTheAccessToken() {
        val (silo, _) = provider { error("No request expected") }

        val copy = silo.credentialOrNull(silo.tokenForAccount(session().token))!!

        assertEquals("", copy.access)
        assertEquals("refresh-1", copy.refresh)
        assertEquals("p1", copy.profileId)
        assertEquals("pin-token", copy.profileToken)
    }

    @Test
    fun aCopyWithoutAnAccessTokenRenewsStraightAway() {
        val (silo, _) = provider { error("No request expected") }
        val copy = silo.tokenForAccount(session().token)

        assertTrue(silo.renewalDue(session().let { ServerSession(it.connection, copy) }))
    }

    @Test
    fun theDeviceKeepsItsOwnTokenForTheSameSignIn() {
        val (silo, _) = provider { error("No request expected") }
        val local = Json.encodeToString(SiloCredential.serializer(), credential.copy(signInId = "a"))
        val same = Json.encodeToString(SiloCredential.serializer(), credential.copy(access = "", signInId = "a"))
        val newer = Json.encodeToString(SiloCredential.serializer(), credential.copy(signInId = "b"))

        assertTrue(silo.keepsLocalToken(local, same))
        assertFalse(silo.keepsLocalToken(local, newer))
    }

    @Test
    fun renewalsReachTheAccountAboutOnceAWeek() = runTest {
        val (silo, _) = provider { ok("""{"access_token":"access-2","refresh_token":"refresh-2","expires_in":28800}""") }
        val before = session(credential.copy(syncedAtMs = clock))

        val soon = silo.renewToken(before, null)!!
        assertFalse(silo.renewalChangesAccount(before.token, soon))

        clock += 8 * 24 * 3_600_000L
        val later = silo.renewToken(before, null)!!
        assertTrue(silo.renewalChangesAccount(before.token, later))
    }

    @Test
    fun readsIdsFromContentIds() {
        assertEquals(TrackingExternalIds(tmdb = 603), contentIdExternalIds("movie-tmdb-603"))
        assertEquals(TrackingExternalIds(tvdb = "121361"), contentIdExternalIds("series-tvdb-121361"))
        assertEquals(TrackingExternalIds(imdb = "tt0133093"), contentIdExternalIds("movie-imdb-tt0133093"))
        assertEquals(TrackingExternalIds(), contentIdExternalIds("movie-local-42"))
        assertNull(episodeContentId("movie-tmdb-603", 1, 1))
    }

    @Test
    fun asksSiloAsAPhone() = runTest {
        val (silo, http) = provider(playbackRoute)

        silo.preparePlayback(session(), request)

        val start = Json.parseToJsonElement(http.requests("/api/v2/playback/start").single().text).jsonObject
        val context = start["client_playback_context"]!!.jsonObject
        assertEquals("mobile", context["form_factor"]?.jsonPrimitive?.content)
        assertEquals("android", context["device"]!!.jsonObject["platform"]?.jsonPrimitive?.content)
    }

    @Test
    fun exoPlayerOnlyClaimsWhatItPlays() = runTest {
        val (silo, http) = provider(playbackRoute)

        silo.preparePlayback(session(), request.copy(capabilities = ServerPlayerCapabilities(directPlayAll = false)))

        val start = Json.parseToJsonElement(http.requests("/api/v2/playback/start").single().text).jsonObject
        val audio = start["client_capabilities"]!!.jsonObject["codecs_audio"].toString()
        assertTrue("eac3" in audio)
        assertFalse("truehd" in audio)
        assertFalse("dts" in audio)
    }
}
