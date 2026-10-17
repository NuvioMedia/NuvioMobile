package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeadLinkStartupMonitorTest {
    @Test
    fun duplicateSourceIdentitiesAreSkipped() = runTest {
        val current = stream("Current", "plugin:current", "https://media.example/current.mp4")
        val duplicate = stream("Duplicate URL", "addon:duplicate", "https://media.example/current.mp4")
        val next = stream("Next", "plugin:next", "https://media.example/next.mp4")
        val fallbacks = mutableListOf<StreamItem>()
        val monitor = newMonitor(
            scope = backgroundScope,
            timeoutSeconds = { 10 },
            candidates = { listOf(current, duplicate, next) },
            onFallback = fallbacks::add,
        )

        monitor.sourceSelected(current)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(listOf(next), fallbacks)
        monitor.close()
    }

    @Test
    fun installedAddonIsChosenBeforePluginWhenBothAreUntried() = runTest {
        val current = stream("Current plugin", "plugin:current", "https://media.example/current.mp4")
        val plugin = stream("Other plugin", "plugin:other", "https://media.example/plugin.mp4")
        val addon = stream("Installed addon", "addon:one", "https://media.example/addon.mp4")
        val fallbacks = mutableListOf<StreamItem>()
        val monitor = newMonitor(
            scope = backgroundScope,
            timeoutSeconds = { 5 },
            candidates = { listOf(current, plugin, addon) },
            onFallback = fallbacks::add,
        )

        monitor.sourceSelected(current)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(listOf(addon), fallbacks)
        monitor.close()
    }

    @Test
    fun timeoutDoesNotFallbackBeforeDeadline() = runTest {
        val current = stream("Current", "plugin:current", "https://media.example/current.mp4")
        val next = stream("Next", "plugin:next", "https://media.example/next.mp4")
        val fallbacks = mutableListOf<StreamItem>()
        val monitor = newMonitor(
            scope = backgroundScope,
            timeoutSeconds = { 10 },
            candidates = { listOf(current, next) },
            onFallback = fallbacks::add,
        )

        monitor.sourceSelected(current)
        runCurrent()
        advanceTimeBy(9_999)
        runCurrent()
        assertTrue(fallbacks.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(next), fallbacks)
        monitor.close()
    }

    @Test
    fun playbackStartingBeforeDeadlineCancelsFallback() = runTest {
        val current = stream("Current", "plugin:current", "https://media.example/current.mp4")
        val next = stream("Next", "plugin:next", "https://media.example/next.mp4")
        val fallbacks = mutableListOf<StreamItem>()
        val monitor = newMonitor(
            scope = backgroundScope,
            timeoutSeconds = { 10 },
            candidates = { listOf(current, next) },
            onFallback = fallbacks::add,
        )

        monitor.sourceSelected(current)
        runCurrent()
        advanceTimeBy(9_999)
        monitor.playbackStateChanged(current, isPlaying = true)
        advanceTimeBy(5_000)
        runCurrent()

        assertTrue(fallbacks.isEmpty())
        monitor.close()
    }

    @Test
    fun oldTimerCannotSwitchAfterAnotherSourceIsSelected() = runTest {
        val first = stream("First", "plugin:first", "https://media.example/first.mp4")
        val second = stream("Second", "plugin:second", "https://media.example/second.mp4")
        val third = stream("Third", "plugin:third", "https://media.example/third.mp4")
        val fallbacks = mutableListOf<StreamItem>()
        val monitor = newMonitor(
            scope = backgroundScope,
            timeoutSeconds = { 10 },
            candidates = { listOf(first, second, third) },
            onFallback = fallbacks::add,
        )

        monitor.sourceSelected(first)
        runCurrent()
        advanceTimeBy(5_000)
        monitor.sourceSelected(second)
        runCurrent()

        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(fallbacks.isEmpty())

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(third), fallbacks)
        monitor.close()
    }

    @Test
    fun latePlayingEventFromPreviousSourceDoesNotMarkFallbackHealthy() = runTest {
        val first = stream("First", "plugin:first", "https://media.example/first.mp4")
        val second = stream("Second", "plugin:second", "https://media.example/second.mp4")
        val third = stream("Third", "plugin:third", "https://media.example/third.mp4")
        val fallbacks = mutableListOf<StreamItem>()
        lateinit var monitor: DeadLinkStartupMonitor
        monitor = newMonitor(
            scope = backgroundScope,
            timeoutSeconds = { 5 },
            candidates = { listOf(first, second, third) },
            onFallback = { selected ->
                fallbacks += selected
                if (selected == second) {
                    // Simulate a stale callback arriving synchronously while
                    // the old source's fallback callback is still on the stack.
                    monitor.playbackStateChanged(first, isPlaying = true)
                }
            },
        )

        monitor.sourceSelected(first)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(second), fallbacks)

        // This mirrors the player's active-stream effect after switchToSource.
        monitor.sourceSelected(second)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(listOf(second, third), fallbacks)
        monitor.close()
    }

    @Test
    fun exhaustedCandidatesNotifyOnlyOnceUntilContentIsReset() = runTest {
        val current = stream("Only source", "plugin:only", "https://media.example/only.mp4")
        var exhaustedCount = 0
        var candidates = listOf(current)
        val fallbacks = mutableListOf<StreamItem>()
        val monitor = newMonitor(
            scope = backgroundScope,
            timeoutSeconds = { 5 },
            candidates = { candidates },
            onFallback = fallbacks::add,
            onExhausted = { exhaustedCount++ },
        )

        monitor.sourceSelected(current)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, exhaustedCount)

        monitor.sourceSelected(current)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, exhaustedCount)

        candidates = listOf(current, stream("New source", "plugin:new", "https://media.example/new.mp4"))
        monitor.resetForContent()
        monitor.sourceSelected(current)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(1, exhaustedCount)
        assertEquals(1, fallbacks.size)
        monitor.close()
    }

    @Test
    fun resetForNewContentClearsAttemptedIdentitiesAndPlaybackState() = runTest {
        val first = stream("First", "plugin:first", "https://media.example/first.mp4")
        val second = stream("Second", "plugin:second", "https://media.example/second.mp4")
        val fallbacks = mutableListOf<StreamItem>()
        val monitor = newMonitor(
            scope = backgroundScope,
            timeoutSeconds = { 5 },
            candidates = { listOf(first, second) },
            onFallback = fallbacks::add,
        )

        monitor.sourceSelected(first)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(second), fallbacks)

        monitor.resetForContent()
        monitor.sourceSelected(second)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(listOf(second, first), fallbacks)
        monitor.close()
    }

    @Test
    fun updatedTimeoutAppliesToSubsequentSourceAttempts() = runTest {
        val first = stream("First", "plugin:first", "https://media.example/first.mp4")
        val second = stream("Second", "plugin:second", "https://media.example/second.mp4")
        val third = stream("Third", "plugin:third", "https://media.example/third.mp4")
        var timeoutSeconds = 5
        val fallbacks = mutableListOf<StreamItem>()
        val monitor = newMonitor(
            scope = backgroundScope,
            timeoutSeconds = { timeoutSeconds },
            candidates = { listOf(first, second, third) },
            onFallback = fallbacks::add,
        )

        monitor.sourceSelected(first)
        runCurrent() // Capture the first attempt's 5-second timeout.
        timeoutSeconds = 20
        advanceTimeBy(4_999)
        runCurrent()
        assertTrue(fallbacks.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(second), fallbacks)

        monitor.sourceSelected(second)
        runCurrent() // The next attempt uses the updated 20-second timeout.
        advanceTimeBy(19_999)
        runCurrent()
        assertEquals(listOf(second), fallbacks)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(second, third), fallbacks)
        monitor.close()
    }

    @Test
    fun nonPlayableCandidatesAreIgnoredSafely() = runTest {
        val current = stream("Current", "plugin:current", "https://media.example/current.mp4")
        val nonPlayable = StreamItem(addonName = "Metadata only", addonId = "plugin:metadata")
        val fallbacks = mutableListOf<StreamItem>()
        var exhaustedCount = 0
        val monitor = newMonitor(
            scope = backgroundScope,
            timeoutSeconds = { 5 },
            candidates = { listOf(current, nonPlayable) },
            onFallback = fallbacks::add,
            onExhausted = { exhaustedCount++ },
        )

        monitor.sourceSelected(current)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertTrue(fallbacks.isEmpty())
        assertEquals(1, exhaustedCount)
        monitor.close()
    }

    @Test
    fun malformedSourceUrlCanFailOverWithoutCrashingTheMonitor() = runTest {
        val malformed = stream("Malformed", "plugin:malformed", "not a valid url")
        val valid = stream("Valid", "plugin:valid", "https://media.example/valid.mp4")
        val fallbacks = mutableListOf<StreamItem>()
        val monitor = newMonitor(
            scope = backgroundScope,
            timeoutSeconds = { 5 },
            candidates = { listOf(malformed, valid) },
            onFallback = fallbacks::add,
        )

        monitor.sourceSelected(malformed)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(listOf(valid), fallbacks)
        monitor.close()
    }

    private fun stream(name: String, addonId: String, url: String) =
        StreamItem(name = name, url = url, addonName = name, addonId = addonId)

    private fun newMonitor(
        scope: kotlinx.coroutines.CoroutineScope,
        timeoutSeconds: () -> Int,
        candidates: () -> List<StreamItem>,
        onFallback: (StreamItem) -> Unit,
        onExhausted: () -> Unit = {},
    ) = DeadLinkStartupMonitor(
        scope = scope,
        timeoutSeconds = timeoutSeconds,
        candidateStreams = candidates,
        onFallback = onFallback,
        onExhausted = onExhausted,
    )
}
