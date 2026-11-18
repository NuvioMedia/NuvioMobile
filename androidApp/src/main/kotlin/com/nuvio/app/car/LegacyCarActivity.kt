package com.nuvio.app.car

import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.apps.auto.sdk.CarActivityService

/**
 * Projection entry point, through the unofficial Android Auto SDK (aauto.aar, from Fermata). This is the
 * route Fermata and YT Auto's projection build use, and the one Android Auto lists for sideloaded apps on
 * versions that hide sideloaded Car App Library apps. Android Auto binds [LegacyCarService] (category
 * PROJECTION) and shows this activity full screen on the head unit.
 *
 * A SurfaceView fills the car screen and [CarDisplayHost] runs Nuvio on it. Unlike the template route,
 * touches arrive as real finger events, so drags start where the finger is.
 */
class LegacyCarActivity : CarActivity() {

    private var host: CarDisplayHost? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIgnoreConfigChanges(0xFFFFFFFF.toInt())
        runCatching {
            val ui = carUiController
            ui.statusBarController.hideAppHeader()
            ui.menuController.hideMenuButton()
        }
        val h = CarDisplayHost(applicationContext)
        host = h
        val dpi = resources.displayMetrics.densityDpi
        val view = object : SurfaceView(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                h.touch(event)
                return true
            }
        }
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                h.attachSurface(holder.surface, width, height, dpi)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) = h.detachSurface()
        })
        setContentView(FrameLayout(this).apply { addView(view, FrameLayout.LayoutParams(-1, -1)) })
    }

    override fun onBackPressed() {
        host?.back()
    }

    override fun onDestroy() {
        host?.release()
        host = null
        super.onDestroy()
    }
}

/** Bound by Android Auto (CATEGORY_PROJECTION) to show [LegacyCarActivity] on the head unit. */
class LegacyCarService : CarActivityService() {
    override fun getCarActivity(): Class<out CarActivity> = LegacyCarActivity::class.java
}
