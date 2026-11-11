package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
import androidx.compose.ui.Modifier
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerSourceFallbackTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val failedUrl = "https://example.com/failed.mkv"
    private val candidates = (1..5).map { index ->
        StreamItem(name = "Source $index", url = "https://example.com/source-$index.mkv", addonName = "Test", addonId = "test")
    }

    @Before
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        listOf("nuvio_watch_progress", "nuvio_player_settings").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        PlayerSettingsStorage.initialize(context)
        WatchProgressStorage.initialize(context)
        WatchProgressRepository.clearLocalState()
    }

    @After
    fun cleanup() {
        scope.cancel()
        WatchProgressRepository.clearLocalState()
    }

    @Test
    fun nextCandidateIsTheFirstMatchNotTriedYet() {
        val tried = setOfNotNull(candidates[0].playerSourceIdentityKey())

        val next = nextSourceFallbackCandidate(candidates, settings(), tried)

        assertEquals(candidates[1], next)
    }

    @Test
    fun otherCopiesOfTheFailedTorrentAreSkipped() {
        val sameTorrent = StreamItem(name = "Same", url = "https://example.com/same.mkv", infoHash = HASH, addonName = "Test", addonId = "test")
        val other = candidates[0]

        val next = nextSourceFallbackCandidate(listOf(sameTorrent, other), settings(), emptySet(), failedInfoHash = HASH)

        assertEquals(other, next)
    }

    @Test
    fun noCandidateWhenEverySourceWasTried() {
        val tried = candidates.mapNotNull { it.playerSourceIdentityKey() }.toSet()

        assertNull(nextSourceFallbackCandidate(candidates, settings(), tried))
    }

    @Test
    fun regexModeOnlyFallsBackToMatchingSources() {
        val regexSettings = settings().copy(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH, streamAutoPlayRegex = "Source 3")

        assertEquals(candidates[2], nextSourceFallbackCandidate(candidates, regexSettings, emptySet()))
    }

    @Test
    fun replacementBeforePlaybackStartedKeepsTheResumePosition() {
        val runtime = runtime(initialPositionMs = 120_000L)

        runtime.playReplacementSource(candidates[0], candidates[0].url!!, "tt2026")

        assertEquals(candidates[0].url, runtime.activeSourceUrl)
        assertEquals(120_000L, runtime.activeInitialPositionMs)
        assertEquals(candidates[0].url, runtime.autoSelectedSourceUrl)
    }

    @Test
    fun noFallbackAfterTheConfiguredNumberOfSources() {
        val runtime = runtime()
        runtime.playerSettingsUiState = runtime.playerSettingsUiState.copy(streamAutoPlayFallbackMaxSources = 2)
        runtime.sourceFallbackAttempts = 2

        assertFalse(runtime.tryFallbackToNextSourceAfterError("failed"))
    }

    @Test
    fun sourcePickedByTheUserNeverFallsBack() {
        assertFalse(runtime(autoSelected = false).tryFallbackToNextSourceAfterError("failed"))
    }

    @Test
    fun engineSwitchOnErrorExcludesTheFallback() {
        val runtime = runtime()
        runtime.playerSettingsUiState = runtime.playerSettingsUiState.copy(autoSwitchPlaybackEngineOnError = true)

        assertFalse(runtime.tryFallbackToNextSourceAfterError("failed"))
    }

    @Test
    fun failureAfterPlaybackStartedNeverFallsBack() {
        val runtime = runtime()
        runtime.initialLoadCompleted = true

        assertFalse(runtime.tryFallbackToNextSourceAfterError("failed"))
    }

    @Test
    fun manualSourceSelectionModeNeverFallsBack() {
        val runtime = runtime()
        runtime.playerSettingsUiState = runtime.playerSettingsUiState.copy(streamAutoPlayMode = StreamAutoPlayMode.MANUAL)

        assertFalse(runtime.tryFallbackToNextSourceAfterError("failed"))
    }

    private fun settings() = PlayerSettingsUiState(
        streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM,
        streamAutoPlayFallbackOnFailure = true,
        autoSwitchPlaybackEngineOnError = false,
        streamReuseLastLinkEnabled = false,
    )

    private fun nextSourceFallbackCandidate(
        streams: List<StreamItem>,
        settings: PlayerSettingsUiState,
        triedKeys: Set<String>,
        failedInfoHash: String? = null,
    ) = nextSourceFallbackCandidate(
        streams = streams,
        settings = settings,
        triedKeys = triedKeys,
        failedAddonId = "test",
        failedInfoHash = failedInfoHash,
        installedAddonNames = setOf("Test"),
        debridEnabled = false,
        activeResolverProviderId = null,
    )

    private fun runtime(autoSelected: Boolean = true, initialPositionMs: Long = 0L) = PlayerScreenRuntime(
        PlayerScreenArgs(
            profileId = 1,
            title = "Movie",
            sourceUrl = failedUrl,
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = null,
            providerName = "Test",
            streamTitle = "Failed",
            streamSubtitle = null,
            initialBingeGroup = null,
            pauseDescription = null,
            onBack = {},
            onOpenInExternalPlayer = null,
            onOpenExternalUrl = null,
            modifier = Modifier,
            logo = null,
            poster = null,
            background = null,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            contentType = "movie",
            videoId = "tt2026",
            parentMetaId = "tt2026",
            parentMetaType = "movie",
            providerAddonId = "test",
            torrentInfoHash = null,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = initialPositionMs,
            initialProgressFraction = null,
            stream = StreamItem(name = "Failed", url = failedUrl, addonName = "Test", addonId = "test"),
            sourceAutoSelected = autoSelected,
        ),
    ).apply {
        scope = this@PlayerSourceFallbackTest.scope
        playerSettingsUiState = settings()
    }

    private companion object {
        const val HASH = "0123456789abcdef0123456789abcdef01234567"
    }
}
