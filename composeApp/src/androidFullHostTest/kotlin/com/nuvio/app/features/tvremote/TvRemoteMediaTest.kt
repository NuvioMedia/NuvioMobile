package com.nuvio.app.features.tvremote

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.os.Handler
import com.nuvio.app.MainActivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowMediaSession
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RecordingRemoteSession::class])
class TvRemoteMediaTest {
    private val tv = PairedTv("tv-device-123456", "Living room", "192.168.1.2", 1234, "a".repeat(64), "b".repeat(43))
    private class Store(val tv: PairedTv) : PairStore {
        override fun read() = tv
        override fun write(tv: PairedTv) {}
        override fun clear() {}
    }
    private class Connection : RemoteConnection {
        val snapshots = Channel<RemoteSnapshot>(Channel.UNLIMITED)
        val commands = mutableListOf<RemoteCommand>()
        override suspend fun pair(secret: String): PairResponse = error("unused")
        override suspend fun snapshot(after: Long?) = snapshots.receive()
        override suspend fun command(command: RemoteCommand) { commands.add(command) }
        override suspend fun revoke() {}
        override fun close() {}
    }

    @Test fun remoteSessionRoutesButtonsAndTapAndYieldsToLocalPlayback() = runTest {
        val context = RuntimeEnvironment.getApplication()
        TvRemoteService.hide(context)
        val connection = Connection()
        val repository = TvRemoteRepository(Store(tv), backgroundScope, { connection })
        val controller = TvRemoteMediaController(context, repository, backgroundScope) { throw java.io.IOException("Artwork failed") }
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        runCurrent()
        assertNull(assertNotNull(manager.getNotification(TV_NOTIFICATION_ID)).extras.get(Notification.EXTRA_MEDIA_SESSION))
        connection.snapshots.send(RemoteSnapshot(deviceId = tv.deviceId, deviceName = tv.deviceName,
            sessionId = "movie-session-123", title = "Movie", state = "playing", canSeek = false, artwork = "https://artwork.invalid/poster.jpg"))
        runCurrent()
        val notification = assertNotNull(manager.getNotification(TV_NOTIFICATION_ID))
        assertNull(notification.getLargeIcon()) // Artwork failure must not hide metadata or controls.
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra("nuvio.tv.remote.OPEN", false))
        assertNotNull(notification.extras.get(Notification.EXTRA_MEDIA_SESSION))
        assertEquals(context.getString(com.nuvio.app.R.string.app_name), notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        val remote = assertNotNull(RecordingRemoteSession.latest)
        assertEquals(VolumeProvider.VOLUME_CONTROL_FIXED, assertNotNull(remote.volume).volumeControl)
        assertEquals(tvRouteSessionId("movie-session-123"), assertNotNull(remote.volume).volumeControlId)
        assertEquals(0L, assertNotNull(remote.state).actions and PlaybackState.ACTION_SEEK_TO)
        assertTrue(assertNotNull(remote.state).actions and PlaybackState.ACTION_PAUSE != 0L)
        assertEquals("Movie", assertNotNull(remote.capturedMetadata).getString(MediaMetadata.METADATA_KEY_TITLE))
        assertNotNull(remote.callback).onPause()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        runCurrent()
        assertEquals("pause", connection.commands.single().action)
        controller.localPlayback.value = true
        runCurrent()
        assertNull(TvRemoteRoutes.current.value)
        assertNull(assertNotNull(manager.getNotification(TV_NOTIFICATION_ID)).extras.get(Notification.EXTRA_MEDIA_SESSION))
        assertNotNull(repository.state.value.snapshot) // still controllable inside Nuvio
        controller.localPlayback.value = false
        runCurrent()
        assertNotNull(manager.getNotification(TV_NOTIFICATION_ID))
        repository.disconnect(); runCurrent()
        assertNull(manager.getNotification(TV_NOTIFICATION_ID))
    }

    @Test fun namedRouteMatchesMediaSessionAndReleasesOldEpisodes() {
        val service = Robolectric.buildService(TvRemoteRouteProvider::class.java).create()
        try {
            service.get().publish(TvRemoteRoute(tv.deviceId, "episode-one", tv.deviceName))
            val first = service.get().allSessionInfo.single()
            assertEquals(tvRouteSessionId("episode-one"), first.id)
            assertEquals("Nuvio", first.name)
            assertEquals(RuntimeEnvironment.getApplication().packageName, first.clientPackageName)
            assertEquals(listOf(tv.deviceId), first.selectedRoutes)
            service.get().publish(TvRemoteRoute(tv.deviceId, "episode-two", tv.deviceName))
            assertEquals(tvRouteSessionId("episode-two"), service.get().allSessionInfo.single().id)
            service.get().publish(null)
            assertTrue(service.get().allSessionInfo.isEmpty())
        } finally { service.destroy() }
    }

