package com.nuvio.app.core.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.jelly.JellyMotion
import com.nuvio.app.core.ui.jelly.JellyTabTargets
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FloatingNavigationBarGestureTest {
    @get:Rule
    val compose = createComposeRule()

    private val direction = mutableStateOf(LayoutDirection.Ltr)
    private lateinit var motion: JellyMotion
    private val clicks = mutableListOf<Int>()
    private var profilePopupOpened = false

    @Test
    fun tapsKeepTheirDestinationBeforeSelectionRecomposes() {
        setContent()
        for (layoutDirection in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            reset(layoutDirection)
            for (index in listOf(2, 1, 3, 0)) {
                compose.onNodeWithContentDescription("Tab $index").performTouchInput { click() }
                assertSettlesAt(index)
            }
            compose.runOnIdle { assertEquals(listOf(2, 1, 3, 0), clicks) }
        }
    }

    @Test
    fun profileHoldReturnsToSelectedTabWithoutNavigating() {
        setContent()
        for (layoutDirection in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            reset(layoutDirection)
            compose.onNodeWithContentDescription("Tab 3").performTouchInput { longClick() }
            assertSettlesAt(0)
            compose.runOnIdle {
                assertTrue(profilePopupOpened)
                assertTrue(clicks.isEmpty())
            }
            compose.onNodeWithContentDescription("Tab 1").performTouchInput { click() }
            assertSettlesAt(1)
            compose.runOnIdle { assertEquals(listOf(1), clicks) }
        }
    }

    private fun setContent() {
        motion = JellyMotion(0, 4).apply { resize(320f, 64f, 4) }
        compose.setContent {
            val isRtl = direction.value == LayoutDirection.Rtl
            val items = List(4) { index ->
                FloatingNavigationItem(
                    label = "Tab $index",
                    selected = index == 0,
                    onClick = { clicks += index },
                    content = if (index == 3) {
                        { onClick ->
                            Box(
                                Modifier.size(28.dp)
                                    .clickable(onClick = onClick)
                                    .pointerInput(Unit) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { profilePopupOpened = true },
                                            onDrag = { change, _ -> change.consume() },
                                        )
                                    },
                            )
                        }
                    } else null,
                )
            }
            CompositionLocalProvider(
                LocalDensity provides Density(1f),
                LocalLayoutDirection provides direction.value,
            ) {
                NuvioTheme {
                    Box(
                        Modifier.size(320.dp, 64.dp).pointerInput(isRtl) {
                            detectJellyTabGestures(motion, 1f, { items }, { isRtl })
                        },
                    ) {
                        JellyTabTargets(items, 1f, motion, false, Modifier.matchParentSize())
                    }
                }
            }
        }
    }

    private fun reset(layoutDirection: LayoutDirection) {
        compose.runOnIdle {
            direction.value = layoutDirection
            clicks.clear()
            profilePopupOpened = false
            motion.select(visualNavIndex(0, 4, layoutDirection == LayoutDirection.Rtl))
            repeat(240) { motion.advance(1.0 / 60) }
        }
    }

    private fun assertSettlesAt(index: Int) {
        compose.runOnIdle {
            repeat(240) { motion.advance(1.0 / 60) }
            assertEquals(
                visualNavIndex(index, 4, direction.value == LayoutDirection.Rtl).toFloat(),
                motion.frame.position,
                0.0001f,
            )
            assertFalse(motion.dragging)
            assertFalse(motion.running)
        }
    }
}
