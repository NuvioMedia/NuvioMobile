package com.nuvio.app.features.details

import android.app.Application
import android.content.Context
import com.nuvio.app.core.storage.ProfileScopedKey
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
class RatingsSettingsTest {
    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_meta_screen_settings", Context.MODE_PRIVATE).edit().clear().commit()
        MetaScreenSettingsStorage.initialize(context)
        MetaScreenSettingsRepository.clearLocalState()
    }

    @AfterTest
    fun clearState() {
        MetaScreenSettingsRepository.clearLocalState()
    }

    @Test
    fun choicesPersistIndependentlyAndRoundTripThroughTheProfilePayload() {
        MetaScreenSettingsRepository.setShowOverallRatings(false)
        MetaScreenSettingsRepository.setEpisodeRatingsVisibility(EpisodeRatingsVisibility.HIDE_UNWATCHED_EPISODES)
        val payload = MetaScreenSettingsStorage.loadPayload()!!

        MetaScreenSettingsRepository.resetToDefaults()
        assertTrue(MetaScreenSettingsRepository.uiState.value.showOverallRatings)
        assertEquals(EpisodeRatingsVisibility.SHOW_ALL, MetaScreenSettingsRepository.uiState.value.episodeRatingsVisibility)

        MetaScreenSettingsStorage.savePayload(payload)
        MetaScreenSettingsRepository.onProfileChanged()
        assertFalse(MetaScreenSettingsRepository.uiState.value.showOverallRatings)
        assertEquals(EpisodeRatingsVisibility.HIDE_UNWATCHED_EPISODES, MetaScreenSettingsRepository.uiState.value.episodeRatingsVisibility)

        MetaScreenSettingsRepository.setShowOverallRatings(true)
        MetaScreenSettingsRepository.onProfileChanged()
        assertTrue(MetaScreenSettingsRepository.uiState.value.showOverallRatings)
        assertEquals(EpisodeRatingsVisibility.HIDE_UNWATCHED_EPISODES, MetaScreenSettingsRepository.uiState.value.episodeRatingsVisibility)
    }

    @Test
    fun olderAndUnknownPayloadsKeepRatingsVisibleWithoutResettingOtherPreferences() {
        MetaScreenSettingsStorage.savePayload("""{"blur_unwatched_episodes":true}""")
        MetaScreenSettingsRepository.onProfileChanged()
        assertTrue(MetaScreenSettingsRepository.uiState.value.showOverallRatings)
        assertTrue(MetaScreenSettingsRepository.uiState.value.blurUnwatchedEpisodes)
        assertEquals(EpisodeRatingsVisibility.SHOW_ALL, MetaScreenSettingsRepository.uiState.value.episodeRatingsVisibility)

        MetaScreenSettingsStorage.savePayload("""{"episode_ratings_visibility":"future","show_overall_ratings":false}""")
        MetaScreenSettingsRepository.onProfileChanged()
        assertFalse(MetaScreenSettingsRepository.uiState.value.showOverallRatings)
        assertEquals(EpisodeRatingsVisibility.SHOW_ALL, MetaScreenSettingsRepository.uiState.value.episodeRatingsVisibility)
    }

    @Test
    fun savingRatingsDoesNotChangeAnotherProfilesPayload() {
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("nuvio_meta_screen_settings", Context.MODE_PRIVATE)
        val otherProfileKey = ProfileScopedKey.of("meta_screen_settings_payload", 99)
        val otherPayload = """{"show_overall_ratings":true,"episode_ratings_visibility":"SHOW_ALL"}"""
        preferences.edit().putString(otherProfileKey, otherPayload).commit()

        MetaScreenSettingsRepository.setShowOverallRatings(false)
        MetaScreenSettingsRepository.setEpisodeRatingsVisibility(EpisodeRatingsVisibility.HIDE_EPISODES)
        MetaScreenSettingsRepository.onProfileChanged()

        assertFalse(MetaScreenSettingsRepository.uiState.value.showOverallRatings)
        assertEquals(EpisodeRatingsVisibility.HIDE_EPISODES, MetaScreenSettingsRepository.uiState.value.episodeRatingsVisibility)
        assertEquals(otherPayload, preferences.getString(otherProfileKey, null))
    }
}
