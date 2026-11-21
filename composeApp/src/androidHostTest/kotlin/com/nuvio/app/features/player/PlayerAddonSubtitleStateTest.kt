package com.nuvio.app.features.player

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.network.NetworkStatusUiState
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.AddonsUiState
import com.nuvio.app.features.addons.ManagedAddon
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerAddonSubtitleStateTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun addonSubtitleStateUpdatesTriggerRecompositionOnRuntimeReceiver() {
        val runtime = runtime()
        var observedSubtitles: List<AddonSubtitle> = emptyList()
        var observedLoading = false
        var observedFetchKey: String? = null

        compose.setContent {
            runtime.ObserveAddonSubtitleState(
                onObserved = { subtitles, loading, fetchKey ->
                    observedSubtitles = subtitles
                    observedLoading = loading
                    observedFetchKey = fetchKey
                },
            )
        }

        compose.runOnIdle {
            assertTrue(observedSubtitles.isEmpty())
            assertEquals(false, observedLoading)
            assertEquals(null, observedFetchKey)

            runtime.addonsUiState = AddonsUiState(
                addons = listOf(
                    ManagedAddon(
                        manifestUrl = "https://example.com/manifest.json",
                        manifest = AddonManifest(
                            id = "org.example.subs",
                            name = "Example Subs",
                            description = "",
                            version = "1.0.0",
                            resources = listOf(
                                AddonResource(
                                    name = "subtitles",
                                    types = listOf("movie"),
                                    idPrefixes = listOf("tt"),
                                ),
                            ),
                            types = listOf("movie"),
                            transportUrl = "https://example.com/manifest.json",
                        ),
                        enabled = true,
                    ),
                ),
            )
            runtime.isLoadingAddonSubtitles = true
            runtime.addonSubtitles = listOf(
                AddonSubtitle(
                    id = "sub-1",
                    url = "https://example.com/sub.vtt",
                    language = "ro",
                    display = "Romanian - Example Subs",
                    addonName = "Example Subs",
                ),
            )
        }

        compose.runOnIdle {
            assertEquals(1, observedSubtitles.size)
            assertEquals("sub-1", observedSubtitles.single().id)
            assertTrue(observedLoading)
            assertNotNull(observedFetchKey)
        }
    }

    @Test
    fun serversUnreachableDoesNotMarkAddonSubtitlesOffline() {
        val stateField = NetworkStatusRepository::class.java.getDeclaredField("_uiState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(NetworkStatusRepository) as MutableStateFlow<NetworkStatusUiState>
        val previous = stateFlow.value
        try {
            stateFlow.value = NetworkStatusUiState(condition = NetworkCondition.ServersUnreachable)
            assertFalse(isNetworkOffline())

            stateFlow.value = NetworkStatusUiState(condition = NetworkCondition.NoInternet)
            assertTrue(isNetworkOffline())

            stateFlow.value = NetworkStatusUiState(condition = NetworkCondition.Online)
            assertFalse(isNetworkOffline())
        } finally {
            stateFlow.value = previous
        }
    }

    @Composable
    private fun PlayerScreenRuntime.ObserveAddonSubtitleState(
        onObserved: (List<AddonSubtitle>, Boolean, String?) -> Unit,
    ) {
        onObserved(addonSubtitles, isLoadingAddonSubtitles, addonSubtitleFetchKey)
    }

    private fun runtime() = PlayerScreenRuntime(
        PlayerScreenArgs(
            profileId = 1,
            title = "Movie",
            sourceUrl = "https://example.com/movie.mp4",
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = null,
            providerName = "Test",
            streamTitle = "Movie",
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
            videoId = "tt0426592",
            parentMetaId = "tt0426592",
            parentMetaType = "movie",
            providerAddonId = null,
            torrentInfoHash = null,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = 0L,
            initialProgressFraction = null,
        ),
    )
}
