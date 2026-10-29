package com.nuvio.app.features.updater

import android.app.Application
import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdatePreferencesTest {
    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_updater", Context.MODE_PRIVATE).edit().clear().commit()
        AndroidAppUpdaterPlatform.initialize(context)
    }

    @Test
    fun betaDefaultSurvivesPromotionToStable() {
        assertEquals(UpdateChannel.BETA, UpdatePreferences("1.1.0-beta.1").channel.value)
        assertEquals("beta", AppUpdaterPlatform.getUpdateChannel())
        assertEquals(UpdateChannel.BETA, UpdatePreferences("1.1.0").channel.value)
    }

    @Test
    fun stableChoiceSurvivesInstallingABetaBuild() {
        val preferences = UpdatePreferences("1.1.0-beta.1")
        preferences.setChannel(UpdateChannel.STABLE)

        assertEquals(UpdateChannel.STABLE, UpdatePreferences("1.1.0-beta.2").channel.value)
    }

    @Test
    fun changingChannelClearsIgnoredRelease() {
        val preferences = UpdatePreferences("1.1.0")
        AppUpdaterPlatform.setIgnoredTag("1.2.0")
        preferences.setChannel(UpdateChannel.BETA)

        assertNull(AppUpdaterPlatform.getIgnoredTag())
        assertEquals(UpdateChannel.BETA, preferences.channel.value)
        assertEquals(UpdateChannel.BETA, UpdatePreferences("1.1.0").channel.value)
    }

    @Test
    fun initializationAndReselectingChannelPreserveIgnoredRelease() {
        AppUpdaterPlatform.setIgnoredTag("1.2.0")
        val preferences = UpdatePreferences("1.1.0")
        preferences.setChannel(UpdateChannel.STABLE)

        assertEquals("1.2.0", AppUpdaterPlatform.getIgnoredTag())
    }

    @Test
    fun invalidSavedChannelIsReplacedByBuildDefault() {
        AppUpdaterPlatform.setUpdateChannel("unknown")

        assertEquals(UpdateChannel.BETA, UpdatePreferences("1.1.0-beta.1").channel.value)
        assertEquals("beta", AppUpdaterPlatform.getUpdateChannel())
    }
}
