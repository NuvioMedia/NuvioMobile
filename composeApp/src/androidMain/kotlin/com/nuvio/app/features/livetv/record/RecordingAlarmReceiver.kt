package com.nuvio.app.features.livetv.record

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.nuvio.app.features.livetv.LiveTvAlarms
import com.nuvio.app.features.livetv.LiveTvPreferencesAndroid
import com.nuvio.app.features.livetv.data.LiveTvRepository

/** Wakes the app for a scheduled recording or a reminder, and sets the alarms again after the phone restarts. */
class RecordingAlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_FIRE = "com.nuvio.app.livetv.RECORD"
        const val EXTRA_ID = "id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // The app may not be running: Live TV's stored data has to be in place first.
        LiveTvPreferencesAndroid.initialize(app)
        RecordingPlatform.initialize(app)
        when (intent.action) {
            ACTION_FIRE -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return
                val recording = !id.startsWith(LiveTvAlarms.SPORTS) && !id.startsWith(LiveTvAlarms.REMINDER)
                // Keep the app awake long enough for the recording service (or the notification) to take over.
                val pending = goAsync()
                if (recording) LiveTvRecordingService.start(app)
                LiveTvRepository.ensureLoaded(updateCheckDelayMs = 0L)
                LiveTvRecordingScheduler.start()
                LiveTvAlarms.fire(id)
                Handler(Looper.getMainLooper()).postDelayed({ runCatching { pending.finish() } }, 8_000)
            }
            else -> LiveTvAlarms.rearmAll() // boot: set the alarms again
        }
    }
}
