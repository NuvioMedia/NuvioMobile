package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsUiState
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.player.skip.NextEpisodeThresholdMode
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertSame
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerAutoPlayTest {
    @get:Rule
    val compose = createComposeRule()
    private val scope = CoroutineScope(StandardTestDispatcher())
    private val playerVisible = mutableStateOf(true)
    private val episodes = (1..8).map {
        MetaVideo(id = "autoplay-test:1:$it", title = "Episode $it", season = 1, episode = it)
    }
    private val nearEnd = PlayerPlaybackSnapshot(
        isLoading = false, isPlaying = false, positionMs = 1_190_000L, durationMs = 1_200_000L,
    )

    @After
    fun cleanup() {
        playerVisible.value = false
        compose.waitForIdle()
        scope.cancel()
        WatchProgressRepository.clearLocalState()
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("nuvio_watch_progress", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun freshEpisodeSnapshotDoesNotStartAnotherAutoPlay() {
        val runtime = startRuntime()
        compose.runOnIdle { runtime.updatePlaybackSnapshot(nearEnd) }
        compose.runOnIdle { assertTrue(runtime.nextEpisodeAutoPlaySearching) }
        switchEpisode(runtime, 2)
        compose.runOnIdle {
            runtime.updatePlaybackSnapshot(nearEnd.copy(positionMs = 0L))
        }
        compose.runOnIdle {
            assertEquals(2, runtime.activeEpisodeNumber)
            assertFalse(runtime.nextEpisodeAutoPlaySearching)
        }
    }

    @Test
    fun loadingSnapshotsCannotStartAutoPlayForAnEpisodeThatHasNotPlayed() {
        val runtime = startRuntime()
        compose.runOnIdle { runtime.updatePlaybackSnapshot(nearEnd) }
        compose.runOnIdle { assertTrue(runtime.nextEpisodeAutoPlaySearching) }
        for (episode in 2..4) {
            switchEpisode(runtime, episode)
            compose.runOnIdle {
                runtime.updatePlaybackSnapshot(nearEnd.copy(isLoading = true))
            }
            compose.runOnIdle {
                assertEquals(episode + 1, runtime.nextEpisodeInfo?.episode)
                assertFalse(runtime.nextEpisodeAutoPlaySearching)
            }
        }
    }

    @Test
    fun freshPositionCancelsAnAutomaticRequest() {
        val runtime = startRuntime()
        switchEpisode(runtime, 2)
        compose.runOnIdle { runtime.updatePlaybackSnapshot(nearEnd) }
        val autoPlayJob = compose.runOnIdle {
            assertTrue(runtime.nextEpisodeAutoPlaySearching)
            runtime.nextEpisodeAutoPlayJob
        }
        compose.runOnIdle { runtime.updatePlaybackSnapshot(nearEnd.copy(positionMs = 0L)) }
        compose.runOnIdle {
            assertFalse(runtime.showNextEpisodeCard)
            assertFalse(runtime.nextEpisodeAutoPlaySearching)
            assertTrue(autoPlayJob?.isCancelled == true)
        }
    }

    @Test
    fun loadingEndedSnapshotCannotStartAutoPlay() {
        val runtime = startRuntime()
        switchEpisode(runtime, 2)
        compose.runOnIdle {
            runtime.updatePlaybackSnapshot(PlayerPlaybackSnapshot(isEnded = true, isLoading = true))
        }
        compose.runOnIdle {
            assertEquals(3, runtime.nextEpisodeInfo?.episode)
            assertFalse(runtime.nextEpisodeAutoPlaySearching)
        }
    }

    @Test
    fun sameUrlAcrossEpisodesResetsThePreviousEndPosition() {
        val runtime = startRuntime()
        compose.runOnIdle { runtime.updatePlaybackSnapshot(nearEnd) }
        for (episode in 2..4) {
            switchEpisode(runtime, episode, runtime.activeSourceUrl)
            compose.runOnIdle {
                assertEquals(0L, runtime.playbackSnapshot.positionMs)
                assertEquals(episode + 1, runtime.nextEpisodeInfo?.episode)
                assertFalse(runtime.nextEpisodeAutoPlaySearching)
            }
        }
    }

    @Test
    fun lateSnapshotsCannotAdvanceAcrossTheSeason() {
        val runtime = startRuntime()
        val previousKey = runtime.activePlaybackKey
        compose.runOnIdle { runtime.updatePlaybackSnapshot(nearEnd) }
        switchEpisode(runtime, 2)
        for (episode in 3..7) {
            compose.runOnIdle {
                assertFalse(runtime.updatePlaybackSnapshot(nearEnd.copy(isEnded = true), previousKey))
                runtime.updatePlaybackSnapshot(nearEnd.copy(positionMs = episode * 1_000L))
            }
            compose.runOnIdle {
                assertEquals(2, runtime.activeEpisodeNumber)
                assertEquals(3, runtime.nextEpisodeInfo?.episode)
                assertFalse(runtime.nextEpisodeAutoPlaySearching)
                assertNull(runtime.nextEpisodeAutoPlayJob)
            }
        }
    }

    @Test
    fun lateSnapshotsDoNotCancelTheCurrentEpisodesAutoPlay() {
        val runtime = startRuntime()
        val previousKey = runtime.activePlaybackKey
        switchEpisode(runtime, 2)
        compose.runOnIdle { runtime.updatePlaybackSnapshot(nearEnd) }
        val job = compose.runOnIdle { runtime.nextEpisodeAutoPlayJob }
        compose.runOnIdle {
            assertFalse(runtime.updatePlaybackSnapshot(nearEnd.copy(positionMs = 0L), previousKey))
        }
        compose.runOnIdle {
            assertTrue(runtime.nextEpisodeAutoPlaySearching)
            assertSame(job, runtime.nextEpisodeAutoPlayJob)
            assertTrue(job?.isActive == true)
        }
    }

    @Test
    fun manualNextEpisodeRequestSurvivesPositionUpdates() {
        val runtime = startRuntime()
        compose.runOnIdle { runtime.playNextEpisode() }
        val job = compose.runOnIdle { runtime.nextEpisodeAutoPlayJob }
        compose.runOnIdle { runtime.updatePlaybackSnapshot(nearEnd.copy(positionMs = 0L)) }
        compose.runOnIdle {
            assertTrue(runtime.nextEpisodeAutoPlaySearching)
            assertSame(job, runtime.nextEpisodeAutoPlayJob)
            assertTrue(job?.isActive == true)
        }
    }

    @Test
    fun endedAndThresholdUpdatesKeepASingleAutoPlayRequest() {
        val runtime = startRuntime()
        compose.runOnIdle { runtime.updatePlaybackSnapshot(nearEnd) }
        val job = compose.runOnIdle { runtime.nextEpisodeAutoPlayJob }
        compose.runOnIdle { runtime.updatePlaybackSnapshot(nearEnd.copy(isEnded = true)) }
        compose.runOnIdle {
            assertTrue(runtime.nextEpisodeAutoPlaySearching)
            assertSame(job, runtime.nextEpisodeAutoPlayJob)
            assertTrue(job?.isActive == true)
        }
    }

    @Test
    fun autoPlayWaitsForTheInitialSeekToFinish() {
        val runtime = startRuntime()
        compose.runOnIdle {
            runtime.initialSeekApplied = false
            runtime.updatePlaybackSnapshot(nearEnd)
        }
        compose.runOnIdle {
            assertFalse(runtime.nextEpisodeAutoPlaySearching)
            runtime.initialSeekApplied = true
        }
        compose.runOnIdle { assertTrue(runtime.nextEpisodeAutoPlaySearching) }
    }

    @Test
    fun shortErrorClipsDoNotStartAutoPlayWithMinutesThreshold() {
        val runtime = startRuntime(PlayerSettingsUiState(
            skipIntroEnabled = false,
            streamAutoPlayNextEpisodeEnabled = true,
            streamReuseLastLinkEnabled = false,
            nextEpisodeThresholdMode = NextEpisodeThresholdMode.MINUTES_BEFORE_END,
            nextEpisodeThresholdMinutesBeforeEnd = 2f,
        ))
        for (episode in 1..4) {
            if (episode > 1) switchEpisode(runtime, episode)
            compose.runOnIdle {
                runtime.updatePlaybackSnapshot(PlayerPlaybackSnapshot(
                    isLoading = false, positionMs = 0L, durationMs = 90_000L,
                ))
            }
            compose.runOnIdle {
                assertEquals(episode + 1, runtime.nextEpisodeInfo?.episode)
                assertFalse(runtime.nextEpisodeAutoPlaySearching)
                assertFalse(runtime.showNextEpisodeCard)
            }
        }
    }

    @Test
    fun shortErrorClipsDoNotTrackProgressScrobbleSkipOrAutoPlay() {
        val runtime = startRuntime()
        for (durationMs in listOf(8_000L, 30_000L, 120_999L)) {
            for (isEnded in listOf(false, true)) {
                compose.runOnIdle {
                    runtime.skipIntervals = listOf(SkipInterval(0.0, 60.0, "recap", "test"))
                    runtime.updatePlaybackSnapshot(PlayerPlaybackSnapshot(
                        isLoading = false,
                        isPlaying = !isEnded,
                        isEnded = isEnded,
                        positionMs = if (isEnded) durationMs else durationMs * 99 / 100,
                        durationMs = durationMs,
                    ))
                }
                compose.runOnIdle {
                    assertFalse(runtime.hasRequestedScrobbleStartForCurrentItem)
                    assertFalse(runtime.hasSentCompletionScrobbleForCurrentItem)
                    assertTrue(WatchProgressRepository.uiState.value.entries.isEmpty())
                    assertFalse(runtime.nextEpisodeAutoPlaySearching)
                    assertFalse(runtime.showNextEpisodeCard)
                    assertNull(runtime.activeSkipInterval)
                }
            }
        }
    }

    @Test
    fun errorClipDoesNotOverwriteExistingEpisodeProgressWhenEndingWithoutDuration() {
        val runtime = startRuntime()
        compose.runOnIdle {
            WatchProgressRepository.upsertPlaybackProgress(
                session = runtime.playbackSession,
                snapshot = nearEnd.copy(positionMs = 300_000L),
                syncRemote = false,
            )
            runtime.updatePlaybackSnapshot(PlayerPlaybackSnapshot(
                isLoading = false, isPlaying = true, positionMs = 29_000L, durationMs = 30_000L,
            ))
        }
        compose.runOnIdle {
            runtime.updatePlaybackSnapshot(PlayerPlaybackSnapshot(
                isLoading = false, isEnded = true, positionMs = 30_000L,
            ))
        }
        compose.runOnIdle {
            val progress = WatchProgressRepository.uiState.value.entries.single()
            assertEquals(300_000L, progress.lastPositionMs)
            assertEquals(1_200_000L, progress.durationMs)
            assertFalse(progress.isCompleted)
            assertFalse(runtime.nextEpisodeAutoPlaySearching)
        }
    }

    @Test
    fun savedProgressInsideTheMinutesThresholdImmediatelyStartsAutoPlay() {
        val runtime = startRuntime(PlayerSettingsUiState(
            skipIntroEnabled = false,
            streamAutoPlayNextEpisodeEnabled = true,
            streamReuseLastLinkEnabled = false,
            nextEpisodeThresholdMode = NextEpisodeThresholdMode.MINUTES_BEFORE_END,
            nextEpisodeThresholdMinutesBeforeEnd = 2f,
        ))
        compose.runOnIdle {
            WatchProgressRepository.upsertPlaybackProgress(
                session = runtime.playbackSession.copy(videoId = episodes[1].id, episodeNumber = 2),
                snapshot = PlayerPlaybackSnapshot(isLoading = false, positionMs = 510_000L, durationMs = 600_000L),
                syncRemote = false,
            )
        }
        switchEpisode(runtime, 2)
        compose.runOnIdle {
            assertEquals(510_000L, runtime.activeInitialPositionMs)
            runtime.updatePlaybackSnapshot(PlayerPlaybackSnapshot(
                isLoading = false, positionMs = runtime.activeInitialPositionMs, durationMs = 600_000L,
            ))
        }
        compose.runOnIdle {
            assertEquals(3, runtime.nextEpisodeInfo?.episode)
            assertTrue(runtime.nextEpisodeAutoPlaySearching)
        }
    }

    @Test
    fun incompleteMetadataJumpsToTheNextListedEpisode() {
        val next = PlayerNextEpisodeRules.resolveNextEpisode(
            videos = listOf(episodes[0], episodes[3], episodes[4]),
            currentSeason = 1,
            currentEpisode = 1,
        )
        assertEquals(4, next?.episode)
    }

    private fun startRuntime(settings: PlayerSettingsUiState? = null): PlayerScreenRuntime {
        WatchProgressStorage.initialize(RuntimeEnvironment.getApplication())
        WatchProgressRepository.clearLocalState()
        val runtime = runtime()
        if (settings != null) runtime.playerSettingsUiState = settings
        compose.setContent { if (playerVisible.value) runtime.BindPlayerRuntimeEffects() }
        compose.runOnIdle { assertEquals(2, runtime.nextEpisodeInfo?.episode) }
        return runtime
    }

    private fun switchEpisode(runtime: PlayerScreenRuntime, episode: Int, url: String = "https://example.com/episode-$episode.mp4") {
        compose.runOnIdle {
            runtime.switchToEpisodeStream(
                StreamItem(url = url, addonName = "Test", addonId = "test"),
                episodes[episode - 1],
            )
        }
        compose.waitForIdle()
    }

    private fun runtime() = PlayerScreenRuntime(
        PlayerScreenArgs(
            profileId = ProfileRepository.activeProfileId,
            title = "Series",
            sourceUrl = "https://example.com/episode-1.mp4",
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = null,
            providerName = "Test",
            streamTitle = "Episode 1",
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
            seasonNumber = 1,
            episodeNumber = 1,
            episodeTitle = "Episode 1",
            episodeThumbnail = null,
            contentType = "series",
            videoId = "autoplay-test:1:1",
            parentMetaId = "autoplay-test",
            parentMetaType = "series",
            providerAddonId = "test",
            torrentInfoHash = null,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = 0L,
            initialProgressFraction = null,
        ),
    ).apply {
        scope = this@PlayerAutoPlayTest.scope
        playerSettingsUiState = PlayerSettingsUiState(
            skipIntroEnabled = false,
            streamAutoPlayNextEpisodeEnabled = true,
            streamReuseLastLinkEnabled = false,
        )
        metaUiState = MetaDetailsUiState(meta = MetaDetails(
            id = "autoplay-test", type = "series", name = "Series", videos = episodes,
        ))
    }

}
