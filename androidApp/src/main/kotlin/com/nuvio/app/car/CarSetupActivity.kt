package com.nuvio.app.car

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.nuvio.android.R
import com.nuvio.app.NuvioAndroidBootstrap
import com.nuvio.app.features.player.CarPlayerOverrides
import com.nuvio.app.features.profiles.ProfileRepository

/**
 * Nuvio Auto Setup on the phone: install steps, every in-car setting with its own switch, a one-tap Basic mode
 * that turns every extra off, reset, and the car log. Changes apply right away when the car is connected.
 */
class CarSetupActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var column: LinearLayout
    private lateinit var statusLine: TextView
    private lateinit var basicBanner: TextView
    private lateinit var locationNote: TextView
    private lateinit var locationButton: Button
    private lateinit var micNote: TextView
    private lateinit var micButton: Button
    private lateinit var listenerButton: Button
    private lateinit var notificationButton: Button
    private lateinit var logView: TextView

    // ---- Layout helpers -----------------------------------------------------------------------------------------

    private fun dp(n: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), resources.displayMetrics).toInt()

    private fun text(value: String, sp: Float, color: Int = Color.WHITE, top: Int = 4) = TextView(this).apply {
        text = value
        textSize = sp
        setTextColor(color)
        setPadding(0, dp(top), 0, dp(4))
    }

    private fun note(value: String) = text(value, 13.5f, MUTED, top = 0)

    /** A titled card that groups related settings. */
    private fun section(title: String, subtitle: String? = null): LinearLayout {
        column.addView(text(title.uppercase(), 12.5f, ACCENT, top = 26).apply {
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        })
        subtitle?.let { column.addView(note(it)) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(CARD)
            }
        }
        column.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        return card
    }

    /** A switch bound to a [CarPrefs.Flag]; extras are greyed out while Basic mode is on. */
    private fun LinearLayout.flag(flag: CarPrefs.Flag, label: String, description: String? = null, onChange: (Boolean) -> Unit = {}) {
        addView(Switch(this@CarSetupActivity).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
            isChecked = flag.stored(this@CarSetupActivity)
            setPadding(0, dp(8), 0, dp(2))
            tag = if (flag.extra) EXTRA_TAG else null
            setOnCheckedChangeListener { _, on ->
                flag.set(this@CarSetupActivity, on)
                CarLog.d("Setup: ${flag.key} = $on")
                onChange(on)
                applyNow()
            }
        })
        description?.let { addView(note(it)) }
    }

    private fun LinearLayout.choice(label: String?, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        label?.let { addView(text(it, 16f, top = 10)) }
        val group = RadioGroup(this@CarSetupActivity)
        options.forEachIndexed { i, option ->
            group.addView(RadioButton(this@CarSetupActivity).apply {
                id = View.generateViewId()
                text = option
                setTextColor(Color.WHITE)
                isChecked = i == selected
                setOnClickListener {
                    onPick(i)
                    applyNow()
                }
            })
        }
        addView(group)
    }

    private fun LinearLayout.button(label: String, onClick: () -> Unit): Button {
        val b = Button(this@CarSetupActivity).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(CARD_HIGH)
            }
            setOnClickListener { onClick() }
        }
        addView(b, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        return b
    }

    /** Setting changes take effect in the car right away. */
    private fun applyNow() {
        CarSession.refresh(this)
        updateBasicMode()
    }

    // ---- Screen ---------------------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { NuvioAndroidBootstrap.initialize(this) }
        window.decorView.setBackgroundColor(BG)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(44), dp(18), dp(40))
        }
        column.addView(text("Nuvio Auto", 30f, top = 0).apply { typeface = Typeface.DEFAULT_BOLD })
        statusLine = note("")
        column.addView(statusLine)
        basicBanner = text("", 14f, Color.rgb(250, 204, 21), top = 8)
        column.addView(basicBanner)

        buildGetStarted()
        buildCarMode()
        buildStartup()
        buildPlayback()
        buildData()
        buildCarScreen()
        buildSafety()
        buildVoiceAndPhone()
        buildExperimental()
        buildTroubleshooting()

        setContentView(ScrollView(this).apply {
            addView(column)
            isFillViewport = true
        })
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
        main.postDelayed(object : Runnable {
            override fun run() {
                updateStatus()
                main.postDelayed(this, 2_000)
            }
        }, 2_000)
    }

    override fun onPause() {
        main.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun refreshAll() {
        updateStatus()
        updateBasicMode()
        updateLocation()
        updateMic()
        updateListenerAccess()
        updateNotificationPermission()
        updateLog()
    }

    private fun updateStatus() {
        val mode = CarMode.current(this)
        statusLine.text = (if (CarSession.active) "● Connected to the car" else "○ Not connected to a car") +
            "  ·  " + (if (mode == CarMode.FULL_SCREEN_APP) "Full-screen app mode" else "Navigation-style mode")
    }

    private fun updateBasicMode() {
        val basic = CarPrefs.basicMode(this)
        basicBanner.visibility = if (basic) View.VISIBLE else View.GONE
        basicBanner.text = "Basic mode is on: every in-car extra is off. Turn it off under Troubleshooting."
        fun walk(v: View) {
            if (v.tag == EXTRA_TAG) v.alpha = if (basic) 0.4f else 1f
            if (v is LinearLayout) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(column)
    }

    // ---- Sections -------------------------------------------------------------------------------------------------

    private fun buildGetStarted() {
        val card = section("Get started")
        card.addView(note(
            "1. Install this APK the same way each time (your installer tool or KingInstaller).\n" +
                "2. Android Auto settings: tap Version 10 times, then ⋮ > Developer settings > Unknown sources.\n" +
                "3. Restart the phone or force-stop Android Auto so it picks up changes.\n" +
                "4. Android Auto settings > Customize launcher: make sure Nuvio is ticked.\n" +
                "5. Sign in to Nuvio on the phone once. The car uses the same app and account.",
        ))
        card.button("Open Nuvio on the phone") { packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity) }
        card.button("Open the phone remote") { startActivity(Intent(this, CarRemoteActivity::class.java)) }
    }

    private fun buildCarMode() {
        val card = section("Car mode")
        val modeNote = note(CarMode.current(this).description)
        card.choice(null, CarMode.entries.map { it.label }, CarMode.current(this).ordinal) { i ->
            val mode = CarMode.entries[i]
            CarMode.set(this, mode)
            modeNote.text = mode.description + "\n\nAfter switching: unplug, force-stop Android Auto, reconnect."
            updateStatus()
        }
        card.addView(modeNote)
    }

    private fun buildStartup() {
        val card = section("When the car connects")
        val live = runCatching { ProfileRepository.state.value.profiles }.getOrDefault(emptyList())
        if (live.isNotEmpty()) CarPrefs.cacheProfiles(this, live.map { it.profileIndex to it.name })
        val pinned = live.filter { it.pinEnabled }.map { it.profileIndex }.toSet()
        val profiles = CarPrefs.cachedProfiles(this)
        if (profiles.isEmpty()) {
            card.addView(note("Car profile: open Nuvio on the phone once (signed in) so its profiles show up here."))
        } else {
            val options = listOf("Ask every time (\"Who's watching\")") +
                profiles.map { (index, name) -> if (index in pinned) "$name (PIN: can't open automatically)" else name }
            val current = CarPrefs.storedCarProfile(this)
            val selected = profiles.indexOfFirst { it.first == current }.let { if (it < 0) 0 else it + 1 }
            card.choice("Open this profile in the car", options, selected) { i ->
                CarPrefs.setCarProfile(this, if (i == 0) null else profiles[i - 1].first)
                CarLog.d("Setup: car profile = ${CarPrefs.storedCarProfile(this)}")
            }
        }
        card.flag(CarPrefs.CAR_LOCK, "Car lock (kids mode)", "Keeps the car on the car profile: no switching profiles and no Settings on the car screen. The phone remote can only control playback.")
        card.flag(CarPrefs.RESUME_PROMPT, "Offer \"Continue watching?\"", "Once per drive, after the home screen loads.")
    }

    private fun buildPlayback() {
        val card = section("Playback in the car", "Layered over your profile's player settings while Nuvio is on the car screen; nothing saved on the phone changes.")
        card.flag(CarPrefs.FILL_VIDEO, "Fill the car screen with video", "Crops the edges instead of showing black bars.")
        card.flag(CarPrefs.BIG_BUFFER, "Bigger buffer for dead zones", "Loads up to 2 minutes ahead so tunnels and weak signal don't pause the video.")
        card.flag(CarPrefs.AUTO_SKIP_INTROS, "Auto-skip intros and recaps")
        card.flag(CarPrefs.AUTO_NEXT_EPISODE, "Play the next episode automatically", "Also looks up its sources early so it starts faster.")
        card.flag(CarPrefs.PAUSE_ON_DISCONNECT, "Pause when the car disconnects", "Your spot is saved to Continue Watching.")
        card.choice("Subtitle size", CarPrefs.SUBTITLE_LABELS.toList(), CarPrefs.subtitleScaleIndex(this)) {
            CarPrefs.setSubtitleScaleIndex(this, it)
        }
        card.flag(CarPrefs.SUBTITLE_BACKGROUND, "Dark box behind subtitles", "Easier to read in sunlight.")
        val steering = CarPrefs.STEERING_LABELS.keys.toList()
        card.choice("Steering wheel next / previous", CarPrefs.STEERING_LABELS.values.toList(), steering.indexOf(CarPrefs.steering(this))) {
            CarPrefs.setSteering(this, steering[it])
        }
    }

    private fun buildData() {
        val card = section("Data saver", "720p-or-lower streams first, overriding the profile's auto-play choice. Bigger streams stay listed below.")
        card.choice(null, CarPrefs.DataSaver.entries.map { it.label }, CarPrefs.storedDataSaver(this).ordinal) {
            CarPrefs.setDataSaver(this, CarPrefs.DataSaver.entries[it])
        }
        card.addView(note("\"Always\" also covers a car Wi-Fi hotspot, which uses mobile data too."))
    }

    private fun buildCarScreen() {
        val card = section("Car screen")
        card.choice("Text and button size", CarPrefs.SIZE_LABELS.toList(), CarPrefs.sizeIndex(this)) { CarPrefs.setSizeIndex(this, it) }
        card.addView(note("Size changes apply the next time Nuvio opens in the car (or use the size button in the car)."))
        card.flag(CarPrefs.BAR_AT_BOTTOM, "Button bar at the bottom", "Full-screen app mode. Keeps Android Auto's direction pop-ups from covering it.")
        card.choice("Hide the button bar after", CarPrefs.BAR_TIMEOUT_LABELS.toList(), CarPrefs.barTimeoutIndex(this)) {
            CarPrefs.setBarTimeoutIndex(this, it)
        }
        card.flag(CarPrefs.HIDE_STATUS_OVERLAY, "Hide Android Auto's status icons", "Full-screen app mode: signal, battery and clock over Nuvio.")
        card.flag(CarPrefs.AVOID_CAR_BUTTONS, "Keep Nuvio clear of Android Auto's buttons", "Navigation-style mode. Leaves a border so Android Auto's corner buttons never cover Nuvio.")
    }

    private fun buildSafety() {
        val card = section("Safety")
        card.flag(CarPrefs.HIDE_WHILE_DRIVING, "Hide video while driving", "Audio keeps playing. Uses the phone's GPS speed (about 10 mph). Stays on even in Basic mode.") { updateLocation() }
        locationNote = note("")
        card.addView(locationNote)
        locationButton = card.button("Allow location") { requestLocation() }
    }

    private fun buildVoiceAndPhone() {
        val card = section("Voice and phone")
        micNote = note("")
        card.addView(micNote)
        micButton = card.button("Allow microphone") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC) }
        card.flag(CarPrefs.REMOTE_NOTIFICATION, "Remote notification while in the car") { updateNotificationPermission() }
        notificationButton = card.button("Allow notifications") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
        card.button("Add the remote to Quick Settings") { addQuickTile() }
        card.addView(note("Or edit your Quick Settings and drag \"Nuvio remote\" in."))
    }

    private fun buildExperimental() {
        val card = section("Experimental")
        card.flag(CarPrefs.CLUSTER_MIRROR, "Copy Maps directions to the cockpit", "Navigation-style mode only. Full-screen app mode keeps Maps in the cockpit without this. Turn off if Maps stops navigating.") {
            updateListenerAccess()
        }
        listenerButton = card.button("Allow notification access") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    private fun buildTroubleshooting() {
        val card = section("Troubleshooting")
        card.addView(Switch(this).apply {
            text = "Basic mode (turn every extra off)"
            textSize = 16f
            setTextColor(Color.WHITE)
            isChecked = CarPrefs.basicMode(this@CarSetupActivity)
            setPadding(0, dp(8), 0, dp(2))
            setOnCheckedChangeListener { _, on ->
                CarPrefs.setBasicMode(this@CarSetupActivity, on)
                CarLog.d("Setup: basic mode = $on")
                applyNow()
            }
        })
        card.addView(note("Just Nuvio on the car screen: no profile, overrides, data saver, prompts or extra buttons. Your individual switches are kept for when you turn it off. The driving cover stays on."))
        card.button("Reset all Nuvio Auto settings") {
            AlertDialog.Builder(this)
                .setTitle("Reset Nuvio Auto settings?")
                .setMessage("Every switch goes back to its default. Car mode and Nuvio's own settings aren't touched.")
                .setPositiveButton("Reset") { _, _ ->
                    CarPrefs.reset(this)
                    CarLog.d("Setup: settings reset")
                    CarSession.refresh(this)
                    recreate()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        column.addView(text("CAR LOG", 12.5f, ACCENT, top = 26).apply { typeface = Typeface.DEFAULT_BOLD })
        column.addView(note("What happened the last times Nuvio opened in the car. Share it if something fails."))
        val logCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(logCard)
        logCard.button("Share log") {
            val body = CarLog.deviceLine() + "\n" + "App: ${packageName} " + versionName() + "\n" + CarLog.read(this)
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body),
                "Share Nuvio Auto log",
            ))
        }
        logCard.button("Clear log") {
            CarLog.clear(this)
            updateLog()
        }
        logView = text("", 11.5f, MUTED, top = 10).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
        }
        column.addView(logView)
        column.addView(note("\n${packageName} · ${versionName()}"))
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull().orEmpty()

    // ---- Permissions and status -----------------------------------------------------------------------------------

    private fun hasLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun updateLocation() {
        val wanted = CarPrefs.HIDE_WHILE_DRIVING.stored(this)
        locationButton.visibility = if (wanted && !hasLocation()) View.VISIBLE else View.GONE
        locationNote.text = when {
            !wanted -> "Off: video plays while the car moves. Only for a passenger watching."
            hasLocation() -> "On: the video is covered above about 10 mph."
            else -> "Needs location access to know when the car is moving. Until then, video is never hidden."
        }
    }

    private fun requestLocation() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) || !getPreferences(MODE_PRIVATE).getBoolean("askedLocation", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("askedLocation", true).apply()
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
        } else {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun updateMic() {
        val ok = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micButton.visibility = if (ok) View.GONE else View.VISIBLE
        micNote.text = if (ok) "Car voice search is ready (the mic button on the car screen uses the car's microphone)."
        else "Car voice search needs the microphone permission."
    }

    private fun updateNotificationPermission() {
        val needs = Build.VERSION.SDK_INT >= 33 && CarPrefs.REMOTE_NOTIFICATION.stored(this) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        notificationButton.visibility = if (needs) View.VISIBLE else View.GONE
    }

    private fun updateListenerAccess() {
        val granted = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        listenerButton.visibility = if (CarPrefs.CLUSTER_MIRROR.stored(this) && !granted) View.VISIBLE else View.GONE
    }

    private fun updateLog() {
        val log = CarLog.read(this).trim()
        logView.text = if (log.isEmpty()) "(empty)" else log.lines().takeLast(80).joinToString("\n")
    }

    private fun addQuickTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSystemService(StatusBarManager::class.java)?.requestAddTileService(
                ComponentName(this, RemoteTileService::class.java),
                "Nuvio remote",
                Icon.createWithResource(this, R.drawable.car_ic_play),
                mainExecutor,
            ) { }
        } else {
            Toast.makeText(this, "Edit Quick Settings and drag \"Nuvio remote\" in", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshAll()
        CarSession.refresh(this)
    }

    private companion object {
        val BG = Color.rgb(9, 9, 15)
        val CARD = Color.rgb(22, 22, 31)
        val CARD_HIGH = Color.rgb(36, 36, 48)
        val ACCENT = Color.rgb(167, 139, 250)
        val MUTED = Color.argb(0xB3, 255, 255, 255)
        const val EXTRA_TAG = "extra"
        const val REQ_LOCATION = 7
        const val REQ_MIC = 9
        const val REQ_NOTIFY = 8
    }
}
