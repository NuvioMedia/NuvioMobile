package com.nuvio.app.features.home

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.home.components.HomeHeroSection
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w390dp-h844dp")
class HomeHeroPagerTest {
    @get:Rule
    val compose = createComposeRule()

    private val items = List(8) { index ->
        MetaPreview(id = "hero-$index", type = "movie", name = "Hero $index")
    }

    @Test
    fun swipingAcrossEitherEndOnlyShowsTheFirstAndLastTitles() {
        showHero()

        swipeRight()
        assertWrapFrames(expectedIndex = 7)

        compose.onNodeWithTag("hero").performTouchInput {
            swipe(Offset(width * 0.7f, height * 0.3f), Offset(width * 0.3f, height * 0.3f))
        }
        assertWrapFrames(expectedIndex = 0)
    }

    @Test
    fun automaticCyclingFromLastToFirstDoesNotShowOtherTitles() {
        showHero()
        swipeRight()
        assertWrapFrames(expectedIndex = 7)

        compose.mainClock.advanceTimeBy(6_000L)
        assertWrapFrames(expectedIndex = 0)
    }

    private fun showHero() {
        compose.setContent {
            MaterialTheme {
                HomeHeroSection(
                    items = items,
                    modifier = Modifier.width(390.dp).testTag("hero"),
                )
            }
        }
        compose.onNodeWithText("Hero 0").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
    }

    private fun swipeRight() {
        compose.onNodeWithTag("hero").performTouchInput {
            swipe(Offset(width * 0.3f, height * 0.3f), Offset(width * 0.7f, height * 0.3f))
        }
    }

    private fun assertWrapFrames(expectedIndex: Int) {
        val intermediateTitle = (1..6)
            .map { hasText("Hero $it") }
            .reduce { matcher, next -> matcher or next }
        repeat(100) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodes(intermediateTitle).assertCountEquals(0)
        }
        compose.onNodeWithText("Hero $expectedIndex").assertIsDisplayed()
    }
}
