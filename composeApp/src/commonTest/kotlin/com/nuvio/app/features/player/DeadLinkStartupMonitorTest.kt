package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
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

    @Test
    fun discoversAlternativesWhenSourcesPanelWasNeverOpened() = runTest {
        val current = stream("Dead source", "addon:dead", "https://media.example/dead.mp4")
        val plugin = stream("Plugin alternative", "plugin:alternative", "https://media.example/plugin.mp4")
        val addon = stream("Installed addon alternative", "addon:alternative", "https://media.example/addon.mp4")
        var discoveryCalls = 0
        val fallbacks = mutableListOf<StreamItem>()
        var exhaustedCount = 0

        // No sources are cached before the timeout. Discovery only happens when
        // the watchdog requests it, matching playback without opening Sources.
        val monitor = newMonitor(
            scope = backgroundScope,
            timeoutSeconds = { 5 },
            candidates = {
                discoveryCalls++
                listOf(current, plugin, addon)
            },
            onFallback = fallbacks::add,
            onExhausted = { exhaustedCount++ },
        )

        monitor.sourceSelected(current)
        runCurrent()
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(0, discoveryCalls)
        assertTrue(fallbacks.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, discoveryCalls)
        assertEquals(listOf(addon), fallbacks)
        assertEquals(0, exhaustedCount)

        // Each failed source gets a fresh startup timeout. Attempted sources
        // are excluded, and installed addons remain ahead of plugins.
        monitor.sourceSelected(addon)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(addon, plugin), fallbacks)
        assertEquals(0, exhaustedCount)
        monitor.close()
    }

    @Test
    fun sourceChangeCancelsSuspendedDiscoveryAndCannotUseStaleResults() = runTest {
        val current = stream("Current", "addon:current", "https://media.example/current.mp4")
        val stale = stream("Stale", "addon:stale", "https://media.example/stale.mp4")
        val manuallySelected = stream("Manual", "addon:manual", "https://media.example/manual.mp4")
        val discoveryStarted = CompletableDeferred<Unit>()
        val completeDiscovery = CompletableDeferred<List<StreamItem>>()
        val fallbacks = mutableListOf<StreamItem>()

        val monitor = DeadLinkStartupMonitor(
            scope = backgroundScope,
            timeoutSeconds = { 5 },
            candidateStreams = {
                discoveryStarted.complete(Unit)
                completeDiscovery.await()
            },
            onFallback = fallbacks::add,
            onExhausted = {},
        )

        monitor.sourceSelected(current)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(discoveryStarted.isCompleted)

        // Selecting another source cancels the in-flight discovery attempt.
        monitor.sourceSelected(manuallySelected)
        runCurrent()
        completeDiscovery.complete(listOf(current, stale))
        runCurrent()

        assertTrue(fallbacks.isEmpty())
        monitor.close()
    }

    @Test
    fun emptyDiscoveryIsNotExhaustedUntilFetchCompletes() = runTest {
        val current = stream("Only source", "addon:only", "https://media.example/only.mp4")
        val discoveryStarted = CompletableDeferred<Unit>()
        val completeDiscovery = CompletableDeferred<List<StreamItem>>()
        var exhaustedCount = 0

        val monitor = DeadLinkStartupMonitor(
            scope = backgroundScope,
            timeoutSeconds = { 5 },
            candidateStreams = {
                discoveryStarted.complete(Unit)
                completeDiscovery.await()
            },
            onFallback = {},
            onExhausted = { exhaustedCount++ },
        )

        monitor.sourceSelected(current)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(discoveryStarted.isCompleted)
        assertEquals(0, exhaustedCount)

        completeDiscovery.complete(listOf(current))
        runCurrent()
        assertEquals(1, exhaustedCount)
        monitor.close()
    }

    @Test
    fun discoveryFailureRetriesAndCanStillFallback() = runTest {
        val current = stream("Dead source", "addon:dead", "https://media.example/dead.mp4")
        val next = stream("Alternative", "addon:next", "https://media.example/next.mp4")
        var discoveryCalls = 0
        val fallbacks = mutableListOf<StreamItem>()
        var exhaustedCount = 0
        val monitor = newMonitor(
            scope = backgroundScope,
            timeoutSeconds = { 5 },
            candidates = {
                discoveryCalls++
                if (discoveryCalls == 1) error("temporary discovery failure")
                listOf(current, next)
            },
            onFallback = fallbacks::add,
            onExhausted = { exhaustedCount++ },
        )

        monitor.sourceSelected(current)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(1, discoveryCalls)
        assertTrue(fallbacks.isEmpty())
        assertEquals(0, exhaustedCount)

        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(1, discoveryCalls)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, discoveryCalls)
        assertEquals(listOf(next), fallbacks)
        assertEquals(0, exhaustedCount)
        monitor.close()
    }

    @Test
    fun newlyArrivingCandidateIsTriedWhileOtherProvidersStillLoad() = runTest {
        val current = stream("Current dead link", "addon:current", "https://media.example/current.mp4")
        val next = stream("Late candidate", "addon:late", "https://media.example/late.mp4")
        var loading = true
        var candidates = listOf(current)
        val fallbacks = mutableListOf<StreamItem>()
        var exhaustedCount = 0
        val monitor = newMonitor(
            scope = backgroundScope,
            timeoutSeconds = { 5 },
            candidates = { candidates },
            onFallback = fallbacks::add,
            onExhausted = { exhaustedCount++ },
            sourceDiscoveryLoading = { loading },
        )

        monitor.sourceSelected(current)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(fallbacks.isEmpty())
        assertEquals(0, exhaustedCount)

        candidates = listOf(current, next)
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf(next), fallbacks)
        assertEquals(0, exhaustedCount)
        monitor.close()
    }

    @Test
    fun aDifferentSourceGetsAWatchdogAfterEarlierSourcePlayed() = runTest {
        val first = stream("First", "addon:first", "https://media.example/first.mp4")
        val second = stream("Second", "addon:second", "https://media.example/second.mp4")
        val third = stream("Third", "addon:third", "https://media.example/third.mp4")
        val fallbacks = mutableListOf<StreamItem>()
        val monitor = newMonitor(
            scope = backgroundScope,
            timeoutSeconds = { 5 },
            candidates = { listOf(first, second, third) },
            onFallback = fallbacks::add,
        )

        monitor.sourceSelected(first)
        runCurrent()
        monitor.playbackStateChanged(first, isPlaying = true)
        monitor.sourceSelected(second)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(listOf(third), fallbacks)
        monitor.close()
    }

    @Test
    fun fallbackChainContinuesWhenCandidateNeverBecomesActive() = runTest {
        val first = stream("First dead link", "addon:first", "https://media.example/first.mp4")
        val second = stream("Second candidate", "addon:second", "https://media.example/second.mp4")
        val third = stream("Third candidate", "addon:third", "https://media.example/third.mp4")
        val fallbacks = mutableListOf<StreamItem>()
        val markedDead = mutableListOf<StreamItem>()
        val monitor = newMonitor(
            scope = backgroundScope,
            timeoutSeconds = { 5 },
            candidates = { listOf(first, second, third) },
            // Simulate asynchronous preparation failing before activeStream changes.
            onFallback = fallbacks::add,
            onDeadLink = markedDead::add,
        )

        monitor.sourceSelected(first)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(first), markedDead)
        assertEquals(listOf(second), fallbacks)

        // No sourceSelected(second) event arrives; the activation watchdog must
        // still mark it dead and advance to the next untried source.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(second, third), fallbacks)
        assertEquals(listOf(first, second), markedDead)
        monitor.close()
    }

    private fun stream(name: String, addonId: String, url: String) =
        StreamItem(name = name, url = url, addonName = name, addonId = addonId)

    private fun newMonitor(
        scope: kotlinx.coroutines.CoroutineScope,
        timeoutSeconds: () -> Int,
        candidates: suspend () -> List<StreamItem>,
        onFallback: (StreamItem) -> Unit,
        onExhausted: () -> Unit = {},
        sourceDiscoveryLoading: () -> Boolean = { false },
        onDeadLink: (StreamItem) -> Unit = {},
    ) = DeadLinkStartupMonitor(
        scope = scope,
        timeoutSeconds = timeoutSeconds,
        candidateStreams = candidates,
        onFallback = onFallback,
        onExhausted = onExhausted,
        sourceDiscoveryLoading = sourceDiscoveryLoading,
        onDeadLink = onDeadLink,
    )
}
