package com.nuvio.app.navigation

import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.navigation3.runtime.NavKey
import com.nuvio.app.core.ui.PosterZoomAnchor
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

internal class PosterNavigationState {
    private var pending: PosterZoomAnchor? = null
    private var pendingSince: TimeMark? = null
    private var viewportBounds: Rect? = null
    var active by mutableStateOf<PosterNavigationRequest?>(null)
        private set

    fun prepare(anchor: PosterZoomAnchor) {
        pending = anchor
        pendingSince = TimeSource.Monotonic.markNow()
    }

    fun updateViewport(route: NavKey, bounds: Rect) {
        if (bounds.width <= 0f || bounds.height <= 0f) return
        viewportBounds = bounds
        active?.takeIf { it.to == route }?.viewportBounds = bounds
    }

    fun navigate(from: AppRoute?, to: AppRoute) {
        val anchor = pending
        val recent = pendingSince?.elapsedNow()?.let { it < 2.seconds } == true
        pending = null
        pendingSince = null
        clear()
        if (from == null || from == to || to !is DetailRoute || anchor == null || !recent) return
        val viewport = viewportBounds ?: return
        if (anchor.source?.canLift != true) return
        active = PosterNavigationRequest(from, to, anchor, viewport)
    }

    fun complete(request: PosterNavigationRequest) {
        if (active === request) {
            request.finish(completed = true)
            clear()
        }
    }

    fun clear() {
        pending = null
        pendingSince = null
        active?.finish()
        active = null
    }
}

internal class PosterNavigationRequest(
    val from: AppRoute,
    val to: DetailRoute,
    val anchor: PosterZoomAnchor,
    viewport: Rect,
) {
    private var finished = false
    var contentReady by mutableStateOf(false)
        private set
    var viewportBounds by mutableStateOf(viewport)
    var clock by mutableStateOf<State<Float>?>(null)
    val elapsedMillis: Float
        get() = clock?.value ?: 0f

    fun placeArtwork(): Boolean = !finished && anchor.source?.lift() == true

    fun revealContent() {
        if (!finished) contentReady = true
    }

    fun finish(completed: Boolean = false) {
        if (finished) return
        if (completed) contentReady = true
        finished = true
        anchor.source?.land()
    }
}
