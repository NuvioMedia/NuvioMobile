package com.nuvio.app.features.shuffle

import android.app.Application
import android.content.Context
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watched.watchedItemKey
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h852dp-port")
class EpisodeShuffleSheetTest {
    @get:Rule
    val compose = createComposeRule()
    private val episode = MetaVideo("show:1:1", "The first episode", season = 1, episode = 1,
        overview = "An unexpected visitor brings the friends together for an evening they will remember.")
    private val meta = MetaDetails("show", "series", "A familiar series", videos = listOf(episode))
    private val profileId get() = ProfileRepository.activeProfileId

    @Before
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("episode_shuffle", Context.MODE_PRIVATE).edit().clear().commit()
        EpisodeShuffleStorage.initialize(context)
        EpisodeShuffleRepository.clearLocalState()
        EpisodeShuffleRepository.setAvailable(true)
    }

    @Test
    fun choosingUnwatchedShowsPreviewAndSpoilerProtection() {
        show()
        compose.onNodeWithText("Unwatched episodes").performClick()
        compose.onNodeWithText("Your episode").assertIsDisplayed()
        compose.onNodeWithText("The first episode").assertIsDisplayed()
        compose.onNodeWithText("Artwork hidden to avoid spoilers").assertIsDisplayed()
        compose.onNodeWithText("This is the only episode in your selection.").assertIsDisplayed()
    }

    @Test
    fun caughtUpRequiresExplicitAllEpisodesSelection() {
        show(watched = setOf(watchedItemKey("series", "show", 1, 1)))
        compose.onNodeWithText("Unwatched episodes").assertIsNotEnabled()
        compose.onNodeWithText("You have watched every episode. Choose All episodes to watch again.").assertIsDisplayed()
        compose.onNodeWithText("All episodes").performClick()
        compose.onNodeWithText("The first episode").assertIsDisplayed()
    }

    @Test
    fun stoppingShuffleRestoresSequentialModeAndConfirms() {
        val enabled = EpisodeShuffleSettings(true, true)
        EpisodeShuffleRepository.save("show", enabled, profileId)
        compose.setContent {
            NuvioTheme {
                val save = rememberShuffleSave("show", profileId, enabled)
                Button(onClick = { save(enabled.copy(enabled = false)) }) { Text("Stop") }
            }
        }
        compose.onNodeWithText("Stop").performClick()
        compose.runOnIdle {
            assertFalse(EpisodeShuffleRepository.readProfile(profileId).settings("show", "series").enabled)
            assertEquals("Shuffle off", NuvioToastController.currentToast.value?.message)
        }
    }

    private fun show(watched: Set<String> = emptySet(), settings: EpisodeShuffleSettings = EpisodeShuffleSettings()) {
        compose.setContent {
            NuvioTheme {
                EpisodeShuffleSheet(meta, settings, { true }, watched, emptyList(), true,
                    onDismiss = {}, onPlay = {}, onPlayManually = null, onStartFromBeginning = {})
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Unwatched episodes")).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
