package com.nuvio.app.features.shuffle

import android.app.Application
import android.content.Context
import com.nuvio.app.core.storage.PlatformLocalAccountDataCleaner
import com.nuvio.app.features.profiles.ProfileRepository
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
class EpisodeShuffleStorageTest {
    private val profileId get() = ProfileRepository.activeProfileId

    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("episode_shuffle", Context.MODE_PRIVATE).edit().clear().commit()
        EpisodeShuffleStorage.initialize(context)
        EpisodeShuffleRepository.clearLocalState()
    }

    @Test
    fun settingsSurviveReloadAndGlobalPause() {
        assertTrue(EpisodeShuffleRepository.setAvailable(true))
        assertTrue(EpisodeShuffleRepository.save("show", EpisodeShuffleSettings(true, true), profileId))
        EpisodeShuffleRepository.clearLocalState()
        EpisodeShuffleRepository.ensureLoaded()
        assertTrue(EpisodeShuffleRepository.uiState.value.settings("show", "series").enabled)
        assertTrue(EpisodeShuffleRepository.setAvailable(false))
        assertFalse(EpisodeShuffleRepository.uiState.value.settings("show", "series").enabled)
        EpisodeShuffleRepository.setAvailable(true)
        assertEquals(EpisodeShuffleSettings(true, true), EpisodeShuffleRepository.uiState.value.settings("show", "series"))
    }

    @Test
    fun savingAnotherProfileDoesNotChangeActiveSettings() {
        EpisodeShuffleRepository.setAvailable(true)
        EpisodeShuffleRepository.save("show", EpisodeShuffleSettings(true, false), profileId)
        EpisodeShuffleRepository.save("show", EpisodeShuffleSettings(true, true), profileId + 1)
        assertFalse(EpisodeShuffleRepository.uiState.value.shows.getValue("show").includeWatched)
        assertTrue(EpisodeShuffleRepository.readProfile(profileId + 1).shows.getValue("show").includeWatched)
        EpisodeShuffleRepository.save("show", EpisodeShuffleSettings(false, true), profileId + 1)
        assertTrue(EpisodeShuffleRepository.uiState.value.settings("show", "series").enabled)
    }

    @Test
    fun accountWipeRemovesStoredShuffleSettings() {
        EpisodeShuffleRepository.setAvailable(true)
        EpisodeShuffleRepository.save("show", EpisodeShuffleSettings(true, true), profileId)
        PlatformLocalAccountDataCleaner.initialize(RuntimeEnvironment.getApplication())
        PlatformLocalAccountDataCleaner.wipe()
        EpisodeShuffleRepository.clearLocalState()
        EpisodeShuffleRepository.ensureLoaded()
        assertEquals(EpisodeShuffleProfile(), EpisodeShuffleRepository.uiState.value)
    }
}
