@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.nuvio.android

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.app.MainActivity
import com.nuvio.app.features.tvremote.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Synthetic HTTPS fixture only; run on a disposable emulator with -e tvPin <SHA256>. */
@RunWith(AndroidJUnit4::class)
class TvRemoteBackgroundTest {
    @Test fun prepareIdleConnectionForProcessRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pin = requireNotNull(InstrumentationRegistry.getArguments().getString("tvPin"))
        TvCredentialStore(context).write(PairedTv("tv-resume-test-1234", "Test TV", "10.0.2.2", 18443, pin, "b".repeat(43)))
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while ((!AndroidTvRemote.initialized || !AndroidTvRemote.repository.state.value.connected) &&
                SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            assertTrue(AndroidTvRemote.repository.state.value.connected)
        }
        // Deliberately retain this synthetic pairing for the host-driven process-kill check.
    }

    @Test fun tvStartsAfterPhoneActivityCloses() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val pin = requireNotNull(InstrumentationRegistry.getArguments().getString("tvPin"))
        val tv = PairedTv("tv-resume-test-1234", "Test TV", "10.0.2.2", 18443, pin, "b".repeat(43))
        TvCredentialStore(context).write(tv)
        val fixture = TvRemoteTransport(tv)
        fun setPlayback(action: String) = runBlocking(Dispatchers.IO) {
            fixture.command(RemoteCommand(UUID.randomUUID().toString(), "test-movie-session", action))
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        fun notification() = manager.activeNotifications.firstOrNull { it.id == TV_NOTIFICATION_ID }?.notification
        fun awaitCondition(message: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (predicate()) return
                Thread.sleep(100)
            }
            fail(message)
        }
        setPlayback("fixture-idle")
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                awaitCondition("Idle TV connection should already be foreground") {
                    AndroidTvRemote.initialized && AndroidTvRemote.repository.state.value.connected &&
                        notification()?.let { n -> n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 &&
                            !n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) } == true
                }
            }
            Thread.sleep(10_000) // No Activity and no remaining user-interaction FGS exemption.
            assertFalse(AndroidTvRemote.visible)
            repeat(2) {
                setPlayback("fixture-playing")
                awaitCondition("TV playback should publish media controls without opening Nuvio") {
                    notification()?.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
                }
                assertEquals(context.getString(com.nuvio.app.R.string.app_name),
                    notification()?.extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
                assertFalse(AndroidTvRemote.visible)
                setPlayback("fixture-idle")
                awaitCondition("Finishing playback should retain the foreground connection") {
                    notification()?.let { n -> n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 &&
                        !n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) } == true
                }
            }
            // Validate the actual notification action, including cleanup while backgrounded.
            requireNotNull(notification()).actions.single().actionIntent.send()
            awaitCondition("Disconnect must remove the foreground notification") { notification() == null }
            assertTrue(AndroidTvRemote.repository.state.value.disconnected)
        } finally {
            instrumentation.runOnMainSync { if (AndroidTvRemote.initialized) AndroidTvRemote.repository.forget() }
            fixture.close()
        }
    }
}
