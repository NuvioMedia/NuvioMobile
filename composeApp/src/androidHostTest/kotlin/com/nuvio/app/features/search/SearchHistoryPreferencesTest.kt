package com.nuvio.app.features.search

import android.app.Application
import android.content.Context
import com.nuvio.app.core.storage.ProfileScopedKey
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SearchHistoryPreferencesTest {
    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_search_history", Context.MODE_PRIVATE).edit().clear().commit()
        SearchHistoryStorage.initialize(context)
        SearchHistoryRepository.onProfileChanged()
    }

    @Test
    fun existingHistoryRemainsEnabledWhenNoPreferenceIsSaved() {
        SearchHistoryStorage.savePayload("[\"dune\",\"silo\"]")

        SearchHistoryRepository.onProfileChanged()

        assertTrue(SearchHistoryRepository.enabled.value)
        assertEquals(listOf("dune", "silo"), SearchHistoryRepository.uiState.value)
    }

    @Test
    fun disablingHistorySurvivesReloadAndStopsRecordingUntilReenabled() {
        SearchHistoryRepository.recordSearch("dune")
        SearchHistoryRepository.setEnabled(false)
        assertTrue(SearchHistoryRepository.uiState.value.isEmpty())

        SearchHistoryStorage.initialize(RuntimeEnvironment.getApplication())
        SearchHistoryRepository.onProfileChanged()
        assertFalse(SearchHistoryRepository.enabled.value)
        assertTrue(SearchHistoryRepository.uiState.value.isEmpty())
        SearchHistoryRepository.recordSearch("silo")

        SearchHistoryRepository.setEnabled(true)
        SearchHistoryRepository.onProfileChanged()
        assertTrue(SearchHistoryRepository.enabled.value)
        assertEquals(listOf("dune"), SearchHistoryRepository.uiState.value)

        SearchHistoryRepository.recordSearch("arrival")
        assertEquals(listOf("arrival", "dune"), SearchHistoryRepository.uiState.value)
    }

    @Test
    fun changingPreferenceDoesNotReadOrOverwriteAnotherProfile() {
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("nuvio_search_history", Context.MODE_PRIVATE)
        val otherProfileKey = ProfileScopedKey.of("recent_searches_enabled", 99)
        preferences.edit().putBoolean(otherProfileKey, false).commit()

        SearchHistoryRepository.onProfileChanged()
        assertTrue(SearchHistoryRepository.enabled.value)

        SearchHistoryRepository.setEnabled(false)
        SearchHistoryRepository.setEnabled(true)

        assertFalse(preferences.getBoolean(otherProfileKey, true))
    }
}
