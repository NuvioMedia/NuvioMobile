package com.nuvio.app.car

import android.app.ActivityOptions
import android.app.Presentation
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
 * Puts the real Nuvio app on the Android Auto screen.
 *
 * The host gives a navigation app a Surface. A private VirtualDisplay renders into it, and [CarNuvioActivity]
 * (the normal Nuvio activity) is launched onto that display. Taps, drags and flings from the car are replayed
 * into the activity as finger touches. A Presentation on the same display is used for the "starting" screen,
 * the "hidden while driving" cover, and as the visible window that lets the app start its activity while the
 * phone itself is in the background.
 */
class NuvioCarScreen(ctx: CarContext) : Screen(ctx), SurfaceCallback, CarBridge.Listener {

    private enum class Overlay { NONE, STARTING, BLOCKED, DRIVING }

    private val handler = Handler(Looper.getMainLooper())
    private var display: VirtualDisplay? = null
    private var width = 0
    private var height = 0
    private var carDpi = 0
    private var stableArea: Rect? = null
    private var presentation: Presentation? = null
    private var presentationMode = Overlay.NONE
    private var presentationDisplayId = -1
    private var blocked = false
    private var launchPending = false
    private var destroyed = false

    private val touch = TouchReplayer(::deliverTouch, ::dragAnchor)
    private val driving = DrivingDetector(ctx) { refreshOverlay() }

    private val launchTimeout = Runnable {
        launchPending = false
        if (!activityOnCar()) {
            blocked = true
            refreshOverlay()
        }
    }

