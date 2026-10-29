package com.nuvio.app.core.ui

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenActivityEffectTest {
    @Test
    fun activationRefreshStartsWorkOnlyForThePreparedGeneration() = runBlocking {
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(EmptyApplier(), recomposer)
        val runner = launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        val active = mutableStateOf(true)
        val resolvedGenerations = mutableListOf<Int>()
        val onResolve: (Int) -> Unit = { resolvedGenerations += it }

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
                CompositionLocalProvider(LocalScreenActive provides active.value) {
                    ProjectionConsumer(onResolve)
                }
            }
            frame(0)
            frame(16)
            frame(32)

            assertEquals(listOf(1), resolvedGenerations)

            active.value = false
            frame(48)
            frame(64)

            assertEquals(listOf(1), resolvedGenerations)

            active.value = true
            frame(80)
            frame(96)
            frame(112)

            assertEquals(listOf(1, 2), resolvedGenerations)
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.join()
        }
    }

    @Test
    fun activityChangesRestartEffectsWithoutRecomposingTheirParent() = runBlocking {
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(EmptyApplier(), recomposer)
        val runner = launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        val active = mutableStateOf(true)
        val key = mutableStateOf(1)
        val events = mutableListOf<String>()
        var parentCompositions = 0
        val onEvent: (String) -> Unit = { events += it }
        val onComposition: () -> Unit = { parentCompositions++ }

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
                CompositionLocalProvider(LocalScreenActive provides active.value) {
                    ActivityConsumer(
                        effectKey = key.value,
                        onEvent = onEvent,
                        onComposition = onComposition,
                    )
                }
            }
            frame(0)
            frame(16)
            val initialCompositions = parentCompositions

            active.value = false
            frame(32)
            frame(48)
            active.value = true
            frame(64)
            frame(80)

            assertEquals(initialCompositions, parentCompositions)
            assertEquals(listOf("start:1:true", "stop:1:true", "start:1:false", "stop:1:false", "start:1:true"), events)

            key.value = 2
            frame(96)
            frame(112)

            assertEquals(initialCompositions + 1, parentCompositions)
            assertEquals(listOf("stop:1:true", "start:2:true"), events.takeLast(2))
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.join()
        }
    }

    @Composable
    private fun ProjectionConsumer(onResolve: (Int) -> Unit) {
        var generation by remember { mutableStateOf(0) }
        ScreenActivityEffect {
            if (it) generation += 1
        }
        val preparedGeneration = generation
        ScreenActivityEffect(preparedGeneration) { active ->
            if (!active || preparedGeneration != generation) return@ScreenActivityEffect
            onResolve(preparedGeneration)
        }
    }

    @Composable
    private fun ActivityConsumer(
        effectKey: Int,
        onEvent: (String) -> Unit,
        onComposition: () -> Unit,
    ) {
        ScreenActivityEffect(effectKey) { active ->
            onEvent("start:$effectKey:$active")
            try {
                awaitCancellation()
            } finally {
                onEvent("stop:$effectKey:$active")
            }
        }
        SideEffect(onComposition)
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
