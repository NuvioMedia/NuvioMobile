@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.nuvio.android

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.app.MainActivity
import com.nuvio.app.features.tvremote.AndroidTvRemote
import com.nuvio.app.features.tvremote.PairedTv
import com.nuvio.app.features.tvremote.TvCredentialStore
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run with an HTTPS playback fixture on the emulator host and -e tvPin <certificate SHA256>. */
@RunWith(AndroidJUnit4::class)
class TvRemoteResumeTest {
    @Test fun reopensWhileTvPlaybackContinues() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val pin = requireNotNull(InstrumentationRegistry.getArguments().getString("tvPin"))
        TvCredentialStore(context).write(PairedTv("tv-resume-test-1234", "Test TV", "10.0.2.2", 18443, pin, "b".repeat(43)))
        fun launch() = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        fun awaitPlayback() {
            val deadline = android.os.SystemClock.elapsedRealtime() + 15000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (AndroidTvRemote.initialized && AndroidTvRemote.repository.state.value.canControl) return
                Thread.sleep(100)
            }
            assertTrue("Expected connected TV playback: ${AndroidTvRemote.repository.state.value}", false)
        }
        var activity = launch()
        try {
            awaitPlayback()
            repeat(8) {
                activity.moveToState(Lifecycle.State.CREATED)
                Thread.sleep(500)
                activity.moveToState(Lifecycle.State.RESUMED)
                awaitPlayback()
            }
            activity.recreate()
            awaitPlayback()
            repeat(3) {
                activity.close()
                Thread.sleep(500)
                activity = launch()
                awaitPlayback()
            }
            Thread.sleep(6000) // Allow Android's foreground-service deadline to expire.
            assertTrue(AndroidTvRemote.repository.state.value.canControl)
        } finally {
            activity.close()
            instrumentation.runOnMainSync { AndroidTvRemote.repository.forget() }
        }
    }
}
