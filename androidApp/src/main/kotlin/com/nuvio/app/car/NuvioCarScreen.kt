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
import com.nuvio.android.R

/**
 * Car App Library entry: the host gives a navigation app a Surface, and [CarDisplayHost] runs Nuvio on it.
 * Taps, drags and flings arrive as template gestures and are replayed as finger touches.
 */
class NuvioCarScreen(ctx: CarContext) : Screen(ctx), SurfaceCallback {

    private val host = CarDisplayHost(ctx)

    init {
        ctx.getCarService(AppManager::class.java).setSurfaceCallback(this)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = host.release()
        })
    }

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    override fun onGetTemplate(): Template = NavigationTemplate.Builder()
        .setActionStrip(
            ActionStrip.Builder()
                .addAction(Action.Builder().setIcon(icon(R.drawable.car_ic_back)).setOnClickListener { host.back() }.build())
                .addAction(Action.Builder().setIcon(icon(R.drawable.car_ic_keyboard)).setOnClickListener { openKeyboard() }.build())
                .addAction(Action.Builder().setIcon(icon(R.drawable.car_ic_home)).setOnClickListener { host.home() }.build())
                .build()
        )
        .setMapActionStrip(
            ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setIcon(icon(R.drawable.car_ic_text_size))
                        .setOnClickListener {
                            CarToast.makeText(carContext, "Screen size: ${host.cycleSize()}", CarToast.LENGTH_SHORT).show()
                        }
                        .build()
                )
                .build()
        )
        .build()

    private fun openKeyboard() {
        if (!host.hasFocusedField()) {
            CarToast.makeText(carContext, "Tap a search box in Nuvio first", CarToast.LENGTH_LONG).show()
        }
        screenManager.push(CarKeyboardScreen(carContext) { text ->
            if (!host.typeText(text)) {
                CarToast.makeText(carContext, "Tap a search box in Nuvio first", CarToast.LENGTH_LONG).show()
            }
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
