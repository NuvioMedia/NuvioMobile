package com.nuvio.app.features.p2p

import com.nuvio.engine.NuvioStorageMode
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
            cacheSize = P2pCacheSize.GB_5,
            memoryStorageCapacityBytes = MinP2pMemoryStorageBytes,
        )
        val downloadOnly = buildNuvioEngineConfig(
            stateDirectory = stateDirectory,
            cacheDirectory = cacheDirectory,
            uploadEnabled = false,
            torrentProfile = P2pTorrentProfile.SOFT,
            cacheSize = P2pCacheSize.NONE,
            memoryStorageCapacityBytes = MinP2pMemoryStorageBytes,
        )

        assertEquals(0, uploading.streamInactivityTimeoutMilliseconds)
        assertEquals(NuvioUploadMode.Unlimited, uploading.uploadMode)
        assertEquals(NuvioTorrentProfile.Fast, uploading.torrentProfile)
        assertEquals(P2pCacheSize.GB_5.bytes, uploading.diskCacheCapacityBytes)
        assertEquals(0, downloadOnly.streamInactivityTimeoutMilliseconds)
        assertEquals(NuvioUploadMode.Disabled, downloadOnly.uploadMode)
        assertEquals(NuvioTorrentProfile.Soft, downloadOnly.torrentProfile)
        assertEquals(0L, downloadOnly.diskCacheCapacityBytes)
        assertEquals(NuvioStorageMode.Disk, uploading.storageMode)
        assertEquals(NuvioStorageMode.Disk, downloadOnly.storageMode)
    }

    @Test
    fun memoryOnlyCacheSelectsEngineMemoryStorage() {
        val config = buildNuvioEngineConfig(
            stateDirectory = File("state"),
            cacheDirectory = File("cache"),
            uploadEnabled = true,
            torrentProfile = P2pTorrentProfile.BALANCED,
            cacheSize = P2pCacheSize.MEMORY_ONLY,
            memoryStorageCapacityBytes = 256L * 1024L * 1024L,
        )

        assertEquals(NuvioStorageMode.Memory, config.storageMode)
        assertEquals(256L * 1024L * 1024L, config.memoryStorageCapacityBytes)
        assertEquals(0L, config.diskCacheCapacityBytes)
    }

    @Test
    fun memoryStorageCapacityFollowsDeviceMemoryWithinBounds() {
        val gib = 1024L * 1024L * 1024L
        assertEquals(256L * 1024L * 1024L, p2pMemoryStorageCapacityBytes(2L * gib, lowRamDevice = false))
        assertEquals(MinP2pMemoryStorageBytes, p2pMemoryStorageCapacityBytes(512L * 1024L * 1024L, lowRamDevice = false))
        assertEquals(MaxP2pMemoryStorageBytes, p2pMemoryStorageCapacityBytes(8L * gib, lowRamDevice = false))
        assertEquals(MinP2pMemoryStorageBytes, p2pMemoryStorageCapacityBytes(8L * gib, lowRamDevice = true))
    }

    @Test
    fun payloadMeasurementSumsStoredBytesOfEveryPayloadFile() {
        val cacheDirectory = Files.createTempDirectory("nuvio-engine-cache-").toFile()
        try {
            File(cacheDirectory, "payload/$TORRENT_ID/season").mkdirs()
            File(cacheDirectory, "payload/$TORRENT_ID/video.mkv").writeText("12345")
            File(cacheDirectory, "payload/$TORRENT_ID/season/episode.mkv").writeText("123")
            File(cacheDirectory, "state.bin").writeText("ignored")

            assertEquals(8L, measureP2pPayloadBytes(cacheDirectory) { it.length() })
            assertEquals(0L, measureP2pPayloadBytes(File(cacheDirectory, "missing")) { it.length() })
        } finally {
            cacheDirectory.deleteRecursively()
        }
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
