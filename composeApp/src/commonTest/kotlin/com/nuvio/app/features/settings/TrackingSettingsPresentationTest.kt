package com.nuvio.app.features.settings

import com.nuvio.app.features.simkl.SimklConnectionMode
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.trakt.MoreLikeThisSourcePreference
import com.nuvio.app.features.trakt.TraktConnectionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackingSettingsPresentationTest {
    @Test
    fun `provider connection modes map to matching card modes`() {
        assertEquals(
            TrackingConnectionCardMode.DISCONNECTED,
            TraktConnectionMode.DISCONNECTED.toTrackingConnectionCardMode(),
        )
        assertEquals(
            TrackingConnectionCardMode.AWAITING_APPROVAL,
            TraktConnectionMode.AWAITING_APPROVAL.toTrackingConnectionCardMode(),
        )
        assertEquals(
            TrackingConnectionCardMode.CONNECTED,
            TraktConnectionMode.CONNECTED.toTrackingConnectionCardMode(),
        )
        assertEquals(
            TrackingConnectionCardMode.DISCONNECTED,
            SimklConnectionMode.DISCONNECTED.toTrackingConnectionCardMode(),
        )
        assertEquals(
            TrackingConnectionCardMode.AWAITING_APPROVAL,
            SimklConnectionMode.AWAITING_APPROVAL.toTrackingConnectionCardMode(),
        )
        assertEquals(
            TrackingConnectionCardMode.CONNECTED,
            SimklConnectionMode.CONNECTED.toTrackingConnectionCardMode(),
        )
    }

    @Test
    fun `remote brands are available only while their provider is connected`() {
        assertTrue(isTrackingBrandAvailable(TrackingBrand.NUVIO, false, false))
        assertTrue(isTrackingBrandAvailable(TrackingBrand.TMDB, false, false))
        assertFalse(isTrackingBrandAvailable(TrackingBrand.TRAKT, false, true))
        assertTrue(isTrackingBrandAvailable(TrackingBrand.TRAKT, true, false))
        assertFalse(isTrackingBrandAvailable(TrackingBrand.SIMKL, true, false))
        assertTrue(isTrackingBrandAvailable(TrackingBrand.SIMKL, false, true))
    }

    @Test
    fun `Trakt recommendations fall back visually without rewriting preference`() {
        val stored = MoreLikeThisSourcePreference.TRAKT

        assertEquals(
            MoreLikeThisSourcePreference.TMDB,
            effectiveTrackingRecommendationsSource(stored, traktConnected = false),
        )
        assertEquals(
            MoreLikeThisSourcePreference.TRAKT,
            effectiveTrackingRecommendationsSource(stored, traktConnected = true),
        )
        assertEquals(MoreLikeThisSourcePreference.TRAKT, stored)
    }

    @Test
    fun `Trakt continue watching window is only enabled while Trakt is the active watch progress source`() {
        assertTrue(isTraktContinueWatchingWindowEnabled(WatchProgressSource.TRAKT, true, false))
        assertFalse(isTraktContinueWatchingWindowEnabled(WatchProgressSource.SIMKL, true, true))
        assertFalse(isTraktContinueWatchingWindowEnabled(WatchProgressSource.SIMKL, true, false))
        assertFalse(isTraktContinueWatchingWindowEnabled(WatchProgressSource.NUVIO_SYNC, true, false))
    }
}
