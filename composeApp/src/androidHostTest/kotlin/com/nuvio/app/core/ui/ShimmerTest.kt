package com.nuvio.app.core.ui

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShimmerTest {
    @Test
    fun `skeletons share continuous progress and stop when inactive or removed`() = runBlocking {
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(EmptyApplier(), recomposer)
        val runner = launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        val showLoading = mutableStateOf(true)
        val showSecond = mutableStateOf(false)
        val active = mutableStateOf(true)
        lateinit var progress: State<Float>
        lateinit var secondProgress: State<Float>
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
                SkeletonAnimationProvider {
                    CompositionLocalProvider(LocalScreenActive provides active.value) {
                        if (showLoading.value) {
                            progress = rememberSkeletonProgress()
                            SideEffect { compositions++ }
                            if (showSecond.value) {
                                secondProgress = rememberSkeletonProgress()
                            }
                        }
                    }
                }
            }
            frame(0)
            frame(16)
            frame(32)
            val initialProgress = progress.value
            val initialCompositions = compositions

            frame(300)
            frame(600)

            assertNotEquals(initialProgress, progress.value)
            assertEquals(initialCompositions, compositions)

            showSecond.value = true
            frame(700)
            frame(716)

            assertSame(progress, secondProgress)
            assertTrue(secondProgress.value > 0f)

            active.value = false
            frame(750)
            frame(766)
            val inactiveProgress = progress.value
            frame(800)

            assertEquals(inactiveProgress, progress.value)
            assertFalse(frameClock.hasAwaiters)

            active.value = true
            frame(850)
            frame(866)
            frame(882)
            val visibleProgress = progress.value
            frame(1000)

            assertNotEquals(visibleProgress, progress.value)
            assertSame(progress, secondProgress)

            showLoading.value = false
            frame(1100)
            frame(1116)
            val stoppedProgress = progress.value
            frame(1200)
            frame(1600)

            assertEquals(stoppedProgress, progress.value)
            assertFalse(frameClock.hasAwaiters)

            showLoading.value = true
            frame(1700)
            frame(1716)
            frame(1732)
            val restartedProgress = progress.value
            frame(2000)

            assertNotEquals(restartedProgress, progress.value)
            assertSame(progress, secondProgress)
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
