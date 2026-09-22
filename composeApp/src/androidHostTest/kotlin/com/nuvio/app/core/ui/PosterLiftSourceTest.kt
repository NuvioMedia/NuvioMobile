package com.nuvio.app.core.ui

import android.widget.FrameLayout
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PosterLiftSourceTest {
    @Test
    fun `disposing the overlay does not release the layer still remembered by the shelf`() = runBlocking {
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(EmptyApplier(), recomposer)
        val runner = launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        val graphicsContext = GraphicsContext(FrameLayout(RuntimeEnvironment.getApplication()))
        val showOverlay = mutableStateOf(false)
        lateinit var shelfSource: PosterLiftSource

        suspend fun frame(millis: Long) {
            Snapshot.sendApplyNotifications()
            yield()
            frameClock.sendFrame(millis * 1_000_000L)
            yield()
            Snapshot.sendApplyNotifications()
            yield()
        }

        try {
            composition.setContent {
                shelfSource = remember { PosterLiftSource(graphicsContext) }
                if (showOverlay.value) {
                    val overlaySource = remember { shelfSource }
                    DisposableEffect(overlaySource) {
                        onDispose { overlaySource.land() }
                    }
                }
            }
            frame(0)
            shelfSource.recordPoster()
            val originalLayer = shelfSource.layer

            repeat(3) { cycle ->
                assertTrue(shelfSource.lift())
                showOverlay.value = true
                frame(16L + cycle * 48L)

                if (cycle % 2 == 0) shelfSource.land()
                showOverlay.value = false
                frame(32L + cycle * 48L)

                assertFalse(shelfSource.isLifted)
                assertSame(originalLayer, shelfSource.layer)
                assertFalse(originalLayer.isReleased, "Overlay disposal released the shelf's poster layer")
            }
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.join()
        }
        assertTrue(shelfSource.layer.isReleased)
    }

    private fun source(): PosterLiftSource = PosterLiftSource(
        GraphicsContext(FrameLayout(RuntimeEnvironment.getApplication())),
    ).apply {
        onRemembered()
    }

    private fun PosterLiftSource.recordPoster() {
        layer.record(Density(1f), LayoutDirection.Ltr, IntSize(120, 180)) {
            drawRect(Color.Red)
        }
    }

    @Test
    fun `anchor handoff keeps the poster in its slot until the overlay lifts it`() {
        val source = source()
        try {
            assertFalse(source.lift())
            source.recordPoster()
            val anchor = PosterZoomAnchor(Rect(10f, 20f, 130f, 200f), "poster", 12.dp).also {
                it.source = source
            }
            PosterZoomAnchorHolder.stash(anchor)

            assertSame(source, PosterZoomAnchorHolder.consume()?.source)
            assertFalse(source.isLifted)
            assertTrue(source.lift())
            assertTrue(source.isLifted)
        } finally {
            source.land()
            source.onForgotten()
        }
    }

    @Test
    fun `landing restores the original layer and leaves other cards alone`() {
        val source = source()
        val other = source()
        try {
            source.recordPoster()
            other.recordPoster()
            val originalLayer = source.layer

            assertTrue(source.lift())
            assertFalse(other.isLifted)
            source.land()

            assertFalse(source.isLifted)
            assertSame(originalLayer, source.layer)
            assertFalse(originalLayer.isReleased)
            assertTrue(source.lift())
            source.land()
            assertFalse(source.isLifted)
        } finally {
            source.land()
            source.onForgotten()
            other.onForgotten()
        }
    }

    @Test
    fun `removing a lifted card retains its layer until the overlay is dismissed`() {
        val source = source()
        source.recordPoster()

        assertTrue(source.lift())
        source.onForgotten()

        assertTrue(source.isLifted)
        assertFalse(source.layer.isReleased)
        source.land()

        assertFalse(source.isLifted)
        assertTrue(source.layer.isReleased)
        assertFalse(source.lift())
        source.land()
    }

    @Test
    fun `cards that never lift release their layers when discarded`() {
        val source = source()
        source.onForgotten()
        assertTrue(source.layer.isReleased)
        assertFalse(source.lift())

        val abandoned = PosterLiftSource(GraphicsContext(FrameLayout(RuntimeEnvironment.getApplication())))
        abandoned.onAbandoned()
        assertTrue(abandoned.layer.isReleased)
        assertFalse(abandoned.lift())
    }

    @Test
    fun `abandoning an overlay composition does not release a remembered shelf layer`() {
        val source = source()
        try {
            source.recordPoster()
            source.onAbandoned()

            assertFalse(source.layer.isReleased)
            assertTrue(source.lift())
            source.onForgotten()
            source.onAbandoned()
            assertFalse(source.layer.isReleased)
        } finally {
            source.land()
        }
        assertTrue(source.layer.isReleased)
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
