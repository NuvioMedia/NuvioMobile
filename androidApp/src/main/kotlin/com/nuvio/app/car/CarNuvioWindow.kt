package com.nuvio.app.car

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Display
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.OnBackCompletedFallback
import androidx.navigationevent.setViewTreeNavigationEventDispatcherOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nuvio.app.NuvioEmbeddedView

/**
 * Nuvio on the car display, the way YT Auto shows YouTube: a Presentation window (which any app may show on
 * its own virtual display) holding the whole Nuvio UI. Android 15+ refuses to start even an app's own
 * activity on a virtual display it created, so this window plays the activity's part: it provides the
 * lifecycle, view models, saved state, back handling and activity-result plumbing Nuvio's screens expect.
 *
 * [cover] sits above Nuvio for the "starting" and "hidden while driving" screens.
 */
internal class CarNuvioWindow(
    ctx: Context,
    display: Display,
) : Presentation(ctx, display, android.R.style.Theme_Black_NoTitleBar_Fullscreen),
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner, OnBackPressedDispatcherOwner,
    ActivityResultRegistryOwner, NavigationEventDispatcherOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    // At Nuvio's root there's nothing to go back to: stay (closing would leave the car screen black).
    override val onBackPressedDispatcher = OnBackPressedDispatcher { }

    /**
     * Back handling for Nuvio's navigation (NavDisplay and BackHandler), like ComponentActivity provides.
     * Anything it doesn't handle falls through to [onBackPressedDispatcher].
     */
    override val navigationEventDispatcher = NavigationEventDispatcher(
        OnBackCompletedFallback { onBackPressedDispatcher.onBackPressed() },
    )

    private val backInput: DirectNavigationEventInput by lazy {
        DirectNavigationEventInput().also { navigationEventDispatcher.addInput(it) }
    }

    /** Pickers and external players open on the phone; Nuvio gets "cancelled" back right away. */
    override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            runCatching {
                val intent = contract.createIntent(context, input).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.applicationContext.startActivity(intent)
            }.onFailure { CarLog.d("Couldn't open ${contract.javaClass.simpleName} from the car", it) }
            dispatchResult(requestCode, Activity.RESULT_CANCELED, null)
        }
    }

    lateinit var cover: FrameLayout
        private set
    private var nuvio: View? = null
    private var closing = false

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val decor = window!!.decorView
        decor.setViewTreeLifecycleOwner(this)
        decor.setViewTreeViewModelStoreOwner(this)
        decor.setViewTreeSavedStateRegistryOwner(this)
        decor.setViewTreeOnBackPressedDispatcherOwner(this)
        decor.setViewTreeNavigationEventDispatcherOwner(this)
        window!!.setBackgroundDrawableResource(android.R.color.black)
        window!!.setLayout(-1, -1)
        val root = FrameLayout(context)
        val view = NuvioEmbeddedView.create(context, this, this, this)
        nuvio = view
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        cover = FrameLayout(context).apply { visibility = View.GONE }
        root.addView(cover, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        CarLog.d("Nuvio UI created on display ${display.displayId}")
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onStop() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onStop()
    }

    /**
     * Presentations cancel themselves when the display's size or density changes. Nuvio's screens and playback
     * would go with it, so only [close] (from the host) really dismisses this window.
     */
    override fun cancel() {
        if (closing) super.cancel() else CarLog.d("Ignored automatic dismissal of the car window")
    }

    fun close() {
        closing = true
        runCatching { dismiss() }
    }

    /** Keeps Nuvio inside the part of the car screen that Android Auto's buttons never cover. */
    fun setContentInsets(left: Int, top: Int, right: Int, bottom: Int) {
        nuvio?.let { view ->
            (view.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.leftMargin != left || lp.topMargin != top || lp.rightMargin != right || lp.bottomMargin != bottom) {
                    lp.setMargins(left, top, right, bottom)
                    view.layoutParams = lp
                }
            }
        }
    }

    fun injectTouch(event: MotionEvent) {
        window?.decorView?.dispatchTouchEvent(event)
    }

    fun goBack() {
        backInput.backCompleted()
    }

    fun hasFocusedField(): Boolean = currentFocus != null

    /**
     * Types [text] into the focused text field (tap Nuvio's search box first), replacing what's there, then
     * presses Enter to run the search.
     */
    fun typeText(text: String): Boolean {
        val decor = window?.decorView ?: return false
        if (currentFocus == null) return false
        val now = android.os.SystemClock.uptimeMillis()
        fun key(code: Int, meta: Int = 0) {
            decor.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
            decor.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
        }
        key(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        key(KeyEvent.KEYCODE_DEL)
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        for (ch in text) {
            val events = map.getEvents(charArrayOf(ch))
            if (events != null) {
                events.forEach { decor.dispatchKeyEvent(it) }
            } else {
                @Suppress("DEPRECATION")
                decor.dispatchKeyEvent(KeyEvent(now, ch.toString(), KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
            }
        }
        key(KeyEvent.KEYCODE_ENTER)
        return true
    }
}
