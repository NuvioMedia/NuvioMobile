package com.nuvio.app.features.cast

import android.content.Context
import android.util.Log
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal actual object ChromecastPlatform {
    private const val TAG = "ChromecastPlatform"
    private var appContext: Context? = null
    private var mediaRouter: MediaRouter? = null
    private var castContext: CastContext? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        try {
            castContext = CastContext.getSharedInstance(context)
            mediaRouter = MediaRouter.getInstance(context)
            Log.i(TAG, "CastContext initialized")
        } catch (e: Exception) {
            Log.w(TAG, "Cast init failed (no Play Services?): ${e.message}")
        }
    }

    // For MainActivity init
    actual fun initialize() {
        appContext?.let { initialize(it) }
    }

    actual suspend fun scanDevices(timeoutMs: Int): List<UnifiedCastDevice> = withContext(Dispatchers.Main) {
        val ctx = appContext ?: return@withContext emptyList()
        val router = mediaRouter ?: MediaRouter.getInstance(ctx).also { mediaRouter = it }
        val selector = MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .addControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO)
            .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
            .build()

        val collected = mutableMapOf<String, UnifiedCastDevice>()
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) { addRoute(route) }
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) { addRoute(route) }
            fun addRoute(route: MediaRouter.RouteInfo) {
                if (!route.isEnabled) return
                // Filter only Cast-capable routes (present in selector)
                if (!route.matchesSelector(selector)) return
                val id = route.id ?: return
                val name = route.name ?: "Chromecast"
                if (collected.containsKey(id)) return
                val desc = route.description
                collected[id] = UnifiedCastDevice(
                    id = "cast:$id",
                    name = name,
                    protocol = CastProtocol.CHROMECAST,
                    chromecastRouteId = id,
                    chromecastDescription = desc,
                    ipAddress = null
                )
                Log.i(TAG, "Found Cast route: $name id=$id desc=$desc")
            }
        }

        // Seed with already known routes
        router.routes.forEach { callback.addRoute(it) }

        router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)

        // Also try CastContext discovery via SessionManager?
        try {
            val sessionMgr = castContext?.sessionManager
            // Trigger discovery
        } catch (_: Exception) {}

        delay(timeoutMs.toLong())

        router.removeCallback(callback)

        collected.values.toList()
    }

    actual suspend fun castToDevice(device: UnifiedCastDevice, request: ChromecastMediaRequest): Boolean = withContext(Dispatchers.Main) {
        val ctx = appContext ?: return@withContext false
        val router = mediaRouter ?: return@withContext false
        val routeId = device.chromecastRouteId ?: return@withContext false

        // Find route
        val route = router.routes.firstOrNull { it.id == routeId } ?: return@withContext false
        Log.i(TAG, "Selecting Cast route ${route.name}")

        // Select route - this will trigger Cast session establishment
        router.selectRoute(route)

        // Wait for CastSession to become connected (up to 10s)
        val castCtx = try { CastContext.getSharedInstance(ctx) } catch (e: Exception) { null } ?: return@withContext false
        val sessionManager = castCtx.sessionManager

        var castSession: CastSession? = null
        val timeoutMs = 10000L
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val sess = sessionManager.currentCastSession
            if (sess != null && sess.isConnected) {
                castSession = sess
                break
            }
            delay(200)
        }
        if (castSession == null) {
            Log.e(TAG, "CastSession not connected after select")
            return@withContext false
        }

        val remoteClient = castSession.remoteMediaClient ?: return@withContext false

        // Build MediaInfo
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, request.title)
            if (!request.subtitle.isNullOrBlank()) putString(MediaMetadata.KEY_SUBTITLE, request.subtitle)
            request.posterUrl?.let { addImage(WebImage(android.net.Uri.parse(it))) }
        }

        val tracks = mutableListOf<MediaTrack>()
        if (request.subtitles.isNotEmpty()) {
            request.subtitles.forEachIndexed { idx, sub ->
                val trackId = (idx + 1).toLong()
                val track = MediaTrack.Builder(trackId, MediaTrack.TYPE_TEXT)
                    .setName(sub.name.ifBlank { sub.lang.uppercase() })
                    .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                    .setContentId(sub.url)
                    .setContentType("text/vtt")
                    .setLanguage(sub.lang)
                    .build()
                tracks.add(track)
            }
        } else if (!request.subtitleUrl.isNullOrBlank()) {
            val name = request.subtitleName ?: request.subtitleLanguage.uppercase()
            val track = MediaTrack.Builder(1L, MediaTrack.TYPE_TEXT)
                .setName(name)
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(request.subtitleUrl)
                .setContentType(request.subtitleMime)
                .setLanguage(request.subtitleLanguage)
                .build()
            tracks.add(track)
        }

        val mediaInfo = MediaInfo.Builder(request.proxyUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(request.mimeType)
            .setMetadata(metadata)
            .apply {
                if (tracks.isNotEmpty()) setMediaTracks(tracks)
                if (request.durationMs != null && request.durationMs > 0) setStreamDuration(request.durationMs)
            }
            .build()

        val activeTrackIds = if (tracks.isNotEmpty()) {
            val selectedIdx = request.selectedSubtitleIndex ?: 0
            if (selectedIdx >= 0 && selectedIdx < tracks.size) {
                longArrayOf((selectedIdx + 1).toLong())
            } else longArrayOf()
        } else longArrayOf()

        val loadRequest = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .setCurrentTime(request.startPositionMs)
            .apply {
                if (activeTrackIds.isNotEmpty()) setActiveTrackIds(activeTrackIds)
            }
            .build()

        Log.i(TAG, "RemoteMediaClient.load proxy=${request.proxyUrl} title=${request.title}")

        return@withContext suspendCancellableCoroutine { cont ->
            try {
                val pending = remoteClient.load(loadRequest)
                pending.setResultCallback { result ->
                    if (result.status.isSuccess) {
                        Log.i(TAG, "Cast load success")
                        if (cont.isActive) cont.resume(true)
                    } else {
                        Log.e(TAG, "Cast load failed ${result.status.statusCode} ${result.status.statusMessage}")
                        if (cont.isActive) cont.resume(false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cast load exception", e)
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    }

    actual suspend fun pause(): Boolean = withContext(Dispatchers.Main) {
        try {
            val client = getRemoteClient() ?: return@withContext false
            suspendCancellableCoroutine { cont ->
                val p = client.pause()
                p.setResultCallback { res -> cont.resume(res.status.isSuccess) }
            }
        } catch (e: Exception) { false }
    }

    actual suspend fun resume(): Boolean = withContext(Dispatchers.Main) {
        try {
            val client = getRemoteClient() ?: return@withContext false
            suspendCancellableCoroutine { cont ->
                val p = client.play()
                p.setResultCallback { res -> cont.resume(res.status.isSuccess) }
            }
        } catch (e: Exception) { false }
    }

    actual suspend fun seek(positionMs: Long): Boolean = withContext(Dispatchers.Main) {
        try {
            val client = getRemoteClient() ?: return@withContext false
            suspendCancellableCoroutine { cont ->
                val p = client.seek(positionMs)
                p.setResultCallback { res -> cont.resume(res.status.isSuccess) }
            }
        } catch (e: Exception) { false }
    }

    actual suspend fun stop(): Boolean = withContext(Dispatchers.Main) {
        try {
            val client = getRemoteClient() ?: return@withContext false
            suspendCancellableCoroutine { cont ->
                val p = client.stop()
                p.setResultCallback { res -> cont.resume(res.status.isSuccess) }
            }
        } catch (e: Exception) { false }
    }

    actual suspend fun getPosition(): Long? = withContext(Dispatchers.Main) {
        try {
            val client = getRemoteClient() ?: return@withContext null
            val pos = client.approximateStreamPosition
            if (pos < 0) null else pos
        } catch (_: Exception) { null }
    }

    actual suspend fun setVolume(volume: Float): Boolean = withContext(Dispatchers.Main) {
        try {
            val client = getRemoteClient() ?: return@withContext false
            suspendCancellableCoroutine { cont ->
                val p = client.setStreamVolume(volume.toDouble().coerceIn(0.0, 1.0))
                p.setResultCallback { res -> cont.resume(res.status.isSuccess) }
            }
        } catch (_: Exception) { false }
    }

    actual suspend fun setActiveTrack(trackId: Long?): Boolean = withContext(Dispatchers.Main) {
        try {
            val client = getRemoteClient() ?: return@withContext false
            val trackIds = if (trackId != null && trackId > 0) longArrayOf(trackId) else longArrayOf()
            suspendCancellableCoroutine { cont ->
                val p = client.setActiveMediaTracks(trackIds)
                p.setResultCallback { res -> cont.resume(res.status.isSuccess) }
            }
        } catch (_: Exception) { false }
    }

    actual fun isConnected(): Boolean {
        return try {
            val ctx = appContext ?: return false
            val cc = CastContext.getSharedInstance(ctx)
            cc.sessionManager.currentCastSession?.isConnected == true
        } catch (_: Exception) { false }
    }

    actual fun disconnect() {
        try {
            val ctx = appContext ?: return
            val cc = CastContext.getSharedInstance(ctx)
            cc.sessionManager.endCurrentSession(true)
        } catch (_: Exception) {}
        try {
            mediaRouter?.let { router ->
                // Unselect to default route
                router.selectRoute(router.defaultRoute)
            }
        } catch (_: Exception) {}
    }

    private fun getRemoteClient() = try {
        val ctx = appContext ?: return null
        CastContext.getSharedInstance(ctx).sessionManager.currentCastSession?.remoteMediaClient
    } catch (_: Exception) { null }
}
