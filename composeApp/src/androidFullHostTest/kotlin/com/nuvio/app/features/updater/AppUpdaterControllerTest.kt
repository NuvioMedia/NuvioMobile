package com.nuvio.app.features.updater

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import com.nuvio.app.core.build.AppVersionConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppUpdaterControllerTest {
    private lateinit var scope: CoroutineScope
    private lateinit var preferences: UpdatePreferences

    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_updater", Context.MODE_PRIVATE).edit().clear().commit()
        context.applicationInfo.flags = context.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        AndroidAppUpdaterPlatform.initialize(context)
        preferences = UpdatePreferences("1.1.0")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun forcedChecksDoNotOfferEqualOrOlderVersions() {
        listOf(AppVersionConfig.VERSION_NAME, "0.0.0").forEach { tag ->
            val controller = controller { Result.success(update(tag)) }
            controller.checkForUpdates(force = true, showNoUpdateFeedback = false)

            assertFalse(controller.uiState.value.isUpdateAvailable)
            assertFalse(controller.uiState.value.showDialog)
            assertNull(controller.uiState.value.update)
        }
    }

    @Test
    fun manualChecksCanShowAnIgnoredNewerRelease() {
        AppUpdaterPlatform.setIgnoredTag("999.0.0")
        val controller = controller { Result.success(update("999.0.0")) }

        controller.checkForUpdates(force = false, showNoUpdateFeedback = false)
        assertFalse(controller.uiState.value.showDialog)
        controller.checkForUpdates(force = true, showNoUpdateFeedback = false)
        assertTrue(controller.uiState.value.showDialog)
        assertTrue(controller.uiState.value.isUpdateAvailable)
    }

    @Test
    fun channelChangeDiscardsPendingUpdate() {
        val controller = controller { Result.success(update("999.0.0")) }
        controller.checkForUpdates(force = true, showNoUpdateFeedback = false)
        assertTrue(controller.uiState.value.showDialog)

        preferences.setChannel(UpdateChannel.BETA)

        assertEquals(AppUpdaterUiState(updateChannel = UpdateChannel.BETA), controller.uiState.value)
    }

    @Test
    fun channelChangeChecksNewChannelAndIgnoresLateResults() {
        val context = RuntimeEnvironment.getApplication()
        context.applicationInfo.flags = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        val oldResult = CompletableDeferred<Result<AppUpdate>>()
        val requestedChannels = mutableListOf<UpdateChannel>()
        val controller = controller { channel ->
            requestedChannels += channel
            if (channel == UpdateChannel.STABLE) {
                withContext(NonCancellable) { oldResult.await() }
            } else {
                Result.success(update("999.0.0-beta.1"))
            }
        }
        controller.checkForUpdates(force = false, showNoUpdateFeedback = false)
        preferences.setChannel(UpdateChannel.BETA)
        oldResult.complete(Result.success(update("999.0.0")))

        assertEquals(listOf(UpdateChannel.STABLE, UpdateChannel.BETA), requestedChannels)
        assertEquals(UpdateChannel.BETA, controller.uiState.value.updateChannel)
        assertEquals("999.0.0-beta.1", controller.uiState.value.update?.tag)
        assertTrue(controller.uiState.value.showDialog)
        assertFalse(controller.uiState.value.isChecking)
    }

    @Test
    fun noEligibleReleaseDoesNotOpenErrorDialog() {
        val controller = controller { Result.failure(NoChannelReleaseException()) }
        controller.checkForUpdates(force = true, showNoUpdateFeedback = false)

        assertFalse(controller.uiState.value.showDialog)
        assertFalse(controller.uiState.value.isChecking)
        assertNull(controller.uiState.value.errorMessage)
    }

    @Test
    fun channelChangeCancelsDebugDownloadAndClearsItsState() {
        val controller = controller { error("Debug channel changes should not check for updates") }
        controller.showDebugTestUpdate()
        controller.downloadUpdate()
        assertTrue(controller.uiState.value.isDownloading)

        preferences.setChannel(UpdateChannel.BETA)

        assertEquals(AppUpdaterUiState(updateChannel = UpdateChannel.BETA), controller.uiState.value)
    }

    private fun controller(fetch: suspend (UpdateChannel) -> Result<AppUpdate>) =
        AppUpdaterController(scope, preferences, fetch)

    private fun update(tag: String) = AppUpdate(
        tag = tag,
        title = tag,
        notes = "",
        releaseUrl = null,
        assetName = "app.apk",
        assetUrl = "https://example.com/app.apk",
        assetSizeBytes = null,
    )
}
