package com.nuvio.app.features.player

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MatroskaChapterLoaderTest {

    private fun idBytes(id: Long): ByteArray {
        var width = 1
        while (width < 4 && id ushr (width * 8) != 0L) width++
        return ByteArray(width) { i -> (id ushr ((width - 1 - i) * 8)).toByte() }
    }

    /** Size as an 8-byte EBML vint, valid for any test payload. */
    private fun size(value: Long): ByteArray =
        ByteArray(8) { i -> if (i == 0) 0x01 else (value ushr ((7 - i) * 8)).toByte() }

    private fun element(id: Long, vararg children: ByteArray): ByteArray {
        val payload = children.fold(ByteArray(0)) { acc, child -> acc + child }
        return idBytes(id) + size(payload.size.toLong()) + payload
    }

    private fun uint(id: Long, value: Long): ByteArray {
        var width = 1
        while (width < 8 && value ushr (width * 8) != 0L) width++
        return element(id, ByteArray(width) { i -> (value ushr ((width - 1 - i) * 8)).toByte() })
    }

    private fun text(id: Long, value: String): ByteArray = element(id, value.encodeToByteArray())

    private fun atom(startMs: Long, title: String): ByteArray =
        element(
            0xB6L,
            uint(0x91L, startMs * 1_000_000L),
            element(0x80L, text(0x85L, title), text(0x437CL, "eng")),
        )

    private fun chaptersElement(vararg atoms: ByteArray): ByteArray =
        element(MatroskaChapterParser.ID_CHAPTERS, element(0x45B9L, *atoms))

    private val ebml = element(0x1A45DFA3L, uint(0x4282L, 0L))
    private val info = element(0x1549A966L, uint(0x2AD7B1L, 1_000_000L))
    private val cluster = element(MatroskaChapterParser.ID_CLUSTER, uint(0xE7L, 0L))

    /** A Segment of unknown size, as in streamed files. */
    private fun segment(payload: ByteArray): ByteArray =
        idBytes(MatroskaChapterParser.ID_SEGMENT) + byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1) + payload

    private fun mkvWithInlineChapters(): ByteArray =
        ebml + segment(info + chaptersElement(atom(0L, "Intro"), atom(90_000L, "Episode")) + cluster)

    private fun mkvWithoutChapters(): ByteArray = ebml + segment(info + cluster)

    private class RemoteChaptersMkv(val head: ByteArray, val chaptersOffset: Long, val chapters: ByteArray)

    /** Chapters far past the head of the file, found through the SeekHead. */
    private fun mkvWithRemoteChapters(): RemoteChaptersMkv {
        val relativeOffset = 1_000_000L
        val seek = element(
            MatroskaChapterParser.ID_SEEK,
            element(MatroskaChapterParser.ID_SEEK_ID, idBytes(MatroskaChapterParser.ID_CHAPTERS)),
            uint(MatroskaChapterParser.ID_SEEK_POSITION, relativeOffset),
        )
        val payload = element(MatroskaChapterParser.ID_SEEK_HEAD, seek) + cluster
        val head = ebml + segment(payload)
        return RemoteChaptersMkv(
            head = head,
            chaptersOffset = (head.size - payload.size) + relativeOffset,
            chapters = chaptersElement(atom(0L, "Cold Open"), atom(120_000L, "Opening")),
        )
    }

    private fun load(
        url: String = "https://host.example.com/stream.mkv",
        headers: Map<String, String> = emptyMap(),
        retryDelaysMs: List<Long> = listOf(0L, 0L),
        fetch: suspend (url: String, start: Long) -> MatroskaChapterLoader.RangeFetchResult,
    ): List<PlayerChapter> = runBlocking {
        MatroskaChapterLoader.loadWithFetcher(url, headers, retryDelaysMs) { requestUrl, _, start, _ ->
            fetch(requestUrl, start)
        }
    }

    @Test
    fun aTransientFailureIsReadAgain() {
        val mkv = mkvWithInlineChapters()
        var attempts = 0
        val result = load { url, _ ->
            attempts++
            if (attempts == 1) {
                MatroskaChapterLoader.RangeFetchResult.TransientFailure("http_429")
            } else {
                MatroskaChapterLoader.RangeFetchResult.Success(mkv, url)
            }
        }
        assertEquals(2, attempts)
        assertEquals(listOf("Intro", "Episode"), result.map { it.title })
    }

    @Test
    fun aFileWithoutChaptersIsReadOnce() {
        val mkv = mkvWithoutChapters()
        var attempts = 0
        val result = load { url, _ ->
            attempts++
            MatroskaChapterLoader.RangeFetchResult.Success(mkv, url)
        }
        assertTrue(result.isEmpty())
        assertEquals(1, attempts)
    }

    @Test
    fun aPermanentHttpErrorIsNotReadAgain() {
        var attempts = 0
        val result = load { _, _ ->
            attempts++
            MatroskaChapterLoader.RangeFetchResult.PermanentFailure("http_404")
        }
        assertTrue(result.isEmpty())
        assertEquals(1, attempts)
    }

    @Test
    fun aParseFailureIsNotReadAgain() {
        var attempts = 0
        val result = load { _, _ ->
            attempts++
            throw IllegalStateException("malformed element")
        }
        assertTrue(result.isEmpty())
        assertEquals(1, attempts)
    }

    @Test
    fun aThrownIoErrorIsReadAgain() {
        val mkv = mkvWithInlineChapters()
        var attempts = 0
        val result = load { url, _ ->
            attempts++
            if (attempts == 1) throw IOException("connection reset")
            MatroskaChapterLoader.RangeFetchResult.Success(mkv, url)
        }
        assertEquals(2, attempts)
        assertEquals(listOf("Intro", "Episode"), result.map { it.title })
    }

    @Test
    fun itStopsAfterTheLastAttempt() {
        var attempts = 0
        val result = load { _, _ ->
            attempts++
            MatroskaChapterLoader.RangeFetchResult.TransientFailure("timeout")
        }
        assertTrue(result.isEmpty())
        assertEquals(3, attempts)
    }

    @Test
    fun followUpRangesGoToTheRedirectTarget() {
        val remote = mkvWithRemoteChapters()
        val originalUrl = "https://addon.example.com/redirect/ep2.mkv"
        val resolvedUrl = "https://cdn.example.net/files/ep2.mkv"
        val requested = mutableListOf<String>()
        val result = load(url = originalUrl, retryDelaysMs = emptyList()) { url, start ->
            requested += url
            when (start) {
                0L -> MatroskaChapterLoader.RangeFetchResult.Success(remote.head, resolvedUrl)
                remote.chaptersOffset -> MatroskaChapterLoader.RangeFetchResult.Success(remote.chapters, resolvedUrl)
                else -> MatroskaChapterLoader.RangeFetchResult.PermanentFailure("unexpected_offset_$start")
            }
        }
        assertEquals(listOf("Cold Open", "Opening"), result.map { it.title })
        assertEquals(listOf(originalUrl, resolvedUrl, resolvedUrl), requested)
    }

    @Test
    fun credentialsAreNotSentToTheRedirectTargetsHost() {
        val remote = mkvWithRemoteChapters()
        val originalUrl = "https://addon.example.com/redirect/ep2.mkv"
        val resolvedUrl = "https://cdn.example.net/files/ep2.mkv"
        val requested = mutableListOf<String>()
        val result = load(
            url = originalUrl,
            headers = mapOf("Authorization" to "Bearer secret"),
            retryDelaysMs = emptyList(),
        ) { url, start ->
            requested += url
            when (start) {
                0L -> MatroskaChapterLoader.RangeFetchResult.Success(remote.head, resolvedUrl)
                remote.chaptersOffset -> MatroskaChapterLoader.RangeFetchResult.Success(remote.chapters, resolvedUrl)
                else -> MatroskaChapterLoader.RangeFetchResult.PermanentFailure("unexpected_offset_$start")
            }
        }
        assertEquals(listOf("Cold Open", "Opening"), result.map { it.title })
        assertEquals(listOf(originalUrl, originalUrl, originalUrl), requested)
    }

    @Test
    fun aSameHostRedirectTargetIsKeptEvenWithCredentials() {
        assertEquals(
            "https://host.example.com/b.mkv",
            MatroskaChapterLoader.followUpRangeUrl(
                originalUrl = "https://host.example.com/a.mkv",
                resolvedUrl = "https://host.example.com/b.mkv",
                headers = mapOf("Cookie" to "session=1"),
            ),
        )
    }
}
