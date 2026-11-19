package com.nuvio.app.features.usenet

import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UsenetFallbackSessionTest {
    private fun stream(id: String) = StreamItem(
        name = id,
        nzbUrl = "https://example.test/$id.nzb",
        addonName = "Addon",
        addonId = "addon:test",
    )

    private fun session(selected: StreamItem, streams: List<StreamItem>, maxAttempts: Int = 5) =
        UsenetFallbackSession(selected, streams, maxAttempts, isEnabled = { true })

    @Test
    fun preparationHasOneBudgetAcrossTheSelectionAndItsFallbacks() = runTest {
        val sources = (0..6).map { stream("budget-$it") }
        val session = UsenetFallbackSession(sources[0], sources, nowMs = { testScheduler.currentTime }, isEnabled = { true })
        try {
            session.prepareCandidate(sources[0]) { delay(180_000); sources[0] }
            fail("The selected release must time out")
        } catch (_: UsenetPlaybackException) {
        }
        assertEquals(60_000L, testScheduler.currentTime)
        var attempts = 0
        assertNull(session.resolveNext { candidate, _ -> attempts++; delay(180_000); candidate })
        assertEquals(2, attempts)
        assertEquals(120_000L, testScheduler.currentTime)
        assertFalse(session.canAdvance)
        assertTrue(session.budgetExhausted)
    }

    @Test
    fun watchingTimeDoesNotUseThePreparationBudget() = runTest {
        val first = stream("watch")
        val session = UsenetFallbackSession(first, listOf(first, stream("next")), nowMs = { testScheduler.currentTime }, isEnabled = { true })
        session.prepareCandidate(first) { delay(1000); first }
        delay(3_600_000)
        assertNotNull(session.resolveNext { candidate, _ -> delay(1000); candidate })
    }

    @Test
    fun aProviderFailureSkipsTheSameAccountButNotAnotherProvider() = runTest {
        val first = stream("first").copy(nzbServers = listOf("nntps://account-a/8"))
        val sameProvider = stream("second").copy(nzbServers = first.nzbServers)
        val other = stream("third").copy(nzbServers = listOf("nntps://account-b/8"))
        val session = session(first, listOf(first, sameProvider, other))
        try {
            session.prepareCandidate(first) { throw UsenetPlaybackException("Authentication failed", scope = UsenetFailureScope.PROVIDER) }
        } catch (_: UsenetPlaybackException) {
        }
        assertEquals(other, session.next())
        assertEquals(1, session.attempts)
    }

    @Test
    fun anEngineFailureStopsFallbackAndKeepsItsExplanation() = runTest {
        val first = stream("first")
        val session = session(first, listOf(first, stream("next")))
        try {
            session.prepareCandidate(first) { throw UsenetPlaybackException("Engine missing", scope = UsenetFailureScope.ENGINE) }
        } catch (_: UsenetPlaybackException) {
        }
        assertFalse(session.canAdvance)
        assertNull(session.next())
        assertEquals("Engine missing", session.lastFailure?.message)
    }

    @Test
    fun theNextAttemptSaysWhyTheLastReleaseFailed() = runTest {
        val first = stream("first")
        val session = session(first, listOf(first) + (1..5).map { stream("$it") })
        suspend fun failWith(error: Exception) {
            try {
                session.prepareCandidate(first) { throw error }
            } catch (_: Exception) {
            }
        }
        failWith(UsenetPlaybackException("article not found", failure = "missing-article"))
        assertEquals("Articles missing on your providers", session.lastFailureReason)
        failWith(UsenetPlaybackException("missing RAR continuation at https://indexer.test/api?apikey=secret\nmore"))
        assertEquals("missing RAR continuation at https://indexer.test/…", session.lastFailureReason)
        try {
            session.prepareCandidate(first) { delay(200_000); first }
        } catch (_: Exception) {
        }
        assertEquals("Took too long to open", session.lastFailureReason)
        session.playbackFailed()
        assertEquals("Playback failed", session.lastFailureReason)
    }

    @Test
    fun releasesThatAlreadyFailedForGoodAreSkipped() {
        val first = stream("first")
        val dead = stream("dead")
        val next = stream("next")
        val session = UsenetFallbackSession(first, listOf(first, dead, next), isEnabled = { true }, isUnplayable = { it == dead })
        assertEquals(next, session.next())
        assertEquals(1, session.attempts)
    }

    @Test
    fun fallbackIsOffByDefault() = runTest {
        val first = stream("first")
        val session = UsenetFallbackSession(first, listOf(first, stream("next")))
        assertFalse(session.canAdvance)
        assertNull(session.resolveNext { _, _ -> error("Must not open anything with fallback off") })
        assertEquals(0, session.attempts)
    }

    @Test
    fun torrentAndHttpSelectionsNeverFallBack() = runTest {
        val torrent = stream("torrent").copy(nzbUrl = null, infoHash = "abc123")
        val http = stream("debrid").copy(nzbUrl = null, url = "https://example.test/video")
        for (selected in listOf(torrent, http)) {
            val session = session(selected, listOf(selected, stream("next"), torrent, http))
            session.resolved(selected)
            assertFalse(session.enabled)
            assertFalse(session.canAdvance)
            assertFalse(session.canFallbackFrom(selected.playableDirectUrl))
            val resolvedUrl = "http://127.0.0.1:1234/torrent/playback"
            session.resolved(selected.copy(url = resolvedUrl))
            assertFalse(session.ownsPlayback(resolvedUrl))
            assertFalse(session.canFallbackFrom(resolvedUrl))
            assertNull(session.resolveNext { _, _ -> error("Other sources must not fall back") })
            assertEquals(0, session.attempts)
        }
    }

    @Test
    fun usenetFallbackSkipsTorrentsAndHttpSourcesInMixedResults() {
        val first = stream("first")
        val next = stream("next")
        val session = session(
            first,
            listOf(
                first,
                stream("torrent").copy(nzbUrl = null, infoHash = "abc123"),
                stream("http").copy(nzbUrl = null, url = "https://example.test/video"),
                next,
            ),
        )
        assertEquals(next, session.next())
        assertEquals(1, session.attempts)
        assertNull(session.next())
    }

    @Test
    fun playbackFallbackNeedsTheOpenedEngineUrlAndTheSwitchOn() {
        var enabled = true
        val first = stream("first")
        val session = UsenetFallbackSession(first, listOf(first, stream("next")), isEnabled = { enabled })
        val engineUrl = "http://127.0.0.1:1234/stream/session-a/movie.mkv"
        assertFalse(session.canFallbackFrom(engineUrl))
        assertFalse(session.ownsPlayback(engineUrl))
        session.resolved(first.copy(url = engineUrl, nzbServers = emptyList()))
        assertTrue(session.ownsPlayback(engineUrl))
        assertTrue(session.canFallbackFrom(engineUrl))
        assertFalse(session.canFallbackFrom("http://127.0.0.1:1234/torrent/session-b"))
        assertFalse(session.canFallbackFrom("https://example.test/video"))
        UsenetFallbackHandoff.put(engineUrl, session)
        enabled = false
        val restored = UsenetFallbackHandoff.take(engineUrl)!!
        assertTrue(restored.ownsPlayback(engineUrl))
        assertFalse(restored.canFallbackFrom(engineUrl))
        assertNull(restored.next())
        assertEquals(0, restored.attempts)
    }

    @Test
    fun turningFallbackOffWhileItOpensStopsTheReplacement() = runTest {
        var enabled = true
        val first = stream("first")
        val session = UsenetFallbackSession(first, listOf(first, stream("next")), isEnabled = { enabled })
        assertNull(
            session.resolveNext { candidate, _ ->
                enabled = false
                candidate.copy(url = "http://127.0.0.1/resolved")
            },
        )
        assertFalse(session.canAdvance)
        assertEquals(1, session.attempts)
    }

    @Test
    fun startsAfterTheSelectedReleaseAndNeverWraps() {
        val sources = listOf(stream("a"), stream("b"), stream("c"))
        val session = session(sources[1], sources)
        assertEquals(sources[2], session.next())
        assertNull(session.next())
        assertFalse(session.canAdvance)
    }

    @Test
    fun aSelectionMissingFromTheListStartsAtTheFirstRelease() {
        val next = stream("next")
        assertEquals(next, session(stream("cached"), listOf(next)).next())
    }

    @Test
    fun selectingACopyInALaterAddonDoesNotGoBackwards() {
        val first = stream("same")
        val selected = first.copy(addonName = "Second addon")
        val next = stream("next")
        val session = session(selected, listOf(first, stream("earlier"), selected, next))
        assertEquals(next, session.next())
        assertNull(session.next())
    }

    @Test
    fun copiesAcrossAddonsDoNotUseAttempts() {
        val first = stream("first")
        val second = stream("second")
        val session = session(
            first,
            listOf(first, first.copy(addonName = "Other", name = "Other title"), second, second.copy(addonName = "Third"), stream("third")),
            maxAttempts = 2,
        )
        assertEquals(second, session.next())
        assertEquals(stream("third"), session.next())
        assertEquals(2, session.attempts)
        assertNull(session.next())
    }

    @Test
    fun theAttemptLimitHoldsAcrossAnOpenAndALaterPlaybackFailure() = runTest {
        val first = stream("first")
        val second = stream("second")
        val session = session(first, listOf(first, second, stream("third")), maxAttempts = 1)
        assertEquals(second, session.resolveNext { candidate, _ -> candidate })
        assertNull(session.resolveNext { candidate, _ -> candidate })
    }

    @Test
    fun fiveAttemptsIsTheDefaultEvenWithHundredsOfResults() {
        val first = stream("first")
        val session = session(first, (1..100).map { stream("$it") })
        repeat(5) { assertNotNull(session.next()) }
        assertNull(session.next())
    }

    @Test
    fun fiftyAttemptsCanBeConfiguredAndAreTheCap() = runTest {
        val sources = (0..60).map { stream("$it") }
        val session = session(sources.first(), sources, maxAttempts = 50)
        assertEquals(
            sources[50],
            session.resolveNext { candidate, attempt ->
                if (attempt < 50) error("unavailable")
                candidate
            },
        )
        assertEquals(50, session.attempts)
        assertFalse(session.canAdvance)
        val capped = session(sources.first(), sources, maxAttempts = 100)
        repeat(50) { assertNotNull(capped.next()) }
        assertNull(capped.next())
    }

    @Test
    fun externalYouTubeAndUnusableEntriesAreSkipped() {
        val first = stream("first")
        val valid = stream("valid")
        val session = session(
            first,
            listOf(
                first,
                stream("browser").copy(nzbUrl = null, externalUrl = "https://example.test/page"),
                stream("youtube").copy(ytId = "abc"),
                stream("empty").copy(nzbUrl = null),
                stream("intent").copy(url = "intent://other-app"),
                valid,
            ),
        )
        assertEquals(valid, session.next())
        assertEquals(1, session.attempts)
    }

    @Test
    fun aReleaseIsIdentifiedByItsFileSelectionAndKeepsItWhenOpened() {
        val first = stream("nzb").copy(fileIdx = 0)
        val next = first.copy(fileIdx = 1)
        val session = session(first, listOf(first, first.copy(url = "http://127.0.0.1/stream"), next))
        assertEquals(next, session.next())
        assertNull(session.next())
    }

    @Test
    fun failedOpensAdvanceUntilAReleaseOpens() = runTest {
        val sources = (0..3).map { stream("$it") }
        val attempted = mutableListOf<StreamItem>()
        val session = session(sources[0], sources)
        val result = session.resolveNext { candidate, attempt ->
            attempted += candidate
            if (attempt < 3) error("unavailable")
            candidate
        }
        assertEquals(sources[3], result)
        assertEquals(sources.drop(1), attempted)
        assertNull(session.next())
    }

    @Test
    fun cancellationStopsAtOnceWithoutTryingAnotherRelease() = runTest {
        val sources = (0..3).map { stream("$it") }
        val session = session(sources[0], sources)
        var calls = 0
        try {
            session.resolveNext { _, _ -> calls++; throw CancellationException("user left") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(1, calls)
            assertEquals(1, session.attempts)
        }
    }

    @Test
    fun anOpenThatReturnsAfterCancellationCannotStartPlayback() = runTest {
        val first = stream("first")
        val session = session(first, listOf(first, stream("second"), stream("third")))
        var startedPlayback = false
        val job = launch {
            session.resolveNext { candidate, _ ->
                coroutineContext.cancel()
                candidate
            }
            startedPlayback = true
        }
        runCurrent()
        assertTrue(job.isCancelled)
        assertFalse(startedPlayback)
        assertEquals(1, session.attempts)
    }

    @Test
    fun anExhaustedChainStaysExhausted() = runTest {
        val first = stream("first")
        val session = session(first, listOf(first, stream("second")))
        assertNull(session.resolveNext { _, _ -> error("unavailable") })
        assertFalse(session.canAdvance)
        assertNull(session.resolveNext { _, _ -> fail("Must not retry") })
    }

    @Test
    fun theHandoffKeepsTheAttemptsAndIsKeyedByTheEngineUrl() {
        val sources = (0..3).map { stream("handoff-$it") }
        val session = session(sources[0], sources)
        session.next()
        UsenetFallbackHandoff.put("http://127.0.0.1:1/stream/a/x.mkv", session)
        assertNull(UsenetFallbackHandoff.take("http://127.0.0.1:1/stream/b/x.mkv"))
        val restored = UsenetFallbackHandoff.take("http://127.0.0.1:1/stream/a/x.mkv")!!
        assertSame(session, restored)
        assertEquals(sources[2], restored.next())
        assertNull(UsenetFallbackHandoff.take("http://127.0.0.1:1/stream/a/x.mkv"))
    }

    @Test
    fun fallbackSettingsAreKeptWithinRange() {
        assertEquals(50, UsenetConfiguration(fallbackMaxAttempts = 400).sanitized().fallbackMaxAttempts)
        assertEquals(1, UsenetConfiguration(fallbackMaxAttempts = 0).sanitized().fallbackMaxAttempts)
        assertFalse(UsenetConfiguration().fallbackEnabled)
        assertEquals(5, UsenetConfiguration().fallbackMaxAttempts)
    }
}
