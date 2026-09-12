package com.nuvio.android

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.app.MainActivity
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.mdblist.MdbListTracker
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.tracking.WatchProgressSource
import org.junit.Assert.assertEquals
import com.nuvio.app.features.settings.SettingsScreen
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MdbListSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun connectedAccountAndDisconnectCopyRenderInRealSettings() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mdblistLive") == "true")
        assumeTrue(MdbListTracker.auth.state.value.isAuthenticated)
        compose.runOnUiThread {
            compose.activity.setContent { NuvioTheme { SettingsScreen(initialPageName = "TraktAuthentication") } }
        }
        compose.onNodeWithText("Sync watched history and playback progress across devices").performScrollTo().assertIsDisplayed()
        screenshot("mdblist-connected.png")
        compose.onNodeWithText("Disconnect MDBList").performScrollTo().performClick()
        compose.onNodeWithText("Disconnect MDBList?").assertIsDisplayed()
        compose.onNodeWithText("Stop sending watch updates from this profile. Your MDBList history is kept.").assertIsDisplayed()
        screenshot("mdblist-disconnect.png")
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test
    fun libraryAndWatchProgressSourcesCanBeSelectedIndependently() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mdblistLive") == "true")
        assumeTrue(MdbListTracker.auth.state.value.isAuthenticated)
        val profile = MdbListTracker.auth.state.value.scope.profileId
        val original = TrackingSettingsRepository.uiState.value
        compose.runOnUiThread {
            compose.activity.setContent { NuvioTheme { SettingsScreen(initialPageName = "TraktAuthentication") } }
        }
        try {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Library Source"))
            compose.onNodeWithText("Library Source").performClick()
            compose.onAllNodesWithText("MDBList").onLast().performClick()
            compose.waitUntil(30_000) { TrackingSettingsRepository.uiState.value.librarySourceMode == LibrarySourceMode.MDBLIST }
            assertEquals(original.watchProgressSource, TrackingSettingsRepository.uiState.value.watchProgressSource)
            compose.onNodeWithText("Watch Progress").performScrollTo().performClick()
            compose.onAllNodesWithText("MDBList").onLast().performClick()
            compose.waitUntil(30_000) { TrackingSettingsRepository.uiState.value.watchProgressSource == WatchProgressSource.MDBLIST }
            assertEquals(LibrarySourceMode.MDBLIST, TrackingSettingsRepository.uiState.value.librarySourceMode)
            screenshot("mdblist-sources.png")
        } finally {
            compose.runOnUiThread {
                TrackingSettingsRepository.setLibrarySourceMode(original.librarySourceMode)
                TrackingSettingsRepository.setWatchProgressSource(original.watchProgressSource, profile)
            }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
