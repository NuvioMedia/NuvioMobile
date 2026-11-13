package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerSourceFallbackRunTest {
    private val sources = (1..4).map { index ->
        StreamItem(name = "Source $index", url = "https://example.com/source-$index.mkv", addonName = "Test", addonId = "test")
    }
    private val expired = PlayerSourceResolveAttempt.Failed("expired")

    @Test
    fun aSourceThatResolvesPlaysWithoutAFallback() = runBlocking {
        val run = run(outcomes = mapOf(sources[0] to resolved(sources[0])))

        assertEquals("Source 1", run.resolved)
        assertEquals(0, run.tried.attempts)
        assertEquals(setOfNotNull(sources[0].playerSourceIdentityKey()), run.tried.keys)
    }

    @Test
    fun anExpiredDebridResultFallsBackToTheNextSource() = runBlocking {
        val run = run(outcomes = mapOf(sources[0] to expired, sources[1] to resolved(sources[1])))

        assertEquals("Source 2", run.resolved)
        assertNull(run.failure)
        // The source that plays counts, the way the player counts the ones it falls back to.
        assertEquals(1, run.tried.attempts)
        assertEquals(sources.take(2).mapNotNull { it.playerSourceIdentityKey() }.toSet(), run.tried.keys)
    }

    @Test
    fun theConfiguredNumberOfFurtherSourcesIsTheLimit() = runBlocking {
        val run = run(outcomes = sources.associateWith { expired }, maxSources = 2)

        assertNull(run.resolved)
        assertEquals("expired", run.failure)
        assertEquals(2, run.tried.attempts)
    }

    @Test
    fun withTheFallbackOffOnlyTheFirstSourceIsTried() = runBlocking {
        val run = run(outcomes = mapOf(sources[0] to PlayerSourceResolveAttempt.Failed("not cached")), maxSources = 0)

        assertNull(run.resolved)
        assertEquals("not cached", run.failure)
        assertEquals(0, run.tried.attempts)
    }

    private fun resolved(stream: StreamItem) = PlayerSourceResolveAttempt.Resolved(stream.name.orEmpty())

    /** Picks the first untried source, like the auto selection in first-stream mode. */
    private suspend fun run(
        outcomes: Map<StreamItem, PlayerSourceResolveAttempt<String, String>>,
        maxSources: Int = 3,
    ): PlayerSourceFallbackRun<String, String> = resolveWithSourceFallback(
        first = sources[0],
        maxSources = maxSources,
        resolve = { stream -> outcomes[stream] ?: error("${stream.name} was not expected to be tried") },
        nextCandidate = { _, triedKeys -> sources.firstOrNull { it.playerSourceIdentityKey() !in triedKeys } },
    )
}
