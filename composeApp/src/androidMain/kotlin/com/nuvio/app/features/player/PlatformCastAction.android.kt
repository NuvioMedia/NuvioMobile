package com.nuvio.app.features.player

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import androidx.mediarouter.app.MediaRouteButton

@Composable
actual fun PlatformCastAction(
    modifier: Modifier,
) {
    val context = LocalContext.current
    val castContext = remember(context) { runCatching { CastContext.getSharedInstance(context) }.getOrNull() }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            MediaRouteButton(viewContext).apply {
                layoutParams = ViewGroup.LayoutParams(dp(viewContext, 48), dp(viewContext, 48))
                CastButtonFactory.setUpMediaRouteButton(viewContext, this)
            }
        },
        update = { button ->
            if (castContext == null) button.isEnabled = false
        },
    )
}

@Composable
actual fun PlatformCastSessionEffect(
    url: String,
    title: String,
    positionMs: Long,
    headers: Map<String, String>,
    canCast: Boolean,
    onPauseLocal: () -> Unit,
    onResumeLocal: (Long) -> Unit,
    onUnsupported: () -> Unit,
) {
    val context = LocalContext.current
    val current = rememberUpdatedState(CastRequest(url, title, positionMs, headers, canCast, onPauseLocal, onResumeLocal, onUnsupported))
    val castContext = remember(context) { runCatching { CastContext.getSharedInstance(context) }.getOrNull() }

    DisposableEffect(castContext) {
        if (castContext == null) return@DisposableEffect onDispose {}
        var remoteClient: RemoteMediaClient? = null
        var localPausedForCast = false
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) {
                val request = current.value
                val directHttpUrl = request.url.startsWith("http://", true) || request.url.startsWith("https://", true)
                if (!request.canCast || request.headers.isNotEmpty() || !directHttpUrl) {
                    request.onUnsupported()
                    castContext.sessionManager.endCurrentSession(true)
                    return
                }
                val client = session.remoteMediaClient ?: return
                remoteClient = client
                request.onPauseLocal()
                localPausedForCast = true
                val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                    putString(MediaMetadata.KEY_TITLE, request.title)
                }
                val contentType = if (request.url.substringBefore('?').endsWith(".m3u8", true)) {
                    "application/x-mpegURL"
                } else {
                    "video/mp4"
                }
                val media = MediaInfo.Builder(request.url)
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(contentType)
                    .setMetadata(metadata)
                    .build()
                client.load(
                    MediaLoadRequestData.Builder()
                        .setMediaInfo(media)
                        .setAutoplay(true)
                        .setCurrentTime(request.positionMs)
                        .build(),
                )
            }

            override fun onSessionEnding(session: CastSession) {
                if (localPausedForCast) {
                    val request = current.value
                    request.onResumeLocal(remoteClient?.approximateStreamPosition?.coerceAtLeast(0L) ?: request.positionMs)
                    localPausedForCast = false
                }
            }

            override fun onSessionEnded(session: CastSession, error: Int) = Unit
            override fun onSessionStarting(session: CastSession) = Unit
            override fun onSessionStartFailed(session: CastSession, error: Int) = Unit
            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = Unit
            override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
        }
        castContext.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
        onDispose {
            castContext.sessionManager.removeSessionManagerListener(listener, CastSession::class.java)
            if (localPausedForCast) {
                current.value.onResumeLocal(remoteClient?.approximateStreamPosition?.coerceAtLeast(0L) ?: current.value.positionMs)
            }
        }
    }
}

private data class CastRequest(
    val url: String,
    val title: String,
    val positionMs: Long,
    val headers: Map<String, String>,
    val canCast: Boolean,
    val onPauseLocal: () -> Unit,
    val onResumeLocal: (Long) -> Unit,
    val onUnsupported: () -> Unit,
)

private fun dp(context: android.content.Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()
