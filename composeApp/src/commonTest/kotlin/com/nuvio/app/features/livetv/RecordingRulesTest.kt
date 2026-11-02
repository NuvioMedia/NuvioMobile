package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.record.ChannelSnapshot
import com.nuvio.app.features.livetv.record.LiveTvRecording
import com.nuvio.app.features.livetv.record.RecordingRules
import com.nuvio.app.features.livetv.record.RecordingSchedule
import com.nuvio.app.features.livetv.record.RecordingStatus
import com.nuvio.app.features.livetv.record.SeriesRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingRulesTest {
    private val min = 60_000L
    private val t0 = 1_700_000_000_000L

    private fun channel(key: String = "pl|1|A", source: String = "pl") =
        ChannelSnapshot(key, source, "A", null, "http://h/live/u/p/1.ts", mapOf("User-Agent" to "x"), null)

    private fun schedule(
        id: String, start: Long, stop: Long, source: String = "pl", catchup: Boolean = false, pre: Long = 2 * min, post: Long = 5 * min,
    ) = RecordingSchedule(id, channel(source = source), "Show $id", null, null, start, stop, catchup, preMs = pre, postMs = post)

    @Test
    fun paddingWidensTheRecordingWindow() {
        assertEquals((t0 - 2 * min) to (t0 + 60 * min + 5 * min), RecordingRules.window(t0, t0 + 60 * min, 2 * min, 5 * min))
        // No padding, and padding can't be negative.
        assertEquals(t0 to t0 + 60 * min, RecordingRules.window(t0, t0 + 60 * min, 0, 0))
        assertEquals(t0 to t0 + 60 * min, RecordingRules.window(t0, t0 + 60 * min, -5 * min, -5 * min))
    }

    @Test
    fun scheduleUsesItsOwnPaddingOrTheDefaults() {
        val own = schedule("a", t0, t0 + 30 * min, pre = 10 * min, post = 15 * min)
        assertEquals(10 * min, own.pre)
        assertEquals(15 * min, own.post)
        assertEquals(t0 - 10 * min, RecordingRules.fireTime(own))
        val defaults = schedule("b", t0, t0 + 30 * min, pre = -1, post = -1)
        assertEquals(RecordingRules.PRE_MS, defaults.pre)
        assertEquals(RecordingRules.POST_MS, defaults.post)
    }

    @Test
    fun fireTimesForLiveCatchupAndRetries() {
        val live = schedule("a", t0, t0 + 30 * min)
        assertEquals(t0 - 2 * min, RecordingRules.fireTime(live))
        val save = schedule("b", t0, t0 + 30 * min, catchup = true)
        assertEquals(t0 + 30 * min + RecordingRules.CATCHUP_DELAY_MS, RecordingRules.fireTime(save))
        assertEquals(t0 + 99 * min, RecordingRules.fireTime(save.copy(nextTryMs = t0 + 99 * min)))
        assertEquals(t0 + 30 * min + 5 * min, RecordingRules.recordNowStop(t0 + 30 * min, 5 * min))
    }

    @Test
    fun overlapsCountThePaddingOnBothSides() {
        val first = schedule("a", t0, t0 + 60 * min)
        // Starts 3 minutes after the first ends: the first runs 5 minutes late and this one starts 2 early, so they overlap.
        val next = schedule("b", t0 + 63 * min, t0 + 120 * min)
        assertEquals(first, RecordingRules.clashWith(next, listOf(first, next)))
        // Starts 8 minutes after: 5 late + 2 early = 7, no overlap.
        val later = schedule("c", t0 + 68 * min, t0 + 120 * min)
        assertNull(RecordingRules.clashWith(later, listOf(first, later)))
        // Without padding, back to back is fine.
        val a = schedule("d", t0, t0 + 60 * min, pre = 0, post = 0)
        val b = schedule("e", t0 + 60 * min, t0 + 120 * min, pre = 0, post = 0)
        assertNull(RecordingRules.clashWith(b, listOf(a, b)))
    }

    @Test
    fun otherProvidersAndCatchupSavesNeverClash() {
        val first = schedule("a", t0, t0 + 60 * min)
        val otherProvider = schedule("b", t0 + 10 * min, t0 + 50 * min, source = "pl2")
        assertNull(RecordingRules.clashWith(otherProvider, listOf(first, otherProvider)))
        val save = schedule("c", t0 + 10 * min, t0 + 50 * min, catchup = true)
        assertNull(RecordingRules.clashWith(save, listOf(first, save)))
        // A catch-up save on the other side doesn't count either.
        val liveOverSave = schedule("d", t0 + 10 * min, t0 + 50 * min)
        assertNull(RecordingRules.clashWith(liveOverSave, listOf(save, liveOverSave)))
        // A schedule never clashes with itself.
        assertNull(RecordingRules.clashWith(first, listOf(first)))
    }

    @Test
    fun scheduledLookupMatchesChannelAndStart() {
        val s = schedule("a", t0, t0 + 60 * min)
        assertTrue(RecordingRules.isScheduled(listOf(s), "pl|1|A", EpgProgram(t0, t0 + 60 * min, "Show a")))
        assertFalse(RecordingRules.isScheduled(listOf(s), "pl|1|A", EpgProgram(t0 + min, t0 + 60 * min, "Show a")))
        assertFalse(RecordingRules.isScheduled(listOf(s), "pl|2|B", EpgProgram(t0, t0 + 60 * min, "Show a")))
    }

    @Test
    fun episodeKeysReadEveryGuideForm() {
        assertEquals("s2e5", RecordingRules.episodeKey("S02E05"))
        assertEquals("s2e5", RecordingRules.episodeKey("S2 E5"))
        assertEquals("s2e5", RecordingRules.episodeKey("Season 2 Episode 5"))
        assertEquals("s2e5", RecordingRules.episodeKey("2x05"))
        assertEquals("s2e5", RecordingRules.episodeKey("1.4."))
        assertEquals("e7", RecordingRules.episodeKey("Ep 7"))
        assertEquals("x:abc123", RecordingRules.episodeKey("ABC-123"))
        assertNull(RecordingRules.episodeKey(null))
        assertNull(RecordingRules.episodeKey("  "))
    }

    @Test
    fun recordingsAndSchedulesRoundTripThroughJson() {
        val rec = LiveTvRecording(
            id = "rec1", channelKey = "pl|1|A", channelName = "A", channelLogo = null, title = "Show", episode = "S1E2",
            description = "About", programStartMs = t0, programStopMs = t0 + 60 * min, startedAtMs = t0 - 2 * min, stopAtMs = t0 + 65 * min,
            endedAtMs = t0 + 65 * min, status = RecordingStatus.DONE, durationSec = 4020.5, sizeBytes = 1234L, problem = null,
            dir = "/x/rec1", seriesId = "r1", fromCatchup = true, fileName = "recording.mp4",
        )
        assertEquals(rec, LiveTvRecording.fromJson(rec.toJson(), "/x/rec1"))
        val s = schedule("a", t0, t0 + 60 * min, catchup = true).copy(attempts = 2, nextTryMs = t0 + 5 * min, seriesId = "r1")
        assertEquals(s, RecordingSchedule.fromJson(s.toJson()))
        val rule = SeriesRule("r1", channel(), "Show", keepLast = 3, seen = listOf("s1e1", "s1e2"))
        assertEquals(rule, SeriesRule.fromJson(rule.toJson()))
        assertNotNull(rule.norm)
        assertEquals("show", rule.norm)
    }
}
