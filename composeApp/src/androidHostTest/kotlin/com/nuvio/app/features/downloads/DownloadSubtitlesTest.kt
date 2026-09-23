package com.nuvio.app.features.downloads

import android.content.pm.ProviderInfo
import android.os.Bundle
import androidx.core.content.FileProvider
import com.nuvio.app.R
import com.nuvio.app.features.player.ExternalPlayerPlaybackRequest
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.player.SubtitleFileCache
import com.nuvio.app.features.player.prepareExternalPlayerLaunch
import com.nuvio.app.features.player.SubtitleAddonRequest
import com.nuvio.app.features.player.SubtitleRepository
import com.nuvio.app.features.streams.StreamSubtitle
import java.io.File
import java.net.URI
import java.util.Collections
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadSubtitlesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun backgroundDownloadSavesAddonAndStreamSubtitlesBeforeVideo(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        DownloadLocationManager.initialize(context)
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)
        registerFakeDocumentsProvider()
        val paths = Collections.synchronizedList(mutableListOf<String>())
        val playerSubtitles = SubtitleRepository.addonSubtitles.value
        val server = MockWebServer()
        server.start()
        val addonUrl = server.url("/english").toString()
        val frenchUrl = server.url("/french").toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path.orEmpty()
                return when (request.path) {
                    "/subtitles/series/tt123%3A1%3A2.json" -> MockResponse().setBody(
                        """{"subtitles":[{"id":"english","url":"$addonUrl","lang":"eng"},{"id":"french","url":"$frenchUrl","lang":"fre"}]}""",
                    )
                    "/english" -> MockResponse().setBody(srt)
                    "/french" -> if (request.getHeader("Authorization") == "Bearer subtitle") {
                        MockResponse().setBody("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nBonjour\n")
                    } else MockResponse().setResponseCode(401)
                    "/video" -> MockResponse().setBody("complete video")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val scheduler = AndroidDownloadScheduler(context)
        val item = downloadItem(server.url("/video").toString()).copy(
            fileName = "offline-episode.mkv",
            contentType = "series",
            videoId = "tt123:1:2",
            subtitleRequests = listOf(SubtitleAddonRequest(
                server.url("/subtitles/series/tt123%3A1%3A2.json").toString(), "opensubtitles", "OpenSubtitles",
            )),
            sourceSubtitles = listOf(
                StreamSubtitle(server.url("/french").toString(), "fr", "French", mapOf("Authorization" to "Bearer subtitle")),
            ),
        )
        try {
            val transfer = scheduler.store.begin(item)
            assertFalse(scheduler.execute(transfer) { })
        } finally {
            server.shutdown()
        }

        val restored = AndroidDownloadScheduler(context).restore(item)
        val uri = assertNotNull(restored.localFileUri)
        val internalUri = File(scheduler.directory, item.fileName).toURI().toString()
        val tracks = DownloadSubtitles.localSubtitles(internalUri)
        assertEquals(DownloadStatus.Completed, restored.status)
        assertTrue(uri.startsWith("content://"))
        assertEquals(setOf("en", "fr"), tracks.map { it.language }.toSet())
        assertEquals("/video", paths.last())
        assertEquals(1, paths.count { it == "/english" })
        assertEquals(1, paths.count { it == "/french" })
        assertTrue(tracks.single { it.language == "en" }.name.orEmpty().contains("OpenSubtitles"))
        assertEquals(playerSubtitles, SubtitleRepository.addonSubtitles.value)
        tracks.forEach {
            assertTrue(it.url.startsWith("file:"))
            assertTrue(it.headers.isNullOrEmpty())
            assertTrue(PlayerSubtitleCueParser.parse(File(URI(it.url)).readText(), it.url).isNotEmpty())
        }
        assertTrue(tracks.single { it.language == "fr" }.url.endsWith(".vtt"))
        withTimeout(1_000) { DownloadSubtitles.prepare(restored, internalUri) }
        assertEquals(tracks, DownloadSubtitles.localSubtitles(internalUri))

        scheduler.remove(item.fileName)
        withTimeout(5_000) {
            while (DownloadSubtitles.localSubtitles(internalUri).isNotEmpty()) delay(10)
        }
    }

    @Test
    fun unavailableAndInvalidSubtitlesDoNotFailTheVideo(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/valid.srt" -> MockResponse().setBody(srt)
                    "/invalid.srt" -> MockResponse().setBody("<html>Access denied</html>")
                    "/video" -> MockResponse().setBody("video")
                    else -> MockResponse().setResponseCode(503)
                }
            }
            val context = RuntimeEnvironment.getApplication()
            DownloadLocationManager.initialize(context)
            DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)
            registerFakeDocumentsProvider()
            val scheduler = AndroidDownloadScheduler(context)
            val item = downloadItem(server.url("/video").toString()).copy(
                fileName = "partial-subtitles.mkv",
                subtitleRequests = listOf(SubtitleAddonRequest(server.url("/failed-addon").toString(), "failed", "Failed addon")),
                sourceSubtitles = listOf("valid", "invalid", "missing").map {
                    StreamSubtitle(server.url("/$it.srt").toString(), "en", it)
                },
            )
            val transfer = scheduler.store.begin(item)
            assertFalse(scheduler.execute(transfer) { })
            val completed = assertNotNull(scheduler.store.get(item.fileName)).item
            assertEquals(DownloadStatus.Completed, completed.status)
            val internalUri = File(scheduler.directory, item.fileName).toURI().toString()
            assertEquals(listOf("valid"), DownloadSubtitles.localSubtitles(internalUri).map { it.name })
        }
    }

    @Test
    fun savedSubtitlesFollowVideoDirectoryMovesAndIgnoreMissingFiles(): Unit = runBlocking {
        val oldDirectory = temporary.newFolder("old")
        val video = File(oldDirectory, "movie.mkv")
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(srt))
            val item = downloadItem().copy(sourceSubtitles = listOf(StreamSubtitle(server.url("/sub").toString(), "en")))
            DownloadSubtitles.prepare(item, video.toURI().toString())
        }
        val newDirectory = File(temporary.root, "new")
        assertTrue(oldDirectory.renameTo(newDirectory))
        val movedUri = File(newDirectory, video.name).toURI().toString()
        val track = DownloadSubtitles.localSubtitles(movedUri).single()
        assertEquals(srt, File(URI(track.url)).readText())
        File(URI(track.url)).delete()
        assertTrue(DownloadSubtitles.localSubtitles(movedUri).isEmpty())
    }

    @Test
    fun externalPlayersReceiveReadableLocalSubtitlesWithoutAddonRequests(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val provider = ProviderInfo().apply {
            authority = "${context.packageName}.fileprovider"
            name = FileProvider::class.java.name
            packageName = context.packageName
            applicationInfo = context.applicationInfo
            grantUriPermissions = true
            metaData = Bundle().apply { putInt("android.support.FILE_PROVIDER_PATHS", R.xml.file_paths) }
        }
        shadowOf(context.packageManager).addOrUpdateProvider(provider)
        SubtitleFileCache.initialize(context)
        val uri = File(temporary.newFolder(), "movie.mkv").toURI().toString()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(srt))
            DownloadSubtitles.prepare(
                downloadItem().copy(sourceSubtitles = listOf(StreamSubtitle(server.url("/en.srt").toString(), "en", "English"))),
                uri,
            )
        }
        val permanentTrack = DownloadSubtitles.localSubtitles(uri).single()
        val request = withTimeout(2_000) {
            prepareExternalPlayerLaunch(
                request = ExternalPlayerPlaybackRequest(uri, "Offline movie"),
                type = "movie",
                videoId = "offline",
                forwardSubtitles = true,
                sendSkipSegments = false,
                preferredLanguage = "en",
                secondaryLanguage = null,
                onOverlayMessage = {},
            )
        }
        val forwarded = assertNotNull(request.subtitles).single()
        assertTrue(forwarded.url.startsWith("content://"))
        assertEquals(srt, File(context.cacheDir, "subtitles/en_English.srt").readText())
        SubtitleFileCache.clearCache()
        assertEquals(srt, File(URI(permanentTrack.url)).readText())
    }

    @Test
    fun subtitleSourcesSurvivePersistenceAndLegacyDownloadsStillDecode() {
        val legacy = downloadItem()
        val item = legacy.copy(
            subtitleRequests = listOf(SubtitleAddonRequest("https://example.com/subtitles/movie/tt1.json", "addon", "Addon")),
            sourceSubtitles = listOf(StreamSubtitle("https://example.com/en.srt", "en", headers = mapOf("Authorization" to "subtitle"))),
        )
        assertEquals(item, Json.decodeFromString<DownloadItem>(Json.encodeToString(item)))
        val decodedLegacy = Json.decodeFromString<DownloadItem>(Json.encodeToString(legacy))
        assertTrue(decodedLegacy.subtitleRequests.isEmpty())
        assertTrue(decodedLegacy.sourceSubtitles.isEmpty())
    }

    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n"
}
