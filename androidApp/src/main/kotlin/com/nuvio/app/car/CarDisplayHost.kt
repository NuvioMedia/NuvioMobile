package com.nuvio.app.car

import android.app.ActivityOptions
import android.app.Presentation
import android.content.Context
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
import android.view.Surface
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Runs the real Nuvio app on a car surface, whichever way Android Auto hands one over.
 *
 * A private VirtualDisplay renders into the car surface and [CarNuvioActivity] (the normal Nuvio activity) is
 * launched onto it. A Presentation on the same display shows the "starting" and "hidden while driving" covers,
 * and is also the visible window that lets the app start its activity while the phone is in the background.
 *
 * Used by [NuvioCarScreen] (Car App Library navigation surface) and [LegacyCarActivity] (projection API, the
 * route Fermata and YT Auto use). Main thread only.
 */
internal class CarDisplayHost(private val ctx: Context) : CarBridge.Listener {

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
    private var parking: ImageReader? = null
    private var blocked = false
    private var launchPending = false
    private var released = false

    private val replayer = TouchReplayer(::deliverTouch, ::dragAnchor)
    private val driving = DrivingDetector(ctx) { refreshOverlay() }

    private val launchTimeout = Runnable {
        launchPending = false
        if (!activityOnCar()) {
            blocked = true
            refreshOverlay()
        }
    }

    init {
        CarBridge.listener = this
    }

    // ---- Surface ----------------------------------------------------------------------------------------------

    fun attachSurface(surface: Surface, w: Int, h: Int, dpi: Int) {
        if (released || w <= 0 || h <= 0) return
        width = w
        height = h
        carDpi = dpi
        val scaled = CarPrefs.scaledDpi(ctx, carDpi)
        val existing = display
        if (existing == null) {
            val dm = ctx.getSystemService(DisplayManager::class.java)
            // No PUBLIC flag: private to this app, needs no permission, nothing else can appear on it.
            display = dm.createVirtualDisplay(
                "NuvioAuto", w, h, scaled, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
        } else {
            // Back from another app on the car screen: same display, Nuvio and playback untouched.
            existing.resize(w, h, scaled)
            existing.surface = surface
        }
        driving.sync()
        ensureNuvio()
    }

    /**
     * The car surface is gone (another app is on the car screen). A virtual display without a surface turns
     * off, which stops the activity and with it playback, so it draws into a small throwaway buffer instead.
     */
    fun detachSurface() {
        replayer.cancel()
        display?.surface = parkingSurface()
    }

    private fun parkingSurface(): Surface? {
        val reader = parking ?: runCatching {
            ImageReader.newInstance(PARK_W, PARK_H, PixelFormat.RGBA_8888, 2).apply {
                setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, handler)
            }
        }.getOrNull()?.also { parking = it }
        return reader?.surface
    }

    fun setStableArea(area: Rect?) {
        stableArea = area?.let(::Rect)
        applyInsets()
    }

    private fun applyInsets() {
        val activity = CarBridge.activity ?: return
        val area = stableArea
        if (area == null || area.isEmpty || !CarPrefs.avoidCarButtons(ctx) || width == 0) {
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

    // ---- Input ------------------------------------------------------------------------------------------------

    /** Template gestures (Car App Library), replayed as finger touches. */
    fun click(x: Float, y: Float) = replayer.click(x, y)

    fun scroll(distanceX: Float, distanceY: Float) = replayer.scroll(distanceX, distanceY)

    fun fling(velocityX: Float, velocityY: Float) = replayer.fling(velocityX, velocityY)

    /** Real touches (projection API), already in car-surface pixels. */
    fun touch(event: MotionEvent) = deliverTouch(event)

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

    fun back() {
        CarBridge.activity?.goBack()
    }

    /** Types into Nuvio's focused text field; false if nothing is focused. */
    fun typeText(text: String): Boolean = CarBridge.activity?.typeText(text) == true

    fun hasFocusedField(): Boolean = CarBridge.activity?.currentFocus != null

    /** Home: a fresh Nuvio on its home screen. */
    fun home() {
        val activity = CarBridge.activity
        if (activity != null) {
            activity.finish() // onCarActivityGone starts a new one
        } else {
            blocked = false
            ensureNuvio()
        }
    }

    /** Steps to the next car UI size; returns its label. */
    fun cycleSize(): String {
        val index = CarPrefs.nextSize(ctx)
        display?.resize(width, height, CarPrefs.scaledDpi(ctx, carDpi))
        handler.post { refreshOverlay() }
        return CarPrefs.SIZE_LABELS[index]
    }

    // ---- Nuvio activity on the car display --------------------------------------------------------------------

    private fun activityOnCar(): Boolean {
        val vd = display ?: return false
        return CarBridge.activity?.carDisplayId() == vd.display.displayId
    }

    /** Starts Nuvio on the car display unless it's already there. */
    private fun ensureNuvio() {
        if (released) return
        val vd = display ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            blocked = true // launching onto a virtual display needs Android 10
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
        CarBridge.activity?.finish() // left on an old display: replace it
        launchPending = true
        refreshOverlay() // the "Starting Nuvio" presentation is the visible window that allows the launch
        handler.postDelayed({
            if (released || display == null) return@postDelayed
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(vd.display.displayId)
            val intent = Intent(ctx, CarNuvioActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            try {
                ctx.applicationContext.startActivity(intent, options.toBundle())
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
        if (released) return
        replayer.cancel()
        // Home button, a crash, or the system reclaimed it: bring Nuvio back on the car.
        handler.postDelayed({ ensureNuvio() }, 300)
    }

    // ---- Overlay ----------------------------------------------------------------------------------------------

    private fun wantedOverlay(): Overlay = when {
        display == null -> Overlay.NONE
        driving.moving && CarPrefs.hideVideoWhileDriving(ctx) -> Overlay.DRIVING
        blocked -> Overlay.BLOCKED
        !activityOnCar() -> Overlay.STARTING
        else -> Overlay.NONE
    }

    private fun refreshOverlay() {
        if (released) return
        val mode = wantedOverlay()
        val vd = display
        if (mode == Overlay.NONE || vd == null) {
            dismissPresentation()
            return
        }
        val displayId = vd.display.displayId
        if (presentation?.isShowing == true && presentationMode == mode && presentationDisplayId == displayId) return
        dismissPresentation()
        val p = Presentation(ctx, vd.display, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
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
        val c = p.context
        fun dp(n: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), c.resources.displayMetrics).toInt()
        fun line(value: String, sizeSp: Float, alpha: Int) = TextView(c).apply {
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
        return FrameLayout(c).apply {
            setBackgroundColor(0xFF0D0D0D.toInt())
            addView(
                LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(line(title, 24f, 0xFF))
                    if (body.isNotEmpty()) addView(line(body, 17f, 0xB3))
                },
                FrameLayout.LayoutParams(-1, -2, Gravity.CENTER),
            )
        }
    }

    // ---- Teardown ---------------------------------------------------------------------------------------------

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        replayer.cancel()
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
