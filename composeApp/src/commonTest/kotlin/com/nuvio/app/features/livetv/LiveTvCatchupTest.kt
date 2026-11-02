package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.model.CatchupInfo
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvSettings
import com.nuvio.app.features.livetv.model.PlaylistSource
import com.nuvio.app.features.livetv.player.LiveTvCatchup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTvCatchupTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_700_000_000_000L

    private fun channel(catchup: CatchupInfo? = CatchupInfo("xc", null, 3)) = LiveChannel(
        key = "pl|id|Chan", sourceId = "pl", sourceName = "Provider", name = "Chan", tvgId = "id", tvgName = null,
        logo = null, groupId = "g", group = "G", number = 1,
        url = "http://host:80/live/user/pass/42.ts", catchup = catchup
    )

    private fun show(startMs: Long, stopMs: Long) = EpgProgram(startMs, stopMs, "Show")

    @Test
    fun pastShowInsideTheWindowIsReplayable() {
        assertTrue(LiveTvCatchup.isReplayable(channel(), show(now - 5 * hour, now - 4 * hour), now))
    }

    @Test
    fun showOnNowCanBeWatchedFromTheStart() {
        val onNow = show(now - 20 * 60_000L, now + 40 * 60_000L)
        assertTrue(LiveTvCatchup.isReplayable(channel(), onNow, now))
        assertTrue(LiveTvCatchup.canWatchFromStart(channel(), onNow, now))
    }

    @Test
    fun futureShowIsNotReplayable() {
        val later = show(now + hour, now + 2 * hour)
        assertFalse(LiveTvCatchup.isReplayable(channel(), later, now))
        assertFalse(LiveTvCatchup.canWatchFromStart(channel(), later, now))
    }

    @Test
    fun showOlderThanTheCatchupDaysIsNotReplayable() {
        val c = channel(CatchupInfo("xc", null, 2))
        assertTrue(LiveTvCatchup.isReplayable(c, show(now - 2 * day + hour, now - 2 * day + 2 * hour), now))
        assertFalse(LiveTvCatchup.isReplayable(c, show(now - 2 * day - hour, now - 2 * day), now))
    }

    @Test
    fun channelWithoutCatchupIsNeverReplayable() {
        assertFalse(LiveTvCatchup.isReplayable(channel(null), show(now - hour, now), now))
        assertFalse(LiveTvCatchup.canWatchFromStart(channel(null), show(now - hour, now + hour), now))
    }

    @Test
    fun windowRunsPastTheShowButNotPastWhatHasAired() {
        // A show that ended long ago: three hours of follow-on.
        val old = show(now - 10 * hour, now - 9 * hour)
        assertEquals(old.startMs to old.stopMs + LiveTvCatchup.CONTINUE_MS, LiveTvCatchup.window(old, 0L, now))
        // A show on now: only what has aired (half a minute behind live), never less than the show itself.
        val onNow = show(now - hour, now + hour)
        assertEquals(onNow.startMs to onNow.stopMs, LiveTvCatchup.window(onNow, 0L, now))
        val justEnded = show(now - 2 * hour, now - 10 * 60_000L)
        assertEquals(justEnded.startMs to now - 30_000, LiveTvCatchup.window(justEnded, 0L, now))
        // An offset moves the start into the show.
        assertEquals(old.startMs + 5 * 60_000L, LiveTvCatchup.window(old, 5 * 60_000L, now).first)
    }

    @Test
    fun timeShiftsCombineTheGuideOffsetAndTheCatchupShift() {
        assertEquals(0L, LiveTvCatchup.shiftMs(LiveTvSettings()))
        assertEquals(60 * 60_000L - 30 * 60_000L, LiveTvCatchup.shiftMs(LiveTvSettings(epgOffsetMinutes = 60, catchupShiftMinutes = 30)))
    }

    @Test
    fun hlsIsSkippedForAWeekAfterItFailedOnAProvider() {
        val s = LiveTvSettings(catchupPreferHls = true)
        val pl = PlaylistSource(id = "pl", name = "P", url = "", xtreamServer = "http://host")
        assertTrue(LiveTvCatchup.preferHls(s, pl, now))
        assertFalse(LiveTvCatchup.preferHls(s, pl.copy(catchupNoHlsSince = now - day), now))
        assertTrue(LiveTvCatchup.preferHls(s, pl.copy(catchupNoHlsSince = now - 8 * day), now))
        assertFalse(LiveTvCatchup.preferHls(s.copy(catchupPreferHls = false), pl, now))
    }

    @Test
    fun planBuildsAnXtreamLinkWithFallbacks() {
        val plan = LiveTvCatchup.plan(channel(), show(now - 5 * hour, now - 4 * hour), LiveTvSettings(), null, now)
        assertNotNull(plan)
        assertTrue(plan.url.contains("/timeshift/user/pass/"), plan.url)
        assertTrue(plan.url.endsWith("/42.m3u8"), plan.url)
        assertTrue(plan.preferHls)
        // TS form first, then the show-only length, then the other link forms.
        assertTrue(plan.fallbacks.first().endsWith("/42.ts"), plan.fallbacks.first())
        assertTrue(plan.fallbacks.any { it.contains("timeshift.php") })
        assertTrue(LiveTvCatchup.isHlsTimeshift(plan.url))
        assertTrue(LiveTvCatchup.isTsTimeshift(plan.fallbacks.first()))
    }

    @Test
    fun planIsNullOutsideTheWindow() {
        assertNull(LiveTvCatchup.plan(channel(), show(now + hour, now + 2 * hour), LiveTvSettings(), null, now))
        assertNull(LiveTvCatchup.plan(channel(null), show(now - hour, now), LiveTvSettings(), null, now))
    }
}
