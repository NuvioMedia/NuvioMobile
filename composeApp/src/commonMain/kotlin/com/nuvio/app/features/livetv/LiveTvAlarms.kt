package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.record.LiveTvRecordingScheduler
import com.nuvio.app.features.livetv.sports.SportsLiveWatch
import com.nuvio.app.features.livetv.sports.SportsReminders

/**
 * One alarm receiver for all of Live TV: the id says what it's for. Recordings use plain ids;
 * game reminders start with [SPORTS]; programme reminders with [REMINDER]; your teams'
 * kick-offs (the live score) with [LIVE].
 */
internal object LiveTvAlarms {
    const val SPORTS = "sport:"
    const val REMINDER = "rem:"
    const val LIVE = "live:"

    /** An alarm went off (or the app started and a time had passed). */
    fun fire(id: String) {
        when {
            id.startsWith(SPORTS) -> SportsReminders.fire(id.removePrefix(SPORTS))
            id.startsWith(LIVE) -> SportsLiveWatch.fromAlarm(id.removePrefix(LIVE))
            id.startsWith(REMINDER) -> com.nuvio.app.features.livetv.reminders.LiveTvReminders.fire(id.removePrefix(REMINDER))
            else -> LiveTvRecordingScheduler.fire(id)
        }
    }

    /** The phone restarted: every alarm set again. */
    fun rearmAll() {
        LiveTvRecordingScheduler.start()
        SportsReminders.start()
        com.nuvio.app.features.livetv.reminders.LiveTvReminders.start()
    }
}