    init {
        ctx.getCarService(AppManager::class.java).setSurfaceCallback(this)
        CarBridge.listener = this
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                driving.sync()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                destroyed = true
                teardown()
            }
        })
    }

    // ---- Template ---------------------------------------------------------------------------------------------

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    override fun onGetTemplate(): Template = NavigationTemplate.Builder()
        .setActionStrip(
            ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setIcon(icon(R.drawable.car_ic_back))
                        .setOnClickListener { CarBridge.activity?.goBack() }
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setIcon(icon(R.drawable.car_ic_keyboard))
                        .setOnClickListener { openKeyboard() }
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setIcon(icon(R.drawable.car_ic_home))
                        .setOnClickListener { restartNuvio() }
                        .build()
                )
                .build()
        )
        .setMapActionStrip(
            ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setIcon(icon(R.drawable.car_ic_text_size))
                        .setOnClickListener { cycleSize() }
                        .build()
                )
                .build()
        )
        .build()

    private fun openKeyboard() {
        val activity = CarBridge.activity
        if (activity == null || activity.currentFocus == null) {
            CarToast.makeText(carContext, "Tap a search box in Nuvio first", CarToast.LENGTH_LONG).show()
        }
        screenManager.push(CarKeyboardScreen(carContext) { text ->
            val typed = CarBridge.activity?.typeText(text) == true
            if (!typed) CarToast.makeText(carContext, "Tap a search box in Nuvio first", CarToast.LENGTH_LONG).show()
        })
    }

    private fun cycleSize() {
        val index = CarPrefs.nextSize(carContext)
        CarToast.makeText(carContext, "Screen size: ${CarPrefs.SIZE_LABELS[index]}", CarToast.LENGTH_SHORT).show()
        val vd = display ?: return
        vd.resize(width, height, CarPrefs.scaledDpi(carContext, carDpi))
        handler.post { refreshOverlay() }
    }

    /** Home: a fresh Nuvio on its home screen. */
    private fun restartNuvio() {
        val activity = CarBridge.activity
        if (activity != null) {
            activity.finish() // onCarActivityGone launches a new one
        } else {
            blocked = false
            ensureNuvio()
        }
    }

    // ---- Surface ----------------------------------------------------------------------------------------------

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        width = container.width
        height = container.height
        carDpi = container.dpi
        val dpi = CarPrefs.scaledDpi(carContext, carDpi)
        val existing = display
        if (existing == null) {
            val dm = carContext.getSystemService(DisplayManager::class.java)
            // No PUBLIC flag: the display is private to this app, needs no permission, and only this app's
            // windows can appear on it.
            display = dm.createVirtualDisplay(
                "NuvioAuto", width, height, dpi, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
            ensureNuvio()
        } else {
            // Back from another app on the car screen: same display, Nuvio and playback untouched.
            existing.resize(width, height, dpi)
            existing.surface = surface
            ensureNuvio()
        }
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        // Keep the display and Nuvio alive while another app is on the car screen. A virtual display without a
        // surface turns off, which stops the activity (and with it playback), so it draws into a small throwaway
        // buffer instead until the car surface comes back.
        touch.cancel()
        display?.surface = parkingSurface()
    }

    private var parking: ImageReader? = null

    private fun parkingSurface(): android.view.Surface? {
        val reader = parking ?: runCatching {
            ImageReader.newInstance(PARK_W, PARK_H, PixelFormat.RGBA_8888, 2).apply {
                setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, handler)
            }
        }.getOrNull()?.also { parking = it }
        return reader?.surface
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        this.stableArea = Rect(stableArea)
        applyInsets()
    }

    private fun applyInsets() {
        val activity = CarBridge.activity ?: return
        val area = stableArea
        if (area == null || !CarPrefs.avoidCarButtons(carContext) || width == 0) {
            activity.setContentInsets(0, 0, 0, 0)
            return
        }
        activity.setContentInsets(
            area.left.coerceAtLeast(0),
            area.top.coerceAtLeast(0),
            (width - area.right).coerceAtLeast(0),
            (height - area.bottom).coerceAtLeast(0),
        )
    }

    // ---- Touch ------------------------------------------------------------------------------------------------

    override fun onClick(x: Float, y: Float) = touch.click(x, y)

    override fun onScroll(distanceX: Float, distanceY: Float) = touch.scroll(distanceX, distanceY)

    override fun onFling(velocityX: Float, velocityY: Float) = touch.fling(velocityX, velocityY)

    private fun dragAnchor(): PointF {
        val area = stableArea
        return if (area != null && !area.isEmpty) PointF(area.exactCenterX(), area.exactCenterY())
        else PointF(width / 2f, height / 2f)
    }

    private fun deliverTouch(event: MotionEvent) {
        when (presentationMode) {
            Overlay.NONE -> CarBridge.activity?.injectTouch(event)
            Overlay.BLOCKED -> if (event.actionMasked == MotionEvent.ACTION_UP) {
                blocked = false
                ensureNuvio()
            }
            // Starting or driving: the cover swallows touches.
            else -> Unit
        }
    }

    // ---- Nuvio activity on the car display --------------------------------------------------------------------

    private fun activityOnCar(): Boolean {
        val vd = display ?: return false
        return CarBridge.activity?.carDisplayId() == vd.display.displayId
    }

    /** Starts Nuvio on the car display unless it's already there. */
    private fun ensureNuvio() {
        if (destroyed) return
        val vd = display ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Launching onto a virtual display needs Android 10.
            blocked = true
            refreshOverlay()
            return
        }
        if (activityOnCar()) {
            refreshOverlay()
            applyInsets()
            return
        }
        if (launchPending || blocked) {
            refreshOverlay()
            return
        }
        // A Nuvio car activity left on an old display (surface recreated): replace it.
        CarBridge.activity?.finish()
        launchPending = true
        refreshOverlay() // the "Starting Nuvio" presentation is the visible window that allows the launch
        handler.postDelayed({
            if (destroyed || display == null) return@postDelayed
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(vd.display.displayId)
            val intent = Intent(carContext, CarNuvioActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            try {
                carContext.applicationContext.startActivity(intent, options.toBundle())
                handler.postDelayed(launchTimeout, LAUNCH_TIMEOUT_MS)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start Nuvio on the car display", e)
                launchPending = false
                blocked = true
                refreshOverlay()
            }
        }, PRE_LAUNCH_MS)
    }

    override fun onCarActivityShown(activity: CarNuvioActivity) {
        val vd = display ?: return
        if (activity.carDisplayId() != vd.display.displayId) return
        handler.removeCallbacks(launchTimeout)
        launchPending = false
        blocked = false
        applyInsets()
        refreshOverlay()
    }

    override fun onCarActivityGone(activity: CarNuvioActivity) {
        if (destroyed) return
        touch.cancel()
        // Home button, Nuvio crashed, or the system reclaimed it: bring Nuvio back on the car.
        handler.postDelayed({ ensureNuvio() }, 300)
    }

    // ---- Overlay (starting / blocked / driving) ---------------------------------------------------------------

    private fun wantedOverlay(): Overlay = when {
        display == null -> Overlay.NONE
        driving.moving && CarPrefs.hideVideoWhileDriving(carContext) -> Overlay.DRIVING
        blocked -> Overlay.BLOCKED
        !activityOnCar() -> Overlay.STARTING
        else -> Overlay.NONE
    }

    private fun refreshOverlay() {
        if (destroyed) return
        val mode = wantedOverlay()
        val vd = display
        if (mode == Overlay.NONE || vd == null) {
            dismissPresentation()
            return
        }
        val displayId = vd.display.displayId
        if (presentation?.isShowing == true && presentationMode == mode && presentationDisplayId == displayId) return
        dismissPresentation()
        val p = Presentation(carContext, vd.display, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        p.setContentView(overlayView(p, mode))
        p.setOnDismissListener {
            // Presentations dismiss themselves when the display's size or density changes: rebuild if still wanted.
            if (presentation === p) {
                presentation = null
                presentationMode = Overlay.NONE
                handler.post { refreshOverlay() }
            }
        }
        try {
            p.show()
            presentation = p
            presentationMode = mode
            presentationDisplayId = displayId
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't show the car overlay", e)
        }
    }

    private fun dismissPresentation() {
        val p = presentation ?: return
        presentation = null
        presentationMode = Overlay.NONE
        runCatching { p.dismiss() }
    }

    private fun overlayView(p: Presentation, mode: Overlay): FrameLayout {
        val ctx = p.context
        fun dp(n: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), ctx.resources.displayMetrics).toInt()
        fun text(value: String, sizeSp: Float, alpha: Int) = TextView(ctx).apply {
            text = value
            textSize = sizeSp
            setTextColor((alpha shl 24) or 0xFFFFFF)
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(8), dp(32), dp(8))
        }
        val (title, body) = when (mode) {
            Overlay.STARTING -> "Starting Nuvio…" to ""
            Overlay.BLOCKED -> "Nuvio couldn't open on the car screen" to
                "Open Nuvio Auto on your phone once, then tap here to try again."
            Overlay.DRIVING -> "Video hidden while driving" to
                "Audio keeps playing. The picture comes back when you stop."
            Overlay.NONE -> "" to ""
        }
        return FrameLayout(ctx).apply {
            setBackgroundColor(0xFF0D0D0D.toInt())
            addView(
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(text(title, 24f, 0xFF))
                    if (body.isNotEmpty()) addView(text(body, 17f, 0xB3))
                },
                FrameLayout.LayoutParams(-1, -2, Gravity.CENTER),
            )
        }
    }

    // ---- Teardown ---------------------------------------------------------------------------------------------

    private fun teardown() {
        handler.removeCallbacksAndMessages(null)
        touch.cancel()
        driving.stop()
        if (CarBridge.listener === this) CarBridge.listener = null
        dismissPresentation()
        CarBridge.activity?.finish()
        display?.release()
        display = null
        parking?.close()
        parking = null
    }

    private companion object {
        const val TAG = "NuvioAuto"
        const val PRE_LAUNCH_MS = 250L
        const val LAUNCH_TIMEOUT_MS = 6000L
        const val PARK_W = 320
        const val PARK_H = 180
    }
}
