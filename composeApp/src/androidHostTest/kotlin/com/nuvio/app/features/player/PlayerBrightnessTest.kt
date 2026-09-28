package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.serialization.json.buildJsonObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerBrightnessTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val playerVisible = mutableStateOf(true)
    private var controller: PlayerGestureController? = null

    @Before
    fun initialize() {
        val context = compose.activity
        context.getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE).edit().clear().commit()
        PlayerSettingsStorage.initialize(context)
        compose.runOnUiThread {
            context.window.attributes = context.window.attributes.apply { screenBrightness = -1f }
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 77)
        }
    }

    @Test
    fun brightnessChoiceSurvivesPlayerRecreationAndPreservesSystemBrightness() {
        showPlayer()
        compose.runOnIdle {
            assertEquals(77f / 255f, assertNotNull(controller).currentBrightness())
            assertNull(PlayerSettingsStorage.loadPlaybackBrightness())
            assertEquals(0.6f, assertNotNull(controller).setBrightness(0.6f))
            assertEquals(0.6f, compose.activity.window.attributes.screenBrightness)
            assertEquals(0.6f, PlayerSettingsStorage.loadPlaybackBrightness())
            playerVisible.value = false
        }
        compose.runOnIdle {
            assertEquals(-1f, compose.activity.window.attributes.screenBrightness)
            assertEquals(77, Settings.System.getInt(compose.activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
            Settings.System.putInt(compose.activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 26)
            PlayerSettingsStorage.initialize(compose.activity)
            playerVisible.value = true
        }
        compose.runOnIdle {
            assertEquals(0.6f, assertNotNull(controller).currentBrightness())
            assertEquals(0.6f, compose.activity.window.attributes.screenBrightness)
            playerVisible.value = false
        }
        compose.runOnIdle {
            assertEquals(-1f, compose.activity.window.attributes.screenBrightness)
            assertEquals(26, Settings.System.getInt(compose.activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
            assertEquals(0.6f, PlayerSettingsStorage.loadPlaybackBrightness())
        }
    }

    @Test
    fun unadjustedPlaybackContinuesFollowingSystemBrightness() {
        showPlayer()
        compose.runOnIdle {
            assertEquals(-1f, compose.activity.window.attributes.screenBrightness)
            assertEquals(77f / 255f, assertNotNull(controller).currentBrightness())
            playerVisible.value = false
        }
        compose.runOnIdle {
            Settings.System.putInt(compose.activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 102)
            playerVisible.value = true
        }
        compose.runOnIdle {
            assertEquals(-1f, compose.activity.window.attributes.screenBrightness)
            assertEquals(102f / 255f, assertNotNull(controller).currentBrightness())
            assertNull(PlayerSettingsStorage.loadPlaybackBrightness())
        }
    }

    @Test
    fun savedZeroBrightnessIsAppliedAndPreviousWindowOverrideIsRestored() {
        compose.runOnUiThread {
            compose.activity.window.attributes = compose.activity.window.attributes.apply { screenBrightness = 0.25f }
            PlayerSettingsStorage.savePlaybackBrightness(0f)
        }
        showPlayer()
        compose.runOnIdle {
            assertEquals(0f, assertNotNull(controller).currentBrightness())
            assertEquals(0f, compose.activity.window.attributes.screenBrightness)
            playerVisible.value = false
        }
        compose.runOnIdle {
            assertEquals(0.25f, compose.activity.window.attributes.screenBrightness)
            assertEquals(0f, PlayerSettingsStorage.loadPlaybackBrightness())
        }
    }

    @Test
    fun settingsSyncPreservesDeviceBrightnessWithoutExportingIt() {
        PlayerSettingsStorage.savePlaybackBrightness(0.6f)
        assertFalse(PlayerSettingsStorage.exportToSyncPayload().containsKey("playback_brightness"))
        PlayerSettingsStorage.replaceFromSyncPayload(buildJsonObject {})
        assertEquals(0.6f, PlayerSettingsStorage.loadPlaybackBrightness())
    }

    private fun showPlayer() {
        compose.setContent {
            if (playerVisible.value) {
                val activeController = rememberPlayerGestureController()
                SideEffect { controller = activeController }
            }
        }
        compose.waitForIdle()
    }
}
