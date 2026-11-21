package com.nuvio.app.car

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.apps.auto.sdk.CarActivityService
import com.nuvio.android.R
import com.nuvio.app.AppScreenTab
import com.nuvio.app.core.external.ExternalAppCommands
import com.nuvio.app.features.player.NuvioPlaybackRemote
import android.support.car.input.CarRestrictedEditText
import com.google.android.gms.car.input.CarEditable
import com.google.android.gms.car.input.CarEditableListener

/**
 * "Full screen app" car mode: the projection entry point through the unofficial Android Auto SDK
 * (aauto.aar, from Fermata), the route Fermata uses. Android Auto treats Nuvio here as an ordinary app, not
 * a navigation app, so Google Maps stays the navigation app and keeps the car's cockpit directions while
 * Nuvio fills the screen.
 *
 * A SurfaceView fills the car screen and [CarDisplayHost] runs Nuvio on it. Touches are real finger events.
 * There's no Android Auto button strip in this mode, so a small button bar floats over Nuvio: it shows on
 * any touch and fades after a few seconds. The car keyboard comes from the SDK's input manager.
 */
class LegacyCarActivity : CarActivity() {

    private var host: CarDisplayHost? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var bar: LinearLayout
    private var barMode: String = ""
    private var searchField: CarSearchField? = null

    private val hideBar = Runnable { bar.animate().alpha(0f).setDuration(250).withEndAction { bar.visibility = View.GONE } }
    private val poll = object : Runnable {
        override fun run() {
            rebuildBar(force = false)
            main.postDelayed(this, 600)
        }
    }

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
        val surfaceView = object : SurfaceView(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) showBar()
                h.touch(event)
                return true
            }
        }
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                h.attachSurface(holder.surface, width, height, dpi)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) = h.detachSurface()
        })

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                setColor(Color.argb(0xCC, 16, 16, 22))
            }
        }
        val root = FrameLayout(this).apply {
            addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
            addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(12)
                rightMargin = dp(12)
            })
        }
        setContentView(root)
        rebuildBar(force = true)
        showBar()
        main.post(poll)
    }

    // ---- Button bar -------------------------------------------------------------------------------------------

    private fun dp(n: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), resources.displayMetrics).toInt()

    private fun showBar() {
        main.removeCallbacks(hideBar)
        bar.animate().cancel()
        bar.visibility = View.VISIBLE
        bar.alpha = 1f
        main.postDelayed(hideBar, BAR_TIMEOUT_MS)
    }

    /** Browsing: Back, Search, Home, Library, Size. Watching: Back, -10 s, play/pause, +30 s. */
    private fun rebuildBar(force: Boolean) {
        val playback = NuvioPlaybackRemote.state.value
        val watching = ExternalAppCommands.screen.value == ExternalAppCommands.Screen.PLAYER && playback.active
        val mode = if (watching) "watch:${playback.playing}" else "browse"
        if (!force && mode == barMode) return
        barMode = mode
        bar.removeAllViews()
        addButton(R.drawable.car_ic_back) { host?.back() }
        if (watching) {
            addButton(R.drawable.car_ic_replay_10) { NuvioPlaybackRemote.seekBy(-10_000L) }
            addButton(if (playback.playing) R.drawable.car_ic_pause else R.drawable.car_ic_play) {
                NuvioPlaybackRemote.togglePlayPause()
            }
            addButton(R.drawable.car_ic_forward_30) { NuvioPlaybackRemote.seekBy(30_000L) }
        } else {
            addButton(R.drawable.car_ic_search) { startCarKeyboard() }
            addButton(R.drawable.car_ic_home) { tab(AppScreenTab.Home) }
            addButton(R.drawable.car_ic_library) { tab(AppScreenTab.Library) }
            addButton(R.drawable.car_ic_text_size) {
                val label = host?.cycleSize() ?: return@addButton
                Toast.makeText(this, "Screen size: $label", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addButton(icon: Int, onClick: () -> Unit) {
        bar.addView(
            ImageButton(this).apply {
                setImageResource(icon)
                setColorFilter(Color.WHITE)
                scaleType = android.widget.ImageView.ScaleType.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(0x33, 255, 255, 255))
                }
                setOnClickListener {
                    showBar()
                    onClick()
                }
            },
            LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(4); marginEnd = dp(4) },
        )
    }

    private fun tab(tab: AppScreenTab) {
        ExternalAppCommands.send(ExternalAppCommands.Command.OpenTab(tab))
    }

    // ---- Car keyboard -----------------------------------------------------------------------------------------

    private fun startCarKeyboard() {
        runCatching {
            val field = searchField ?: CarSearchField(this) { query ->
                stopCarKeyboard()
                if (query.isNotBlank()) ExternalAppCommands.send(ExternalAppCommands.Command.Search(query.trim()))
            }.also { searchField = it }
            field.setText("")
            a().startInput(field)
        }.onFailure {
            CarLog.d("Car keyboard unavailable", it)
            Toast.makeText(this, "Keyboard unavailable here: use the phone remote", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopCarKeyboard() {
        runCatching { if (a().isInputActive) a().stopInput() }
    }

    override fun onBackPressed() {
        if (runCatching { a().isInputActive }.getOrDefault(false)) {
            stopCarKeyboard()
            return
        }
        host?.back()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        stopCarKeyboard()
        host?.release()
        host = null
        super.onDestroy()
    }

    private companion object {
        const val BAR_TIMEOUT_MS = 4_000L
    }
}

/** Off-screen text field the Android Auto keyboard types into; Search on the keyboard submits it. */
private class CarSearchField(
    ctx: Context,
    private val onSubmit: (String) -> Unit,
) : CarRestrictedEditText(ctx, null), CarEditable {

    init {
        setSingleLine()
        imeOptions = EditorInfo.IME_ACTION_SEARCH
    }

    override fun setCarEditableListener(listener: CarEditableListener) {
        super.setCarEditableListener(
            android.support.car.input.CarEditableListener { selStart, selEnd, candStart, candEnd ->
                listener.onUpdateSelection(selStart, selEnd, candStart, candEnd)
            },
        )
    }

    override fun onEditorAction(actionCode: Int) {
        when (actionCode) {
            EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_NEXT -> onSubmit(text?.toString().orEmpty())
            else -> super.onEditorAction(actionCode)
        }
    }
}

/** Bound by Android Auto (CATEGORY_PROJECTION) to show [LegacyCarActivity] on the head unit. */
class LegacyCarService : CarActivityService() {
    override fun getCarActivity(): Class<out CarActivity> = LegacyCarActivity::class.java
}
