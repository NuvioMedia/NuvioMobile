package com.nuvio.app.car

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nuvio.app.AppScreenTab
import com.nuvio.app.core.external.ExternalAppCommands
import com.nuvio.app.features.player.NuvioPlaybackRemote

/**
 * Phone remote for Nuvio on the car screen, for a passenger: search with the phone keyboard, play/pause and
 * skip, and jump between Home, Search and Library. Opened from the "Nuvio is on your car screen" notification
 * or Nuvio Auto Setup.
 */
class CarRemoteActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var playPause: Button
    private lateinit var searchBox: EditText

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(BG)
        fun dp(n: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), resources.displayMetrics).toInt()
        fun label(value: String, sp: Float, color: Int = Color.WHITE, top: Int = 8) = TextView(this).apply {
            text = value
            textSize = sp
            setTextColor(color)
            setPadding(0, dp(top), 0, dp(4))
        }
        fun button(value: String, onClick: () -> Unit) = Button(this).apply {
            text = value
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(CARD)
            }
            setOnClickListener { onClick() }
        }
        fun row(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
            views.forEachIndexed { i, v ->
                addView(v, LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                    if (i > 0) marginStart = dp(8)
                })
            }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(44), dp(20), dp(32))
        }
        column.addView(label("Nuvio remote", 28f, top = 0))
        status = label("", 14f, MUTED)
        column.addView(status)

        column.addView(label("Search", 19f, top = 20))
        searchBox = EditText(this).apply {
            hint = "Movie or show name"
            setHintTextColor(MUTED)
            setTextColor(Color.WHITE)
            textSize = 18f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(CARD)
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnEditorActionListener { _, actionId, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                    search()
                    true
                } else {
                    false
                }
            }
        }
        column.addView(searchBox, LinearLayout.LayoutParams(-1, -2))
        column.addView(row(button("Search on car") { search() }))

        column.addView(label("Go to", 19f, top = 20))
        column.addView(row(
            button("Home") { tab(AppScreenTab.Home) },
            button("Search") { tab(AppScreenTab.Search) },
            button("Library") { tab(AppScreenTab.Library) },
        ))
        column.addView(row(button("Back") { CarSession.host?.back() }))

        column.addView(label("Playback", 19f, top = 20))
        nowPlaying = label("", 15f, MUTED)
        column.addView(nowPlaying)
        playPause = button("Play / Pause") { NuvioPlaybackRemote.togglePlayPause() }
        column.addView(row(
            button("−10 s") { NuvioPlaybackRemote.seekBy(-10_000L) },
            playPause,
            button("+30 s") { NuvioPlaybackRemote.seekBy(30_000L) },
        ))
        column.addView(row(
            button("−2 min") { NuvioPlaybackRemote.seekBy(-120_000L) },
            button("+2 min") { NuvioPlaybackRemote.seekBy(120_000L) },
        ))

        column.addView(label(
            "For passengers. The car screen covers the video while driving, if that's turned on in Nuvio Auto Setup.",
            13f, MUTED, top = 24,
        ))

        setContentView(ScrollView(this).apply {
            addView(column)
            isFillViewport = true
        })
        column.gravity = Gravity.TOP
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun refresh() {
        status.text = if (CarSession.active) "Connected to the car screen." else
            "Nuvio isn't on the car screen right now. Open it in Android Auto first."
        val playback = NuvioPlaybackRemote.state.value
        nowPlaying.text = if (playback.active) {
            (if (playback.playing) "Playing: " else "Paused: ") +
                listOfNotNull(playback.title, playback.subtitle).joinToString(" · ")
        } else {
            "Nothing playing."
        }
        playPause.text = if (playback.active && playback.playing) "Pause" else "Play"
    }

    private fun search() {
        val query = searchBox.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) return
        ExternalAppCommands.send(ExternalAppCommands.Command.Search(query))
        searchBox.setText("")
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(searchBox.windowToken, 0)
        status.text = "Searching for \"$query\" on the car screen."
    }

    private fun tab(tab: AppScreenTab) {
        ExternalAppCommands.send(ExternalAppCommands.Command.OpenTab(tab))
    }

    private companion object {
        val BG = Color.rgb(13, 13, 13)
        val CARD = Color.rgb(36, 36, 40)
        val MUTED = Color.argb(0xB3, 255, 255, 255)
    }
}
