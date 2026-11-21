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
    private var resumeSeenAt = 0L

    private val hideBar = Runnable { bar.animate().alpha(0f).setDuration(250).withEndAction { bar.visibility = View.GONE } }
    private val poll = object : Runnable {
        override fun run() {
            rebuildBar(force = false)
            if (!resumeOffered && ExternalAppCommands.continueWatching.value.isNotEmpty()) {
                if (resumeSeenAt == 0L) resumeSeenAt = android.os.SystemClock.uptimeMillis()
                if (android.os.SystemClock.uptimeMillis() - resumeSeenAt > RESUME_DELAY_MS) maybeOfferResume()
            }
            main.postDelayed(this, 600)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIgnoreConfigChanges(0xFFFFFFFF.toInt())
        runCatching {
            val ui = carUiController
            ui.menuController.hideMenuButton()
            // Clear Android Auto's status overlay (signal, battery, clock, mic, title) off Nuvio's picture.
            if (CarPrefs.HIDE_STATUS_OVERLAY.get(this)) ui.statusBarController.run {
                hideAppHeader()
                hideTitle()
                hideClock()
                hideBatteryLevel()
                hideConnectivityLevel()
                hideMicButton()
                setAppBarAlpha(0f)
            }
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
            val atBottom = CarPrefs.BAR_AT_BOTTOM.get(this@LegacyCarActivity)
            addView(bar, FrameLayout.LayoutParams(-2, -2, (if (atBottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.END).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
                rightMargin = dp(12)
            })
            addView(resumeCard, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
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
        val timeout = CarPrefs.BAR_TIMEOUTS[CarPrefs.barTimeoutIndex(this)]
        if (timeout > 0) main.postDelayed(hideBar, timeout)
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
            addButton(R.drawable.car_ic_next_episode) { NuvioPlaybackRemote.nextEpisode() }
            addButton(R.drawable.car_ic_audio_only) {
                val on = host?.toggleAudioOnly() ?: return@addButton
                Toast.makeText(this, if (on) "Audio only" else "Picture back on", Toast.LENGTH_SHORT).show()
            }
        } else {
            addButton(R.drawable.car_ic_search) { startCarKeyboard() }
            addButton(R.drawable.car_ic_home) { tab(AppScreenTab.Home) }
            addButton(R.drawable.car_ic_library) { tab(AppScreenTab.Library) }
            addButton(R.drawable.car_ic_downloads) {
                ExternalAppCommands.send(ExternalAppCommands.Command.OpenDownloads)
            }
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

    // ---- "Continue watching?" ----------------------------------------------------------------------------------

    private val resumeCard: LinearLayout by lazy { LinearLayout(this).apply { visibility = View.GONE } }
    private var resumeOffered = false

    /** Once per drive, when the home screen's Continue Watching row first has something. */
    private fun maybeOfferResume() {
        if (resumeOffered || !CarPrefs.resumePrompt(this)) return
        if (ExternalAppCommands.screen.value != ExternalAppCommands.Screen.HOME) return
        val item = ExternalAppCommands.continueWatching.value.firstOrNull() ?: return
        resumeOffered = true
        CarLog.d("Offering to resume ${item.title}")
        resumeCard.removeAllViews()
        resumeCard.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(22), dp(28), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(0xF0, 20, 20, 28))
            }
            fun label(text: String, sp: Float, alpha: Int) = android.widget.TextView(context).apply {
                this.text = text
                textSize = sp
                setTextColor(Color.argb(alpha, 255, 255, 255))
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, dp(2))
            }
            addView(label("Continue watching?", 16f, 0xB3))
            addView(label(item.title, 22f, 0xFF))
            item.subtitle.takeIf { it.isNotBlank() }?.let { addView(label(it, 15f, 0xB3)) }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(14), 0, 0)
                fun pill(text: String, primary: Boolean, onClick: () -> Unit) = android.widget.Button(context).apply {
                    this.text = text
                    isAllCaps = false
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(22).toFloat()
                        setColor(if (primary) Color.rgb(124, 77, 255) else Color.argb(0x40, 255, 255, 255))
                    }
                    setOnClickListener { onClick() }
                }
                addView(pill("Resume", true) {
                    resumeCard.visibility = View.GONE
                    ExternalAppCommands.send(ExternalAppCommands.Command.Resume(item))
                }, LinearLayout.LayoutParams(dp(150), dp(52)).apply { marginEnd = dp(12) })
                addView(pill("Not now", false) { resumeCard.visibility = View.GONE }, LinearLayout.LayoutParams(dp(150), dp(52)))
            })
            visibility = View.VISIBLE
        }
        main.postDelayed({ resumeCard.visibility = View.GONE }, 20_000L)
    }

    private companion object {
        const val RESUME_DELAY_MS = 2_500L
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
