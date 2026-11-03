package com.nuvio.app.features.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackingReadsTest {

    @Test
    fun `unified status selection maps to each provider native destination`() {
        val tabs = listOf(
            tab("local", null, TrackingLibraryTabKind.WATCHLIST),
            tab("simkl:watching", TrackingProviderId.SIMKL, TrackingLibraryTabKind.STATUS, "simkl"),
            tab("simkl:plantowatch", TrackingProviderId.SIMKL, TrackingLibraryTabKind.STATUS, "simkl"),
            tab("simkl:hold", TrackingProviderId.SIMKL, TrackingLibraryTabKind.STATUS, "simkl"),
            tab("simkl:dropped", TrackingProviderId.SIMKL, TrackingLibraryTabKind.STATUS, "simkl"),
            tab("simkl:completed", TrackingProviderId.SIMKL, TrackingLibraryTabKind.STATUS, "simkl", isMembershipDestination = false),
            tab("anilist:current", TrackingProviderId.ANILIST, TrackingLibraryTabKind.STATUS, "anilist"),
            tab("anilist:planning", TrackingProviderId.ANILIST, TrackingLibraryTabKind.STATUS, "anilist"),
            tab("anilist:paused", TrackingProviderId.ANILIST, TrackingLibraryTabKind.STATUS, "anilist"),
            tab("anilist:dropped", TrackingProviderId.ANILIST, TrackingLibraryTabKind.STATUS, "anilist"),
            tab("anilist:repeating", TrackingProviderId.ANILIST, TrackingLibraryTabKind.STATUS, "anilist"),
            tab("anilist:completed", TrackingProviderId.ANILIST, TrackingLibraryTabKind.STATUS, "anilist"),
            tab("mdblist:watchlist", TrackingProviderId.MDBLIST, TrackingLibraryTabKind.WATCHLIST),
            tab("trakt:watchlist", TrackingProviderId.TRAKT, TrackingLibraryTabKind.WATCHLIST),
            tab("trakt:personal:1", TrackingProviderId.TRAKT, TrackingLibraryTabKind.PERSONAL),
        )
        val initial = tabs.associate { it.key to false }.toMutableMap().apply {
            this["local"] = true
            this["simkl:completed"] = true
            this["trakt:personal:1"] = true
        }

        val updated = selectUnifiedTrackingStatus(
            tabs = tabs,
            membership = initial,
            status = UnifiedTrackingStatus.DROPPED,
        )

        assertTrue(updated["simkl:dropped"] == true)
        assertFalse(updated["simkl:watching"] == true)
        assertFalse(updated["simkl:completed"] == true)
        assertTrue(updated["anilist:dropped"] == true)
        assertFalse(updated["anilist:completed"] == true)
        assertTrue(updated["mdblist:watchlist"] == true)
        assertTrue(updated["trakt:watchlist"] == true)
        assertTrue(updated["trakt:personal:1"] == true)
        assertTrue(updated["local"] == true)
        assertEquals(
            UnifiedTrackingStatus.DROPPED,
            selectedUnifiedTrackingStatus(tabs, updated),
        )
    }

    @Test
    fun `single list providers expose a plan to watch fallback`() {
        val tabs = listOf(
            tab("mdblist:watchlist", TrackingProviderId.MDBLIST, TrackingLibraryTabKind.WATCHLIST),
            tab("trakt:watchlist", TrackingProviderId.TRAKT, TrackingLibraryTabKind.WATCHLIST),
            tab("trakt:personal:1", TrackingProviderId.TRAKT, TrackingLibraryTabKind.PERSONAL),
        )
        assertEquals(
            listOf(UnifiedTrackingStatus.PLAN_TO_WATCH),
            unifiedTrackingStatuses(tabs),
        )
        val updated = selectUnifiedTrackingStatus(
            tabs = tabs,
            membership = emptyMap(),
            status = UnifiedTrackingStatus.PLAN_TO_WATCH,
        )
        assertTrue(updated["mdblist:watchlist"] == true)
        assertTrue(updated["trakt:watchlist"] == true)
        assertEquals(UnifiedTrackingStatus.PLAN_TO_WATCH, selectedUnifiedTrackingStatus(tabs, updated))
    }

    private fun tab(
        key: String,
        provider: TrackingProviderId?,
        kind: TrackingLibraryTabKind,
        group: String? = null,
        isMembershipDestination: Boolean = true,
    ) = TrackingLibraryTab(
        key = key,
        title = key,
        providerId = provider,
        kind = kind,
        selectionGroup = group,
        isMembershipDestination = isMembershipDestination,
    )
}
