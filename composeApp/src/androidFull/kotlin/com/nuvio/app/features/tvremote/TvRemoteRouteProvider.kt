package com.nuvio.app.features.tvremote

import android.content.Context
import android.media.MediaRoute2Info
import android.media.MediaRoute2ProviderService
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.media.RoutingSessionInfo
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal fun tvRouteSessionId(sessionId: String) = "nuvio-tv-$sessionId"

internal data class TvRemoteRoute(val deviceId: String, val sessionId: String, val deviceName: String)

/** Publish the actual remote destination so SystemUI does not use its "Other" fallback. */
@RequiresApi(30)
internal object TvRemoteRoutes {
    val current = MutableStateFlow<TvRemoteRoute?>(null)
    private var router: MediaRouter2? = null
    private var registrationAttempted = false
    private val callback = object : MediaRouter2.RouteCallback() {}

    fun update(context: Context, snapshot: RemoteSnapshot?) {
        current.value = snapshot?.let { TvRemoteRoute(it.deviceId, requireNotNull(it.sessionId), it.deviceName) }
        if (snapshot != null && !registrationAttempted) {
            registrationAttempted = true
            // A missing/broken system router must never stop the TV connection or media card.
            router = runCatching {
                MediaRouter2.getInstance(context).also {
                    it.registerRouteCallback(context.mainExecutor, callback,
                        RouteDiscoveryPreference.Builder(listOf(MediaRoute2Info.FEATURE_REMOTE_VIDEO_PLAYBACK), false).build())
                }
            }.onFailure { Log.w("NuvioTvRemote", "System media route unavailable", it) }.getOrNull()
        } else if (snapshot == null) {
            router?.let { runCatching { it.unregisterRouteCallback(callback) } }
            router = null
            registrationAttempted = false
        }
    }
}

/** System-bound metadata provider; it never starts playback or transfers phone audio. */
@RequiresApi(30)
class TvRemoteRouteProvider : MediaRoute2ProviderService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        scope.launch { TvRemoteRoutes.current.collect(::publish) }
    }

    internal fun publish(route: TvRemoteRoute?) {
        val sessionId = route?.let { tvRouteSessionId(it.sessionId) }
        allSessionInfo.filter { it.id != sessionId }.forEach { notifySessionReleased(it.id) }
        if (route == null) {
            notifyRoutes(emptyList())
            return
        }
        notifyRoutes(listOf(MediaRoute2Info.Builder(route.deviceId, "Nuvio")
            .setDescription("Playing on ${route.deviceName}")
            .addFeature(MediaRoute2Info.FEATURE_REMOTE_PLAYBACK)
            .addFeature(MediaRoute2Info.FEATURE_REMOTE_VIDEO_PLAYBACK)
            .setType(MediaRoute2Info.TYPE_REMOTE_TV)
            .setConnectionState(MediaRoute2Info.CONNECTION_STATE_CONNECTED)
            .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_FIXED).build()))
        val session = RoutingSessionInfo.Builder(requireNotNull(sessionId), packageName)
            .setName("Nuvio").addSelectedRoute(route.deviceId)
            .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_FIXED).build()
        if (getSessionInfo(sessionId) == null) notifySessionCreated(REQUEST_ID_NONE, session)
        else notifySessionUpdated(session)
    }

    // This is an existing authenticated TV session, not a casting destination for other apps.
    override fun onCreateSession(requestId: Long, packageName: String, routeId: String, sessionHints: Bundle?) = reject(requestId)
    override fun onSelectRoute(requestId: Long, sessionId: String, routeId: String) = reject(requestId)
    override fun onDeselectRoute(requestId: Long, sessionId: String, routeId: String) = reject(requestId)
    override fun onTransferToRoute(requestId: Long, sessionId: String, routeId: String) = reject(requestId)
    override fun onSetRouteVolume(requestId: Long, routeId: String, volume: Int) = reject(requestId)
    override fun onSetSessionVolume(requestId: Long, sessionId: String, volume: Int) = reject(requestId)
    override fun onReleaseSession(requestId: Long, sessionId: String) {
        // "Stop casting" in the system output picker stops phone observation, never the TV movie.
        if (AndroidTvRemote.initialized &&
            AndroidTvRemote.repository.state.value.snapshot?.sessionId?.let(::tvRouteSessionId) == sessionId) {
            AndroidTvRemote.repository.disconnect()
        }
        if (getSessionInfo(sessionId) != null) notifySessionReleased(sessionId)
    }
    private fun reject(requestId: Long) {
        if (requestId != REQUEST_ID_NONE) notifyRequestFailed(requestId, REASON_REJECTED)
    }
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
