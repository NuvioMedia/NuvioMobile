package com.nuvio.app.features.p2p

import com.nuvio.engine.NuvioUploadMode
import com.nuvio.engine.NuvioTorrentProfile
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class P2pStreamingEngineAndroidTest {
    @Test
    fun appOwnedRoutesDisableAutomaticInactivityExpiry() {
        val stateDirectory = File("state")
        val cacheDirectory = File("cache")

        val uploading = buildNuvioEngineConfig(
            stateDirectory = stateDirectory,
            cacheDirectory = cacheDirectory,
            uploadEnabled = true,
            torrentProfile = P2pTorrentProfile.FAST,
            diskCacheCapacityBytes = P2pCacheSize.GB_5.bytes,
        )
        val downloadOnly = buildNuvioEngineConfig(
            stateDirectory = stateDirectory,
            cacheDirectory = cacheDirectory,
            uploadEnabled = false,
            torrentProfile = P2pTorrentProfile.SOFT,
            diskCacheCapacityBytes = P2pCacheSize.NONE.bytes,
        )

        assertEquals(0, uploading.streamInactivityTimeoutMilliseconds)
        assertEquals(NuvioUploadMode.Unlimited, uploading.uploadMode)
        assertEquals(NuvioTorrentProfile.Fast, uploading.torrentProfile)
        assertEquals(P2pCacheSize.GB_5.bytes, uploading.diskCacheCapacityBytes)
        assertEquals(0, downloadOnly.streamInactivityTimeoutMilliseconds)
        assertEquals(NuvioUploadMode.Disabled, downloadOnly.uploadMode)
        assertEquals(NuvioTorrentProfile.Soft, downloadOnly.torrentProfile)
        assertEquals(0L, downloadOnly.diskCacheCapacityBytes)
    }

    @Test
    fun matchingUnsolicitedStopBecomesTerminalError() {
        val error = unexpectedStreamStopError(
            requestId = 0L,
            eventStreamId = "stream",
            currentStreamId = "stream",
            message = "stream expired after inactivity",
            fallbackMessage = "unknown",
        )

        assertEquals(
            P2pStreamingState.Error("stream expired after inactivity"),
            error,
        )
    }

    @Test
    fun explicitAndStaleStopsAreIgnored() {
        assertNull(
            unexpectedStreamStopError(
                requestId = 7L,
                eventStreamId = "stream",
                currentStreamId = "stream",
                message = "stopped",
                fallbackMessage = "unknown",
            )
        )
        assertNull(
            unexpectedStreamStopError(
                requestId = 0L,
                eventStreamId = "old-stream",
                currentStreamId = "stream",
                message = "stopped",
                fallbackMessage = "unknown",
            )
        )
    }

    @Test
    fun blankStopMessageUsesFallback() {
        assertEquals(
            P2pStreamingState.Error("unknown"),
            unexpectedStreamStopError(
                requestId = 0L,
                eventStreamId = "stream",
                currentStreamId = "stream",
                message = "  ",
                fallbackMessage = "unknown",
            ),
        )
    }

    @Test
    fun globalCachePressureDoesNotBecomeTerminalError() {
        assertNull(
            unexpectedTorrentError(
                requestId = 0L,
                eventTorrentId = null,
                currentTorrentId = "torrent",
                message = "disk cache budget is exceeded by protected torrent data",
                fallbackMessage = "unknown",
            )
        )
    }

    @Test
    fun matchingUnsolicitedTorrentFailureBecomesTerminalError() {
        assertEquals(
            P2pStreamingState.Error("file write failed"),
            unexpectedTorrentError(
                requestId = 0L,
                eventTorrentId = "torrent",
                currentTorrentId = "torrent",
                message = "file write failed",
                fallbackMessage = "unknown",
            ),
        )
    }

    @Test
    fun commandAndStaleTorrentFailuresAreIgnored() {
        assertNull(
            unexpectedTorrentError(
                requestId = 9L,
                eventTorrentId = "torrent",
                currentTorrentId = "torrent",
                message = "failed",
                fallbackMessage = "unknown",
            )
        )
        assertNull(
            unexpectedTorrentError(
                requestId = 0L,
                eventTorrentId = "old-torrent",
                currentTorrentId = "torrent",
                message = "failed",
                fallbackMessage = "unknown",
            )
        )
    }

    @Test
    fun nestedPayloadMovesToTheEnginePayloadDirectory() {
        val cacheDirectory = Files.createTempDirectory("nuvio-engine-cache-").toFile()
        try {
            val legacy = File(cacheDirectory, "payload/payload/$TORRENT_ID").apply { mkdirs() }
            File(legacy, "video.mkv").writeText("cached")

            migrateNestedPayloadDirectory(cacheDirectory)

            assertEquals("cached", File(cacheDirectory, "payload/$TORRENT_ID/video.mkv").readText())
            assertFalse(File(cacheDirectory, "payload/payload").exists())
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun nestedPayloadNeverReplacesCurrentPayload() {
        val cacheDirectory = Files.createTempDirectory("nuvio-engine-cache-").toFile()
        try {
            val current = File(cacheDirectory, "payload/$TORRENT_ID").apply { mkdirs() }
            File(current, "video.mkv").writeText("current")
            val legacy = File(cacheDirectory, "payload/payload/$TORRENT_ID").apply { mkdirs() }
            File(legacy, "video.mkv").writeText("stale")

            migrateNestedPayloadDirectory(cacheDirectory)

            assertEquals("current", File(current, "video.mkv").readText())
            assertFalse(File(cacheDirectory, "payload/payload").exists())
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun missingNestedPayloadLeavesCacheUntouched() {
        val cacheDirectory = Files.createTempDirectory("nuvio-engine-cache-").toFile()
        try {
            migrateNestedPayloadDirectory(cacheDirectory)

            assertTrue(cacheDirectory.listFiles().isNullOrEmpty())
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }

    private companion object {
        const val TORRENT_ID = "0123456789abcdef0123456789abcdef01234567"
    }
}
