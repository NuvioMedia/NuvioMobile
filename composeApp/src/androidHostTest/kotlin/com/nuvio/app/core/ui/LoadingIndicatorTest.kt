package com.nuvio.app.core.ui

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoadingIndicatorTest {
    @Test
    fun indicatorStopsRequestingFramesWhileInactiveAndRestartsWhenVisible() = runBlocking {
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(EmptyApplier(), recomposer)
        val runner = launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        val active = mutableStateOf(true)
        lateinit var progress: State<Float>
        var compositions = 0

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
                progress = rememberLoadingIndicatorFrame(active.value)
                SideEffect { compositions++ }
            }
            frame(0)
            frame(16)
            frame(32)
            val initial = progress.value
            val initialCompositions = compositions
            frame(300)

            assertNotEquals(initial, progress.value)
            assertEquals(initialCompositions, compositions)

            active.value = false
            frame(316)
            frame(332)
            val stopped = progress.value
            frame(600)
            frame(900)

            assertEquals(stopped, progress.value)
            assertFalse(frameClock.hasAwaiters)

            active.value = true
            frame(916)
            frame(932)
            val restarted = progress.value
            frame(1_200)

            assertNotEquals(restarted, progress.value)
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.join()
        }
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
