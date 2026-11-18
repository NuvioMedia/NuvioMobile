package com.nuvio.app.car

import android.os.Build
import android.os.Bundle
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import com.nuvio.app.MainActivity

/**
 * The full Nuvio app, launched by [NuvioCarScreen] onto the private virtual display that draws on the
 * Android Auto screen. Same UI, account, addons and player as the phone app; only the window lives on the car.
 */
class CarNuvioActivity : MainActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The phone may be locked in a pocket or a mount: this window only ever shows on the car display.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(true)
        // Registered before Nuvio's own back handlers, so it only runs when Nuvio has nothing left to go
        // back to. Finishing here would leave the car screen black, so the root just stays put.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        CarLog.init(this)
        val id = carDisplayId()
        CarLog.d("CarNuvioActivity created on display $id")
        if (id == android.view.Display.DEFAULT_DISPLAY) {
            // Android ignored the car display and opened it on the phone: don't leave a second Nuvio there.
            CarLog.d("Landed on the phone screen instead of the car; closing it")
            finish()
            return
        }
        CarBridge.attach(this)
    }

    override fun onResume() {
        super.onResume()
        CarBridge.shown(this)
    }

    // No picture-in-picture on the car display.
    override fun onUserLeaveHint() = Unit

    override fun onDestroy() {
        CarBridge.detach(this)
        super.onDestroy()
    }

    /**
     * The display this activity is on: it must be the car's virtual display, never the phone. Not named
     * displayId, so it can't shadow the framework's hidden Context.getDisplayId().
     */
    @Suppress("DEPRECATION")
    fun carDisplayId(): Int = windowManager.defaultDisplay.displayId

    fun injectTouch(event: MotionEvent) {
        dispatchTouchEvent(event)
    }

    fun goBack() {
        onBackPressedDispatcher.onBackPressed()
    }

    /** Keeps Nuvio's content inside the part of the car screen that Android Auto's buttons never cover. */
    fun setContentInsets(left: Int, top: Int, right: Int, bottom: Int) {
        findViewById<View>(android.R.id.content)?.setPadding(left, top, right, bottom)
    }

    /**
     * Types [text] into whatever text field is focused on the car screen (tap Nuvio's search box first),
     * replacing what's there, then presses Enter to run the search.
     */
    fun typeText(text: String): Boolean {
        if (currentFocus == null) return false
        val now = android.os.SystemClock.uptimeMillis()
        fun key(code: Int, meta: Int = 0) {
            dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
            dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
        }
        key(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        key(KeyEvent.KEYCODE_DEL)
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        for (ch in text) {
            val events = map.getEvents(charArrayOf(ch))
            if (events != null) {
                events.forEach(::dispatchKeyEvent)
            } else {
                // Characters the virtual keymap can't produce (accents, emoji) go in as a raw character event.
                @Suppress("DEPRECATION")
                dispatchKeyEvent(KeyEvent(now, ch.toString(), KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
            }
        }
        key(KeyEvent.KEYCODE_ENTER)
        return true
    }
}