    @Test fun idleConnectionStartsForegroundBeforeLeavingAppAndPublishesLaterPlayback() = runTest {
        val context = RuntimeEnvironment.getApplication()
        TvRemoteService.hide(context)
        val connection = Connection()
        val repository = TvRemoteRepository(Store(tv), backgroundScope, { connection })
        val controller = TvRemoteMediaController(context, repository, backgroundScope)
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        runCurrent()
        controller.allowPromotion() // Pairing/foreground use, with no active playback yet.
        val start = assertNotNull(shadowOf(context).nextStartedService)
        assertEquals(TvRemoteService::class.java.name, start.component?.className)
        val service = Robolectric.buildService(TvRemoteService::class.java).create()
        try {
            assertEquals(Service.START_STICKY, service.get().onStartCommand(start, 0, 1))
            assertNull(assertNotNull(shadowOf(service.get()).lastForegroundNotification).extras.get(Notification.EXTRA_MEDIA_SESSION))
            assertFalse(shadowOf(service.get()).isStoppedBySelf)
            // Activity is no longer visible and the interaction exemption has expired.
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(10))
            connection.snapshots.send(RemoteSnapshot(deviceId = tv.deviceId, deviceName = tv.deviceName,
                sessionId = "later-movie-session", title = "Started on TV", state = "playing"))
            runCurrent()
            assertNotNull(assertNotNull(manager.getNotification(TV_NOTIFICATION_ID)).extras.get(Notification.EXTRA_MEDIA_SESSION))
            assertNull(shadowOf(context).nextStartedService) // Already running; no background FGS launch.
            connection.snapshots.send(RemoteSnapshot(deviceId = tv.deviceId, deviceName = tv.deviceName, state = "idle"))
            runCurrent()
            assertNull(assertNotNull(manager.getNotification(TV_NOTIFICATION_ID)).extras.get(Notification.EXTRA_MEDIA_SESSION))
            assertFalse(shadowOf(service.get()).isStoppedBySelf) // Ready for the next movie.
            repository.disconnect(); runCurrent()
            assertNull(manager.getNotification(TV_NOTIFICATION_ID))
            assertEquals(TvRemoteService::class.java.name, assertNotNull(shadowOf(context).nextStoppedService).component?.className)
        } finally { service.destroy(); TvRemoteService.hide(context) }
    }

    @Test fun connectionLossAndLocalPlaybackKeepObservationAlive() = runTest {
        val context = RuntimeEnvironment.getApplication()
        TvRemoteService.hide(context)
        val repository = TvRemoteRepository(Store(tv), backgroundScope, { throw java.io.IOException("Offline") })
        val controller = TvRemoteMediaController(context, repository, backgroundScope)
        runCurrent()
        controller.allowPromotion()
        assertNotNull(shadowOf(context).nextStartedService)
        controller.localPlayback.value = true
        runCurrent()
        val notification = assertNotNull(shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(TV_NOTIFICATION_ID))
        assertNull(notification.extras.get(Notification.EXTRA_MEDIA_SESSION))
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(tv.deviceName))
        assertNull(shadowOf(context).nextStoppedService)
        repository.disconnect(); runCurrent()
        TvRemoteService.hide(context)
    }

    @Test fun delayedForegroundStartPromotesThenStopsAfterSessionEnds() {
        val context = RuntimeEnvironment.getApplication()
        val notification = Notification.Builder(context, TV_CHANNEL).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("TV").build()
        TvRemoteService.hide(context)
        val service = Robolectric.buildService(TvRemoteService::class.java).create()
        try {
            service.get().onStartCommand(Intent(context, TvRemoteService::class.java).putExtra("notification", notification), 0, 1)
            assertTrue(shadowOf(service.get()).isForegroundStopped)
            assertTrue(shadowOf(service.get()).isStoppedBySelf)
        } finally { service.destroy() }
        assertNull(shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(TV_NOTIFICATION_ID))
    }
}

/** Robolectric's stock MediaController does not bridge session metadata or playback state. */
@Implements(MediaSession::class)
class RecordingRemoteSession : ShadowMediaSession() {
    var volume: VolumeProvider? = null
    var state: PlaybackState? = null
    var capturedMetadata: MediaMetadata? = null
    var callback: MediaSession.Callback? = null
    @Implementation protected fun setPlaybackToRemote(provider: VolumeProvider) { volume = provider; latest = this }
    @Implementation protected fun setPlaybackState(value: PlaybackState?) { state = value }
    @Implementation protected fun setMetadata(value: MediaMetadata?) { capturedMetadata = value }
    @Implementation protected fun setCallback(value: MediaSession.Callback?, handler: Handler?) { callback = value }
    companion object { var latest: RecordingRemoteSession? = null }
}
