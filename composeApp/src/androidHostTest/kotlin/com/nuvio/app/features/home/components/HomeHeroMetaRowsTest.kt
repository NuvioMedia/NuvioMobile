package com.nuvio.app.features.home.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.nuvio.app.features.details.MetaExternalRating
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_IMDB
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_TMDB
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_TOMATOES
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_TRAKT
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w390dp-h844dp")
class HomeHeroMetaRowsTest {
    @get:Rule
    val compose = createComposeRule()

    private val movie = MetaPreview(
        id = "tt1",
        type = "movie",
        name = "Movie title",
        releaseInfo = "2026",
        imdbRating = "7.8",
        genres = listOf("Action"),
        runtime = "109 min",
    )

    @Test
    fun movieWithoutBadgesKeepsImdbOnTheFirstRow() {
        show(movie)

        compose.onNodeWithText("1h 49m").assertIsDisplayed()
        compose.onNodeWithText("2026").assertIsDisplayed()
        assertSameRow(compose.onNodeWithText("Movie"), compose.onNodeWithText("7.8"))
    }

    @Test
    fun badgesMoveImdbToTheSecondRowWithLanguage() {
        show(
            movie.copy(
                type = "series",
                ageRating = "TV-MA",
                status = "Ended",
                language = "en",
            ),
        )

        compose.onNodeWithText("TV-MA").assertIsDisplayed()
        compose.onNodeWithText("ENDED").assertIsDisplayed()
        compose.onNodeWithText("EN").assertIsDisplayed()
        assertSameRow(compose.onNodeWithText("TV-MA"), compose.onNodeWithText("7.8"))
        assertBelow(compose.onNodeWithText("7.8"), compose.onNodeWithText("Series"))
    }

    @Test
    fun overallRatingsSettingHidesImdb() {
        show(movie, showImdbRating = false)

        compose.onNodeWithText("Movie").assertIsDisplayed()
        compose.onNodeWithText("7.8").assertDoesNotExist()
    }

    @Test
    fun mdbListRatingsReplaceImdbAndShowAtMostThree() {
        val showRatings = mutableStateOf(true)
        val item = movie.copy(
            externalRatings = listOf(
                MetaExternalRating(PROVIDER_IMDB, 8.1),
                MetaExternalRating(PROVIDER_TMDB, 65.0),
                MetaExternalRating(PROVIDER_TRAKT, 70.0),
                MetaExternalRating(PROVIDER_TOMATOES, 90.0),
            ),
        )
        compose.setContent {
            MaterialTheme {
                HomeHeroMetaRows(
                    item = item,
                    isTablet = false,
                    showImdbRating = true,
                    showMdbListRatings = showRatings.value,
                )
            }
        }

        compose.onNodeWithText("7.8").assertDoesNotExist()
        compose.onNodeWithText("8.1").assertIsDisplayed()
        compose.onNodeWithText("65").assertIsDisplayed()
        compose.onNodeWithText("70").assertIsDisplayed()
        compose.onNodeWithText("90%").assertDoesNotExist()

        compose.runOnIdle { showRatings.value = false }
        compose.onNodeWithText("65").assertDoesNotExist()
        compose.onNodeWithText("7.8").assertIsDisplayed()
    }

    private fun show(item: MetaPreview, showImdbRating: Boolean = true) {
        compose.setContent {
            MaterialTheme {
                HomeHeroMetaRows(
                    item = item,
                    isTablet = false,
                    showImdbRating = showImdbRating,
                    showMdbListRatings = false,
                )
            }
        }
    }

    private fun assertSameRow(first: SemanticsNodeInteraction, second: SemanticsNodeInteraction) {
        assertTrue(abs(first.centerY() - second.centerY()) < 2f)
    }

    private fun assertBelow(lower: SemanticsNodeInteraction, upper: SemanticsNodeInteraction) {
        assertTrue(lower.centerY() > upper.centerY() + 2f)
    }

    private fun SemanticsNodeInteraction.centerY(): Float =
        fetchSemanticsNode().boundsInRoot.center.y
}
