package com.nuvio.app.car

import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.nuvio.android.R
import com.nuvio.app.AppScreenTab
import com.nuvio.app.core.external.ExternalAppCommands
import com.nuvio.app.features.player.NuvioPlaybackRemote
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Car App Library entry: the host gives a navigation app a Surface, and [CarDisplayHost] runs Nuvio on it.
 * Taps, drags and flings arrive as template gestures and are replayed as finger touches.
 *
 * Android Auto's buttons follow what's on screen:
 * - browsing: Back, Search (car keyboard), Home, Library
 * - watching: Back, back 10 s, play/pause, forward 30 s
 * plus Screen size in the map strip.
 */
class NuvioCarScreen(ctx: CarContext) : Screen(ctx), SurfaceCallback {

    private val host = CarDisplayHost(ctx)
    private var watching = false
    private var playing = false

    init {
        ctx.getCarService(AppManager::class.java).setSurfaceCallback(this)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = host.release()
        })
        lifecycleScope.launch {
            combine(ExternalAppCommands.screen, NuvioPlaybackRemote.state) { screen, playback ->
                (screen == ExternalAppCommands.Screen.PLAYER && playback.active) to playback.playing
            }.distinctUntilChanged().collect { (isWatching, isPlaying) ->
                watching = isWatching
                playing = isPlaying
                invalidate()
            }
        }
    }

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    private fun action(res: Int, onClick: () -> Unit) =
        Action.Builder().setIcon(icon(res)).setOnClickListener { onClick() }.build()

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder().addAction(action(R.drawable.car_ic_back) { host.back() })
        if (watching) {
            strip.addAction(action(R.drawable.car_ic_replay_10) { NuvioPlaybackRemote.seekBy(-10_000L) })
            strip.addAction(action(if (playing) R.drawable.car_ic_pause else R.drawable.car_ic_play) {
                NuvioPlaybackRemote.togglePlayPause()
            })
            strip.addAction(action(R.drawable.car_ic_forward_30) { NuvioPlaybackRemote.seekBy(30_000L) })
        } else {
            strip.addAction(action(R.drawable.car_ic_search) { openSearch() })
            strip.addAction(action(R.drawable.car_ic_home) { openTab(AppScreenTab.Home) })
            strip.addAction(action(R.drawable.car_ic_library) { openTab(AppScreenTab.Library) })
        }
        return NavigationTemplate.Builder()
            .setActionStrip(strip.build())
            .setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(action(R.drawable.car_ic_text_size) {
                        CarToast.makeText(carContext, "Screen size: ${host.cycleSize()}", CarToast.LENGTH_SHORT).show()
                    })
                    .build()
            )
            .build()
    }

    private fun openTab(tab: AppScreenTab) {
        ExternalAppCommands.send(ExternalAppCommands.Command.OpenTab(tab))
    }

    private fun openSearch() {
        screenManager.push(CarKeyboardScreen(carContext) { query ->
            ExternalAppCommands.send(ExternalAppCommands.Command.Search(query))
        })
    }

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        host.attachSurface(surface, container.width, container.height, container.dpi)
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) = host.detachSurface()

    override fun onStableAreaChanged(stableArea: Rect) = host.setStableArea(stableArea)

    override fun onClick(x: Float, y: Float) = host.click(x, y)

    override fun onScroll(distanceX: Float, distanceY: Float) = host.scroll(distanceX, distanceY)

    override fun onFling(velocityX: Float, velocityY: Float) = host.fling(velocityX, velocityY)
}
