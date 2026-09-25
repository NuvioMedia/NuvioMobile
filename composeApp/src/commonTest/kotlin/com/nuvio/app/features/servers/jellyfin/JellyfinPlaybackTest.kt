package com.nuvio.app.features.servers.jellyfin

import com.nuvio.app.features.servers.ServerConnection
import com.nuvio.app.features.servers.ServerException
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.ServerItemRef
import com.nuvio.app.features.servers.ServerPlayMethod
import com.nuvio.app.features.servers.ServerPlaybackRequest
import com.nuvio.app.features.servers.ServerPlaybackTarget
import com.nuvio.app.features.servers.ServerPlayerCapabilities
import com.nuvio.app.features.servers.ServerSession
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JellyfinPlaybackTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val session = ServerSession(
        connection = ServerConnection(
            id = "cabc",
            providerId = "jellyfin",
            name = "Home",
            address = "https://media.example.com/jellyfin",
            remoteServerId = "s1",
            remoteUserId = "u1",
            userName = "viewer",
            credentialRef = "k1",
        ),
        token = "secret",
    )
    private val request = ServerPlaybackRequest(
        target = ServerPlaybackTarget(ServerItemRef("cabc", "item1"), mediaSourceId = "ms1"),
        capabilities = ServerPlayerCapabilities(directPlayAll = true),
    )

    private fun info(source: String) = json.decodeFromString(
        JellyfinPlaybackInfoResult.serializer(),
        """{"PlaySessionId": "ps1", "MediaSources": [$source]}""",
    )

    @Test
    fun directPlayUsesStaticStreamOnOwningServer() {
        val playback = JellyfinProvider.playbackSession(
            session,
            request,
            info(
                """{"Id": "ms1", "SupportsDirectPlay": true, "ETag": "e1",
                   "MediaStreams": [{"Type": "Subtitle", "Language": "eng", "DisplayTitle": "English", "DeliveryMethod": "External",
                                     "DeliveryUrl": "/Videos/item1/ms1/Subtitles/3/0/Stream.srt"}]}""",
            ),
            deviceId = "d1",
        )
        assertEquals(ServerPlayMethod.DIRECT_PLAY, playback.playMethod)
        assertTrue(playback.url.startsWith("https://media.example.com/jellyfin/Videos/item1/stream?static=true&mediaSourceId=ms1&playSessionId=ps1"))
        assertEquals("ps1", playback.playSessionId)
        assertEquals("https://media.example.com/jellyfin/Videos/item1/ms1/Subtitles/3/0/Stream.srt?api_key=secret", playback.subtitles.single().url)
        assertEquals("eng", playback.subtitles.single().language)
    }

    @Test
    fun fallsBackToServerTranscodeWithoutDuplicatingKey() {
        val playback = JellyfinProvider.playbackSession(
            session,
            request.copy(capabilities = ServerPlayerCapabilities(directPlayAll = false, allowDirectPlay = false)),
            info("""{"Id": "ms1", "SupportsDirectPlay": true, "TranscodingUrl": "/videos/item1/master.m3u8?MediaSourceId=ms1&ApiKey=secret"}"""),
            deviceId = "d1",
        )
        assertEquals(ServerPlayMethod.TRANSCODE, playback.playMethod)
        assertEquals("https://media.example.com/jellyfin/videos/item1/master.m3u8?MediaSourceId=ms1&ApiKey=secret", playback.url)
    }

    @Test
    fun marksRemuxAsDirectStream() {
        val playback = JellyfinProvider.playbackSession(
            session,
            request,
            info("""{"Id": "ms1", "SupportsDirectStream": true, "TranscodingUrl": "/videos/item1/master.m3u8?MediaSourceId=ms1"}"""),
            deviceId = "d1",
        )
        assertEquals(ServerPlayMethod.DIRECT_STREAM, playback.playMethod)
        assertTrue(playback.url.endsWith("&api_key=secret"))
    }

    @Test
    fun reportsPermissionAndCompatibilityFailures() {
        val denied = assertFailsWith<ServerException> {
            JellyfinProvider.playbackSession(session, request, json.decodeFromString(JellyfinPlaybackInfoResult.serializer(), """{"ErrorCode": "NotAllowed"}"""), "d1")
        }
        assertEquals(ServerFailure.FORBIDDEN, denied.failure)
        val unsupported = assertFailsWith<ServerException> {
            JellyfinProvider.playbackSession(session, request, info("""{"Id": "ms1"}"""), "d1")
        }
        assertEquals(ServerFailure.UNSUPPORTED, unsupported.failure)
    }
}
