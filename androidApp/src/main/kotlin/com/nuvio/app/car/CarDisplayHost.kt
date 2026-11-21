package com.nuvio.app.car

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.nuvio.app.features.player.NuvioPlaybackRemote

/**
 * Runs Nuvio on a car surface, whichever way Android Auto hands one over: a private VirtualDisplay renders
 * into the car surface and [CarNuvioWindow] (the whole Nuvio UI in a Presentation) is shown on it, the same
 * way YT Auto shows YouTube.
 *
 * Used by [NuvioCarScreen] (Car App Library navigation surface) and [LegacyCarActivity] (projection API, the
 * route Fermata and YT Auto use). Main thread only.
 */
internal class CarDisplayHost(private val ctx: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var display: VirtualDisplay? = null
    private var width = 0
    private var height = 0
    private var carDpi = 0
    private var displayDpi = 0
    private var stableArea: Rect? = null
    private var window: CarNuvioWindow? = null
    private var parking: ImageReader? = null
    private var released = false

    private val replayer = TouchReplayer(::deliverTouch, ::dragAnchor)
    private val driving = DrivingDetector(ctx) { updateCover() }

    init {
        CarLog.init(ctx)
        CarLog.d("Car session started (${ctx.javaClass.simpleName}). ${CarLog.deviceLine()}")
        CarSession.start(ctx, this)
    }

    // ---- Surface ----------------------------------------------------------------------------------------------

    fun attachSurface(surface: Surface, w: Int, h: Int, dpi: Int) {
        if (released || w <= 0 || h <= 0) return
        carDpi = dpi
        val scaled = CarPrefs.scaledDpi(ctx, carDpi)
        val existing = display
        CarLog.d("Car surface ${w}x$h, car dpi $dpi, Nuvio dpi $scaled")
        if (existing == null) {
            val dm = ctx.getSystemService(DisplayManager::class.java)
            // No PUBLIC flag: private to this app, needs no permission, nothing else can appear on it.
            display = dm.createVirtualDisplay(
                "NuvioAuto", w, h, scaled, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
            CarLog.d("Virtual display ${display?.display?.displayId} created")
        } else {
            // Back from another app on the car screen: same display, Nuvio and playback untouched.
            if (w != width || h != height || scaled != displayDpi) existing.resize(w, h, scaled)
            existing.surface = surface
        }
        width = w
        height = h
        displayDpi = scaled
        driving.sync()
        ensureNuvio()
    }

    /**
     * The car surface is gone (another app is on the car screen). A virtual display without a surface turns
     * off and Nuvio's window would stop (and playback with it), so it draws into a small throwaway buffer.
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
        val w = window ?: return
        val area = stableArea
        if (area == null || area.isEmpty || !CarPrefs.avoidCarButtons(ctx) || width == 0) {
            w.setContentInsets(0, 0, 0, 0)
            return
        }
        w.setContentInsets(
            area.left.coerceAtLeast(0),
            area.top.coerceAtLeast(0),
            (width - area.right).coerceAtLeast(0),
            (height - area.bottom).coerceAtLeast(0),
        )
    }

    // ---- Nuvio window -----------------------------------------------------------------------------------------

    private fun ensureNuvio() {
        if (released) return
        val vd = display ?: return
        val current = window
        if (current != null && current.isShowing && current.display.displayId == vd.display.displayId &&
            current.context.resources.configuration.densityDpi == displayDpi
        ) {
            applyInsets()
            updateCover()
            return
        }
        // First start, or the size setting changed the density: (re)build Nuvio's window for this display.
        current?.close()
        val w = CarNuvioWindow(ctx, vd.display)
        try {
            w.show()
            window = w
            CarLog.d("Nuvio is on the car display")
        } catch (e: Exception) {
            CarLog.d("Couldn't show Nuvio on the car display", e)
            window = null
            return
        }
        applyInsets()
        updateCover()
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
        if (coverShowing()) {
            // Audio-only (not driving): a tap on the cover brings the picture back.
            if (audioOnly && !drivingCover() && event.actionMasked == MotionEvent.ACTION_UP) toggleAudioOnly()
            return
        }
        window?.injectTouch(event)
    }

    fun back() {
        window?.goBack()
    }

    fun typeText(text: String): Boolean = window?.typeText(text) == true

    fun hasFocusedField(): Boolean = window?.hasFocusedField() == true

    /** Home: a fresh Nuvio on its home screen. */
    fun home() {
        CarLog.d("Home: restarting Nuvio on the car")
        window?.close()
        window = null
        ensureNuvio()
    }

    /** Steps to the next car UI size; returns its label. Rebuilds Nuvio's window at the new density. */
    fun cycleSize(): String {
        val index = CarPrefs.nextSize(ctx)
        val vd = display
        if (vd != null) {
            displayDpi = CarPrefs.scaledDpi(ctx, carDpi)
            vd.resize(width, height, displayDpi)
            handler.post { ensureNuvio() }
        }
        return CarPrefs.SIZE_LABELS[index]
    }

    // ---- Driving cover ----------------------------------------------------------------------------------------

    /** Audio-only: picture covered on purpose (button in the car), sound keeps playing. */
    var audioOnly = false
        private set

    fun toggleAudioOnly(): Boolean {
        audioOnly = !audioOnly
        CarLog.d("Audio only: $audioOnly")
        window?.cover?.let { it.visibility = View.GONE } // rebuild with the right text
        updateCover()
        return audioOnly
    }

    private fun drivingCover(): Boolean = driving.moving && CarPrefs.hideVideoWhileDriving(ctx)

    private fun coverShowing(): Boolean = drivingCover() || audioOnly

    private fun updateCover() {
        val w = window ?: return
        val cover = w.cover
        if (!coverShowing()) {
            cover.visibility = View.GONE
            return
        }
        val kind = if (drivingCover()) "driving" else "audio"
        if (cover.visibility != View.VISIBLE || cover.tag != kind) {
            cover.removeAllViews()
            buildCover(cover)
            cover.tag = kind
        }
        cover.visibility = View.VISIBLE
    }

    private fun buildCover(cover: FrameLayout) {
        val c = cover.context
        fun dp(n: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), c.resources.displayMetrics).toInt()
        fun line(value: String, sizeSp: Float, alpha: Int) = TextView(c).apply {
            text = value
            textSize = sizeSp
            setTextColor((alpha shl 24) or 0xFFFFFF)
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(8), dp(32), dp(8))
        }
        cover.setBackgroundColor(0xFF0D0D0D.toInt())
        cover.isClickable = true
        cover.addView(
            LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(line(if (drivingCover()) "Video hidden while driving" else "Audio only", 24f, 0xFF))
                val playback = NuvioPlaybackRemote.state.value
                if (playback.active && !playback.title.isNullOrBlank()) {
                    addView(line(listOfNotNull(playback.title, playback.subtitle).joinToString(" · "), 19f, 0xE6))
                }
                addView(
                    line(
                        if (drivingCover()) "Audio keeps playing. Use the steering wheel or the buttons to pause or skip."
                        else "Tap the audio-only button again to bring the picture back.",
                        17f, 0xB3,
                    ),
                )
            },
            FrameLayout.LayoutParams(-1, -2, Gravity.CENTER),
        )
    }

    // ---- Teardown ---------------------------------------------------------------------------------------------

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        replayer.cancel()
        driving.stop()
        CarLog.d("Car session ended")
        CarSession.stop(this)
        window?.close()
        window = null
        display?.release()
        display = null
        parking?.close()
        parking = null
    }

    private companion object {
        const val PARK_W = 320
        const val PARK_H = 180
    }
}
