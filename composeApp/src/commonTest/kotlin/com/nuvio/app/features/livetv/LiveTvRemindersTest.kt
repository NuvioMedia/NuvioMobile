package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.data.LiveTvCodec
import com.nuvio.app.features.livetv.model.Reminder
import com.nuvio.app.features.livetv.reminders.LiveTvReminders
import com.nuvio.app.features.livetv.sports.SportsReminders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTvRemindersTest {
    private val min = 60_000L
    private val now = 1_800_000_000_000L

    @Test
    fun fireTimeIsTheLeadBeforeTheShowOrStraightAway() {
        assertEquals(now + 60 * min - 5 * min, LiveTvReminders.fireTime(now + 60 * min, 5 * min, now))
        assertEquals(now + 60 * min, LiveTvReminders.fireTime(now + 60 * min, 0L, now))
        // The lead has already passed: as soon as the alarm can go off.
        assertEquals(now + 1_000L, LiveTvReminders.fireTime(now + 2 * min, 5 * min, now))
        assertEquals(now + 1_000L, LiveTvReminders.fireTime(now - 2 * min, 0L, now))
        // Game reminders work the same way.
        assertEquals(now + 15 * min, SportsReminders.fireTime(now + 30 * min, 15 * min, now))
    }

    @Test
    fun aReminderIsDueUntilTenMinutesAfterTheStart() {
        assertTrue(LiveTvReminders.isDue(now + 60 * min, now))
        assertTrue(LiveTvReminders.isDue(now - 9 * min, now))
        assertFalse(LiveTvReminders.isDue(now - 11 * min, now))
        assertEquals("Starting now", LiveTvReminders.heading(now, now))
        assertEquals("Starting in a minute", LiveTvReminders.heading(now + 90_000L, now))
        assertEquals("Starting in 5 minutes", LiveTvReminders.heading(now + 5 * min + 10_000L, now))
    }

    @Test
    fun idsRoundTripChannelKeysWithBars() {
        val r = Reminder("pl|bbc.uk|BBC One HD", "BBC One", "News at Ten", now)
        val id = LiveTvReminders.idOf(r)
        assertEquals("pl|bbc.uk|BBC One HD" to now, LiveTvReminders.parseId(id))
        assertNull(LiveTvReminders.parseId("nothing"))
    }

    @Test
    fun remindersKeepTheTvAppsJsonShape() {
        val list = listOf(Reminder("pl|a|A", "A", "Show A", now), Reminder("pl|b|B", "B", "Show B", now + min))
        val json = LiveTvCodec.encodeReminders(list)
        assertTrue(json.contains("\"k\":\"pl|a|A\"") && json.contains("\"s\":$now"))
        assertEquals(list, LiveTvCodec.decodeReminders(json))
    }
}
