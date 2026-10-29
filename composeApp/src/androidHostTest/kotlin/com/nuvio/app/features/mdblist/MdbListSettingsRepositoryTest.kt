package com.nuvio.app.features.mdblist

import android.app.Application
import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MdbListSettingsRepositoryTest {
    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_mdblist_settings", Context.MODE_PRIVATE).edit().clear().commit()
        MdbListSettingsStorage.initialize(context)
        MdbListSettingsRepository.onProfileChanged()
    }

    @AfterTest
    fun clearState() {
        initialize()
    }

    @Test
    fun enabledPreferenceSurvivesReloadWithoutAPersonalKey() {
        MdbListSettingsRepository.setEnabled(true)
        MdbListSettingsRepository.onProfileChanged()

        val settings = MdbListSettingsRepository.snapshot()
        assertTrue(settings.enabled)
        assertEquals("", settings.apiKey)
        assertFalse(settings.hasCredentials)
    }

    @Test
    fun clearingAnOverrideDoesNotDisableRatings() {
        MdbListSettingsRepository.setEnabled(true)
        MdbListSettingsRepository.setApiKey(" separate-key ")
        MdbListSettingsRepository.onProfileChanged()
        assertEquals("separate-key", MdbListSettingsRepository.snapshot().apiKey)

        MdbListSettingsRepository.setApiKey(" ")
        MdbListSettingsRepository.onProfileChanged()

        assertTrue(MdbListSettingsRepository.snapshot().enabled)
        assertEquals("", MdbListSettingsRepository.snapshot().apiKey)
        assertEquals(true, MdbListSettingsStorage.loadEnabled())
        assertEquals("", MdbListSettingsStorage.loadApiKey())
    }
}
