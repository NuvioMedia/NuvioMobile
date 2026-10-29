package com.nuvio.app.features.tracking

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackingScrobbleCoordinatorTest {
    @Test
    fun `fanout isolates one provider failure`() = runBlocking {
        val successful = FakeScrobbler(TrackingProviderId.TRAKT)
        val failing = FakeScrobbler(TrackingProviderId.SIMKL, failure = IllegalStateException("offline"))
        val event = TrackingScrobbleEvent(
            media = TrackingMediaReference(
                kind = TrackingMediaKind.MOVIE,
                ids = TrackingExternalIds(imdb = "tt0111161"),
            ),
            progressPercent = 42.5,
        )

        val failures = dispatchTrackingScrobble(
            scrobblers = listOf(successful, failing),
            profileId = 2,
            action = TrackingScrobbleAction.PAUSE,
            event = event,
        )

        assertEquals(1, successful.callCount)
        assertEquals(1, failing.callCount)
        assertEquals(listOf(TrackingProviderId.SIMKL), failures.map(TrackingScrobbleFailure::providerId))
    }

    @Test
    fun `seek fanout targets only providers that restart scrobbles`() = runBlocking {
        val trakt = FakeScrobbler(
            providerId = TrackingProviderId.TRAKT,
            seekScrobblePolicy = TrackingSeekScrobblePolicy.STOP_AND_RESTART,
        )
        val simkl = FakeScrobbler(TrackingProviderId.SIMKL)
        val event = TrackingScrobbleEvent(
            media = TrackingMediaReference(
                kind = TrackingMediaKind.MOVIE,
                ids = TrackingExternalIds(imdb = "tt0111161"),
            ),
            progressPercent = 55.0,
        )

        dispatchTrackingSeekScrobble(
            scrobblers = listOf(trakt, simkl),
            profileId = 2,
            action = TrackingScrobbleAction.STOP,
            event = event,
        )

        assertEquals(1, trakt.callCount)
        assertEquals(0, simkl.callCount)
    }

    @Test
    fun `a sessionless event reaches only the scrobblers that take it`() = runBlocking {
        val simkl = FakeScrobbler(TrackingProviderId.SIMKL, acceptsSessionlessScrobbles = true)
        val trakt = FakeScrobbler(TrackingProviderId.TRAKT)
        val event = TrackingScrobbleEvent(
            media = TrackingMediaReference(
                kind = TrackingMediaKind.MOVIE,
                ids = TrackingExternalIds(imdb = "tt0111161"),
            ),
            progressPercent = 90.0,
            sessionless = true,
        )

        dispatchTrackingScrobble(
            scrobblers = listOf(simkl, trakt),
            profileId = 2,
            action = TrackingScrobbleAction.PAUSE,
            event = event,
        )

        assertEquals(1, simkl.callCount)
        assertEquals(0, trakt.callCount)

        // An event a session owns reaches every one of them.
        dispatchTrackingScrobble(
            scrobblers = listOf(simkl, trakt),
            profileId = 2,
            action = TrackingScrobbleAction.PAUSE,
            event = event.copy(sessionless = false),
        )

        assertEquals(2, simkl.callCount)
        assertEquals(1, trakt.callCount)
    }

    private class FakeScrobbler(
        override val providerId: TrackingProviderId,
        override val seekScrobblePolicy: TrackingSeekScrobblePolicy = TrackingSeekScrobblePolicy.NONE,
        override val acceptsSessionlessScrobbles: Boolean = false,
        private val failure: Throwable? = null,
    ) : TrackingScrobbler {
        var callCount: Int = 0

        override suspend fun scrobble(
            profileId: Int,
            action: TrackingScrobbleAction,
            event: TrackingScrobbleEvent,
        ) {
            callCount += 1
            failure?.let { throw it }
        }
    }
}
