package com.nuvio.app

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.junit4.createComposeRule
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonStorage
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.BingeGroupCacheRepository
import com.nuvio.app.features.streams.BingeGroupCacheStorage
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamLaunch
import com.nuvio.app.features.streams.StreamLaunchStore
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.updater.AndroidAppUpdaterPlatform
import com.nuvio.app.navigation.StreamRoute
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowActivity
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = Application::class,
    qualifiers = "w360dp-h640dp-port",
    shadows = [OrientationTrackingActivity::class],
)
class StreamOrientationTest {
    @get:Rule
    val compose = createComposeRule()

    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        PlayerSettingsStorage.initialize(context)
        AddonStorage.initialize(context)
        BingeGroupCacheStorage.initialize(context)
        AndroidAppUpdaterPlatform.initialize(context)
        PlayerSettingsRepository.clearLocalState()
        AddonRepository.clearLocalState()
        MetaDetailsRepository.clear()
        StreamsRepository.clear()
        StreamLaunchStore.clear()
        OrientationTrackingActivity.requests.clear()
    }

    @AfterTest
    fun clearState() {
        PlayerSettingsRepository.clearLocalState()
        StreamsRepository.clear()
        StreamLaunchStore.clear()
        AddonRepository.clearLocalState()
        MetaDetailsRepository.clear()
        BingeGroupCacheRepository.remove("orientation-series")
    }

    @Test
    fun openingManualStreamListWithNoCachedLinkDoesNotRequestLandscape() {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.setStreamReuseLastLinkEnabled(true)
        openStreamList(manualSelection = false)
        assertNoLandscapeRequest()
    }

    @Test
    fun openingManualStreamListWithNoSavedBingeGroupDoesNotRequestLandscape() {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.setStreamAutoPlayReuseBingeGroup(true)
        openStreamList(manualSelection = false)
        assertNoLandscapeRequest()
    }

    @Test
    fun openingManualStreamListWithDefaultSettingsDoesNotRequestLandscape() {
        PlayerSettingsRepository.ensureLoaded()
        openStreamList(manualSelection = false)
        assertNoLandscapeRequest()
    }

    @Test
    fun explicitManualSelectionDoesNotRequestLandscape() {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.setStreamReuseLastLinkEnabled(true)
        openStreamList(manualSelection = true)
        assertNoLandscapeRequest()
    }

    @Test
    fun openingAndRefreshingEpisodeWithUnmatchedSavedBingeGroupDoesNotRequestLandscape() {
        verifySavedBingeGroupOrientation(reuseBingeGroup = true)
    }

    @Test
    fun disablingBingeGroupReuseKeepsEpisodeSourcesInPortrait() {
        verifySavedBingeGroupOrientation(reuseBingeGroup = false)
    }

    private fun verifySavedBingeGroupOrientation(reuseBingeGroup: Boolean) {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.setStreamReuseLastLinkEnabled(false)
        PlayerSettingsRepository.setStreamAutoPlayMode(StreamAutoPlayMode.MANUAL)
        PlayerSettingsRepository.setStreamAutoPlayPreferBingeGroup(true)
        PlayerSettingsRepository.setStreamAutoPlayReuseBingeGroup(reuseBingeGroup)
        BingeGroupCacheRepository.save("orientation-series", "previous-group")
        val launch = StreamLaunch(
            profileId = ProfileRepository.activeProfileId,
            type = "series",
            videoId = "orientation-series:1:1",
            title = "Example series",
            parentMetaId = "orientation-series",
            parentMetaType = "series",
            seasonNumber = 1,
            episodeNumber = 1,
        )
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""
                {"id":"orientation","name":"Test sources","description":"","version":"1",
                 "resources":["meta","stream"],"types":["series"],"catalogs":[]}
            """.trimIndent()))
            server.enqueue(MockResponse().setBody("""
                {"meta":{"id":"orientation-series","type":"series","name":"Example series",
                 "videos":[{"id":"orientation-series:1:1","title":"Episode 1","season":1,"episode":1}]}}
            """.trimIndent()))
            repeat(3) {
                server.enqueue(MockResponse().setBody("""
                    {"streams":[{"name":"Available source","url":"https://example.com/episode.mp4",
                     "behaviorHints":{"bingeGroup":"different-group"}}]}
                """.trimIndent()).setBodyDelay(500, TimeUnit.MILLISECONDS))
            }
            AddonStorage.saveInstalledAddonUrls(
                ProfileRepository.activeProfileId,
                listOf(server.url("/manifest.json").toString()),
            )
            runBlocking {
                AddonRepository.initialize()
                withTimeout(5_000) { AddonRepository.awaitManifestsLoaded() }
                assertNotNull(MetaDetailsRepository.fetch("series", "orientation-series"))
            }
            openStreamList(launch)
            repeat(3) { attempt ->
                if (attempt > 0) {
                    compose.runOnIdle {
                        StreamsRepository.reload(
                            type = launch.type,
                            videoId = launch.videoId,
                            parentMetaId = launch.parentMetaId,
                            season = launch.seasonNumber,
                            episode = launch.episodeNumber,
                        )
                    }
                }
                compose.waitUntil(timeoutMillis = 5_000) {
                    val state = StreamsRepository.uiState.value
                    state.groups.flatMap { it.streams }.isNotEmpty() &&
                        !state.isAnyLoading && !state.showDirectAutoPlayOverlay
                }
                compose.waitForIdle()
            }
            assertEquals(5, server.requestCount)
            assertNoLandscapeRequest()
        }
    }

    private fun openStreamList(manualSelection: Boolean) {
        openStreamList(
            StreamLaunch(
                profileId = 0,
                type = "movie",
                videoId = "orientation-test",
                title = "Example movie",
                manualSelection = manualSelection,
            ),
        )
    }

    private fun openStreamList(launch: StreamLaunch) {
        val launchId = StreamLaunchStore.put(launch)
        compose.setContent {
            NuvioTheme {
                MainAppContent(
                    initialRoute = StreamRoute(launchId, launch.title),
                    ownsAppRuntime = false,
                    showLaunchOverlay = false,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun assertNoLandscapeRequest() {
        compose.runOnIdle {
            assertFalse(
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE in OrientationTrackingActivity.requests,
                "Opening the stream list requested these orientations: ${OrientationTrackingActivity.requests}",
            )
        }
    }
}

@Implements(Activity::class)
class OrientationTrackingActivity : ShadowActivity() {
    companion object {
        val requests = mutableListOf<Int>()
    }

    @Implementation
    override fun setRequestedOrientation(requestedOrientation: Int) {
        requests += requestedOrientation
        super.setRequestedOrientation(requestedOrientation)
    }
}
