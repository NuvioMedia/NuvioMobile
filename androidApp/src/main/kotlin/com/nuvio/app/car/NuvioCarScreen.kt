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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
    private var mirror: ClusterMirror? = null
    private var watching = false
    private var playing = false

    init {
        ctx.getCarService(AppManager::class.java).setSurfaceCallback(this)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            // Mirror Maps' guidance only while Nuvio is the app on the car screen; with Maps showing, Maps
            // drives the cockpit itself.
            override fun onStart(owner: LifecycleOwner) {
                if (CarPrefs.clusterMirror(ctx) && mirror == null) {
                    mirror = runCatching { ClusterMirror(ctx).also { it.start() } }
                        .onFailure { CarLog.d("Cockpit mirroring unavailable", it) }
                        .getOrNull()
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                mirror?.stop()
                mirror = null
            }

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
        if (CarPrefs.resumePrompt(ctx)) offerResumeOnce()
    }

    /**
     * Once per car session: when the home screen's Continue Watching row first has something (so a profile is
     * picked and Nuvio is ready), ask whether to resume it.
     */
    private fun offerResumeOnce() {
        lifecycleScope.launch {
            val item = combine(ExternalAppCommands.continueWatching, ExternalAppCommands.screen) { items, screen ->
                items.firstOrNull()?.takeIf { screen == ExternalAppCommands.Screen.HOME }
            }.filterNotNull().first()
            kotlinx.coroutines.delay(RESUME_PROMPT_DELAY_MS) // let the car's home screen settle first
            if (ExternalAppCommands.screen.value != ExternalAppCommands.Screen.HOME) return@launch
            CarLog.d("Offering to resume ${item.title}")
            screenManager.push(CarResumeScreen(carContext, item))
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
        val map = ActionStrip.Builder()
        if (watching) {
            map.addAction(action(R.drawable.car_ic_next_episode) { NuvioPlaybackRemote.nextEpisode() })
            map.addAction(action(R.drawable.car_ic_audio_only) { toggleAudioOnly() })
        } else {
            map.addAction(action(R.drawable.car_ic_mic) { openVoice() })
            map.addAction(action(R.drawable.car_ic_downloads) {
                ExternalAppCommands.send(ExternalAppCommands.Command.OpenDownloads)
            })
        }
        map.addAction(action(R.drawable.car_ic_text_size) {
            CarToast.makeText(carContext, "Screen size: ${host.cycleSize()}", CarToast.LENGTH_SHORT).show()
        })
        return NavigationTemplate.Builder()
            .setActionStrip(strip.build())
            .setMapActionStrip(map.build())
            .build()
    }

    private fun toggleAudioOnly() {
        val on = host.toggleAudioOnly()
        CarToast.makeText(carContext, if (on) "Audio only" else "Picture back on", CarToast.LENGTH_SHORT).show()
    }

    private fun openTab(tab: AppScreenTab) {
        ExternalAppCommands.send(ExternalAppCommands.Command.OpenTab(tab))
    }

    private fun openVoice() {
        screenManager.push(CarVoiceScreen(carContext) { query ->
            ExternalAppCommands.send(ExternalAppCommands.Command.Search(query))
        })
    }

    private fun openSearch() {
        screenManager.push(
            CarKeyboardScreen(
                carContext,
                onSubmit = { query -> ExternalAppCommands.send(ExternalAppCommands.Command.Search(query)) },
                onVoice = { screenManager.pop(); openVoice() },
            ),
        )
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

    private companion object {
        const val RESUME_PROMPT_DELAY_MS = 2_500L
    }
}
