package com.nuvio.app.navigation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import com.nuvio.app.core.ui.LocalPosterClickAnchor
import com.nuvio.app.core.ui.PosterLiftSource
import com.nuvio.app.core.ui.PosterOpenMotion
import com.nuvio.app.core.ui.posterCardClickable
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PosterNavigationTest {
    @get:Rule
    val compose = createComposeRule()
    private val motion = PosterNavigationState()
    private lateinit var navigator: NuvioNavigator
    private lateinit var backDispatcher: OnBackPressedDispatcher
    private lateinit var view: View
    private var shelfMounted = false
    private var liftedBeforeDestinationDraw = false
    private var destinationMounts = 0

    private fun setContent() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            view = LocalView.current
            backDispatcher = assertNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            DisposableEffect(motion) {
                onDispose { motion.clear() }
            }
            val stack = remember { NavBackStack<NavKey>(TabsRoute) }
            navigator = remember {
                NuvioNavigator(
                    stack,
                    onLocalNavigate = { from, to ->
                        motion.navigate(from, to)
                        assertNotNull(motion.active, "Poster transition must start before navigation")
                        liftedBeforeDestinationDraw = motion.active?.anchor?.source?.isLifted == true
                    },
                    onLocalPop = motion::clear,
                )
            }
            val current = stack.last()
            LaunchedEffect(current) {
                if (motion.active?.to?.let { it != current } == true) motion.clear()
            }
            CompositionLocalProvider(LocalPosterClickAnchor provides { anchor ->
                val source = assertNotNull(anchor.source)
                assertTrue(source.layer.size.width > 0, "The source layer must have drawn before the tap")
                motion.prepare(anchor)
            }) {
                NavDisplay(
                    backStack = stack,
                    onBack = { navigator.popBackStack() },
                    entryProvider = { key ->
                        posterNavigationEntry(key, NavEntry(key) {
                            if (key == TabsRoute) {
                                DisposableEffect(Unit) {
                                    shelfMounted = true
                                    onDispose { shelfMounted = false }
                                }
                                Box(
                                    Modifier.size(120.dp, 180.dp).background(Color.Red)
                                        .posterCardClickable(
                                            onClick = { navigator.navigate(DetailRoute("movie", "test")) },
                                            onLongClick = null,
                                        )
                                        .testTag("poster"),
                                )
                            } else {
                                DisposableEffect(Unit) {
                                    destinationMounts++
                                    onDispose { }
                                }
                                Box(Modifier.fillMaxSize().background(Color.Blue))
                            }
                        }, motion)
                    },
                )
            }
        }
        compose.mainClock.advanceTimeByFrame()
        drawFrame()
    }

    private fun drawFrame() {
        compose.waitForIdle()
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            bitmap.recycle()
        }
    }

    private fun clickPoster() {
        compose.onNodeWithTag("poster").performClick()
        compose.runOnIdle { assertNotNull(motion.active) }
    }

    @Test
    fun `tapping retains the visible poster until the destination is placed`() {
        setContent()
        clickPoster()
        compose.runOnIdle {
            assertFalse(liftedBeforeDestinationDraw, "The tap hid the poster before the destination could draw it")
            val viewport = assertNotNull(motion.active).viewportBounds
            assertTrue(viewport.width > 0f && viewport.height > 0f)
        }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle {
            assertNotNull(assertNotNull(motion.active).clock)
            assertEquals(0f, assertNotNull(motion.active).elapsedMillis, "The first destination frame must start at the poster bounds")
            assertTrue(assertNotNull(motion.active?.anchor?.source).isLifted)
        }
    }

    @Test
    fun `navigation retains the shelf and original layer until the measured transition finishes`() {
        setContent()
        clickPoster()
        compose.mainClock.advanceTimeBy(100)
        lateinit var source: PosterLiftSource
        compose.runOnIdle {
            source = assertNotNull(motion.active?.anchor?.source)
            assertTrue(shelfMounted)
            assertNotNull(motion.active?.clock)
            assertTrue(source.isLifted)
            assertFalse(source.layer.isReleased)
            assertEquals(0, destinationMounts)
        }
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle {
            val request = assertNotNull(motion.active)
            assertEquals(0f, PosterOpenMotion.artworkAlpha(request.elapsedMillis))
            assertEquals(0f, PosterOpenMotion.backgroundAlpha(request.elapsedMillis))
            assertTrue(shelfMounted)
            assertEquals(0, destinationMounts)
        }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            assertNotNull(motion.active)
            assertTrue(shelfMounted)
            assertTrue(source.isLifted)
            assertFalse(source.layer.isReleased)
            assertEquals(1, destinationMounts)
        }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle {
            assertNull(motion.active)
            assertFalse(source.isLifted)
            assertFalse(shelfMounted)
            assertTrue(source.layer.isReleased)
            assertEquals(1, destinationMounts)
        }
    }

    @Test
    fun `back before the first destination frame prevents a late poster lift`() {
        setContent()
        clickPoster()
        lateinit var source: PosterLiftSource
        compose.runOnIdle {
            val request = assertNotNull(motion.active)
            source = assertNotNull(request.anchor.source)
            assertTrue(navigator.popBackStack())
            assertFalse(request.placeArtwork())
            assertFalse(source.isLifted)
            assertEquals(0, destinationMounts)
        }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle {
            assertNull(motion.active)
            assertTrue(shelfMounted)
            assertFalse(source.layer.isReleased)
            assertFalse(source.isLifted)
            assertEquals(0, destinationMounts)
        }
    }

    @Test
    fun `system back during the blank interval cancels detail composition`() {
        setContent()
        clickPoster()
        compose.mainClock.advanceTimeBy(400)
        lateinit var source: PosterLiftSource
        compose.runOnIdle {
            val request = assertNotNull(motion.active)
            source = assertNotNull(request.anchor.source)
            assertEquals(0f, PosterOpenMotion.artworkAlpha(request.elapsedMillis))
            assertEquals(0f, PosterOpenMotion.backgroundAlpha(request.elapsedMillis))
            backDispatcher.onBackPressed()
        }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle {
            assertEquals(TabsRoute, navigator.currentRoute)
            assertNull(motion.active)
            assertFalse(source.isLifted)
            assertFalse(source.layer.isReleased)
            assertTrue(shelfMounted)
            assertEquals(0, destinationMounts)
        }
    }

    @Test
    fun `system back after the shortened blank restores the shelf`() {
        setContent()
        clickPoster()
        compose.mainClock.advanceTimeBy(500)
        lateinit var source: PosterLiftSource
        compose.runOnIdle {
            source = assertNotNull(motion.active?.anchor?.source)
            assertEquals(1, destinationMounts)
            backDispatcher.onBackPressed()
        }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle {
            assertEquals(TabsRoute, navigator.currentRoute)
            assertNull(motion.active)
            assertFalse(source.isLifted)
            assertFalse(source.layer.isReleased)
            assertTrue(shelfMounted)
            assertEquals(1, destinationMounts)
        }
    }

    @Test
    fun `back during opening restores the shelf and clears the pending transition`() {
        setContent()
        clickPoster()
        compose.mainClock.advanceTimeBy(100)
        lateinit var source: PosterLiftSource
        compose.runOnIdle {
            source = assertNotNull(motion.active?.anchor?.source)
            assertTrue(navigator.popBackStack())
            assertEquals(TabsRoute, navigator.currentRoute)
        }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle {
            assertNull(motion.active)
            assertFalse(source.isLifted)
            assertTrue(shelfMounted)
            assertFalse(source.layer.isReleased)
            assertEquals(0, destinationMounts)
        }
        clickPoster()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertNotNull(motion.active) }
        compose.mainClock.advanceTimeBy(600)
    }
}
