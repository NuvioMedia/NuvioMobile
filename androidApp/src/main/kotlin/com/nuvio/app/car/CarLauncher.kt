package com.nuvio.app.car

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Starts [CarNuvioActivity] on the car's virtual display, trying the ways Android allows an app that's in the
 * background (the phone is usually locked or showing something else) to open a screen:
 *
 * 1. DIRECT: plain startActivity. Works when Nuvio Auto is open on the phone, or when Android counts the car
 *    overlay as a visible window.
 * 2. OVERLAY: with "Display over other apps" granted, a 1-pixel invisible window on the phone first. Android
 *    lets an app with that permission and a visible overlay start activities from the background.
 * 3. PENDING: a PendingIntent sent with background-start allowed (Android 14+ opt-in).
 */
internal class CarLauncher(private val ctx: Context) {

    enum class Method { DIRECT, OVERLAY, PENDING }

    private var overlayView: View? = null

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(ctx)

    /** Methods worth trying on this phone, in order. */
    fun methods(): List<Method> = buildList {
        if (canDrawOverlays()) add(Method.OVERLAY) else add(Method.DIRECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) add(Method.PENDING)
        if (canDrawOverlays()) add(Method.DIRECT)
    }

    /** Must run before [launch] for [Method.OVERLAY]; the window needs a frame on screen to count as visible. */
    fun prepare(method: Method) {
        if (method == Method.OVERLAY) showOverlay()
    }

    /** Throws when Android rejects the launch outright; a silent background block shows up as a timeout. */
    fun launch(method: Method, displayId: Int) {
        val intent = Intent(ctx, CarNuvioActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        )
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        when (method) {
            Method.DIRECT, Method.OVERLAY -> ctx.applicationContext.startActivity(intent, options.toBundle())
            Method.PENDING -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    @Suppress("DEPRECATION")
                    options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
                val pending = PendingIntent.getActivity(
                    ctx, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ActivityOptions.makeBasic().apply {
                            @Suppress("DEPRECATION")
                            setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        }.toBundle()
                    } else null,
                )
                pending.send(ctx, 0, null, null, null, null, options.toBundle())
            }
        }
    }

    private fun showOverlay() {
        if (overlayView != null || !canDrawOverlays()) return
        val wm = ctx.applicationContext.getSystemService(WindowManager::class.java) ?: return
        val view = View(ctx.applicationContext).apply { setBackgroundColor(0x01000000) }
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching {
            wm.addView(view, params)
            overlayView = view
        }.onFailure { CarLog.d("Overlay window failed", it) }
    }

    fun hideOverlay() {
        val view = overlayView ?: return
        overlayView = null
        runCatching { ctx.applicationContext.getSystemService(WindowManager::class.java)?.removeView(view) }
    }
}
