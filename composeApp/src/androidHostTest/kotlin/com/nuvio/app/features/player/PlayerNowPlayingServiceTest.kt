package com.nuvio.app.features.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Parcel
import com.nuvio.app.core.build.AppFeaturePolicy
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class PlayerNowPlayingServiceTest {
    private lateinit var context: RecordingServiceContext

    @Before
    fun prepare() {
        assumeTrue(AppFeaturePolicy.mediaPlaybackForegroundServiceEnabled)
        context = RecordingServiceContext(RuntimeEnvironment.getApplication())
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(NOW_PLAYING_CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW),
        )
        PlayerNowPlayingService.hide(context)
        context.takeCall("stop")
    }

    @Test
    fun eachDelayedStartStillPromotesAfterRapidPublishHideCycles() {
        val first = startThenHide("First")
        val second = startThenHide("Second")
        val controller = Robolectric.buildService(PlayerNowPlayingService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(first, 0, 1)
            assertEquals("First", shadowOf(service).lastForegroundNotification?.extras?.getString(Notification.EXTRA_TITLE))
            service.onStartCommand(second, 0, 2)
            assertEquals("Second", shadowOf(service).lastForegroundNotification?.extras?.getString(Notification.EXTRA_TITLE))
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun startupNotificationSurvivesIntentParcelingAndServiceRecreation() {
        val request = startThenHide("Restarted")
        val first = Robolectric.buildService(PlayerNowPlayingService::class.java).create()
        first.get().onStartCommand(request, 0, 1)
        first.destroy()

        val parcel = Parcel.obtain()
        val restored = try {
            request.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Intent.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
        val recreated = Robolectric.buildService(PlayerNowPlayingService::class.java).create()
        try {
            recreated.get().onStartCommand(restored, 0, 1)
            assertEquals("Restarted", shadowOf(recreated.get()).lastForegroundNotification?.extras?.getString(Notification.EXTRA_TITLE))
            assertTrue(shadowOf(recreated.get()).isStoppedBySelf)
        } finally {
            recreated.destroy()
        }
    }

    @Test
    fun activePlaybackKeepsTheLatestNotificationWhenAnOlderStartArrives() {
        PlayerNowPlayingService.publish(context, notification("Initial"))
        val request = context.takeCall("start")
        PlayerNowPlayingService.publish(context, notification("Updated"))
        val controller = Robolectric.buildService(PlayerNowPlayingService::class.java).create()
        try {
            controller.get().onStartCommand(request, 0, 1)
            val manager = context.getSystemService(NotificationManager::class.java)
            val current = assertNotNull(shadowOf(manager).getNotification(NOW_PLAYING_NOTIFICATION_ID))
            assertEquals("Updated", current.extras.getString(Notification.EXTRA_TITLE))
        } finally {
            PlayerNowPlayingService.hide(context)
            context.takeCall("stop")
            controller.destroy()
        }
    }

    private fun startThenHide(title: String): Intent {
        PlayerNowPlayingService.publish(context, notification(title))
        val request = context.takeCall("start")
        PlayerNowPlayingService.hide(context)
        context.takeCall("stop")
        return request
    }

    private fun notification(title: String): Notification =
        Notification.Builder(context, NOW_PLAYING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .build()

    private class RecordingServiceContext(base: Context) : ContextWrapper(base) {
        private val calls = LinkedBlockingQueue<Pair<String, Intent>>()

        override fun getApplicationContext(): Context = this

        override fun startForegroundService(service: Intent): ComponentName? {
            calls.put("start" to service)
            return service.component
        }

        override fun stopService(service: Intent): Boolean {
            calls.put("stop" to service)
            return true
        }

        fun takeCall(expected: String): Intent {
            val call = assertNotNull(calls.poll(5, TimeUnit.SECONDS))
            assertEquals(expected, call.first)
            return call.second
        }
    }
}
