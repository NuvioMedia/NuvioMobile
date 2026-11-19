package com.nuvio.app.car

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Phone-side Nuvio Auto setup: install steps, car screen size, and the driving video lock. */
class CarSetupActivity : Activity() {

    private lateinit var locationNote: TextView
    private lateinit var locationButton: Button
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(BG)
        fun dp(n: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), resources.displayMetrics).toInt()
        fun text(value: String, sp: Float, color: Int = Color.WHITE, top: Int = 8) = TextView(this).apply {
            text = value
            textSize = sp
            setTextColor(color)
            setPadding(0, dp(top), 0, dp(4))
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(32))
        }
        column.addView(text("Nuvio Auto", 28f, top = 0))
        column.addView(text("The full Nuvio app on your Android Auto screen.", 15f, MUTED))

        column.addView(text("Set up Android Auto", 19f, top = 24))
        column.addView(text(
            "1. In Android Auto settings, tap Version 10 times, then ⋮ > Developer settings > turn on Unknown sources.\n" +
                "2. Restart the phone (or force-stop Android Auto) so it picks up new apps.\n" +
                "3. Android Auto settings > Customize launcher: make sure Nuvio is ticked.\n" +
                "4. Sign in and set up your addons in Nuvio on the phone once. The car uses the same app and account.\n" +
                "If Nuvio still doesn't show up, reinstall this APK with KingInstaller (\"Install as King\").",
            15f, MUTED,
        ))
        column.addView(Button(this).apply {
            text = "Open Nuvio on the phone"
            setOnClickListener {
                packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
            }
        })

        column.addView(text("In the car", 19f, top = 24))
        column.addView(text(
            "Tap to select, drag to scroll. Drags start from the middle of the screen, so to scroll a row of posters, " +
                "first scroll it to the middle. Buttons: Back, Keyboard (tap Nuvio's search box first, then type with " +
                "the car keyboard), Home, and Screen size.",
            15f, MUTED,
        ))

        column.addView(text("Car screen size", 19f, top = 24))
        val sizes = RadioGroup(this)
        CarPrefs.SIZE_LABELS.forEachIndexed { i, label ->
            sizes.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                setTextColor(Color.WHITE)
                isChecked = i == CarPrefs.sizeIndex(this@CarSetupActivity)
                setOnClickListener { CarPrefs.setSizeIndex(this@CarSetupActivity, i) }
            })
        }
        column.addView(sizes)

        column.addView(text("Safety", 19f, top = 24))
        column.addView(Switch(this).apply {
            text = "Hide video while driving (audio keeps playing)"
            setTextColor(Color.WHITE)
            isChecked = CarPrefs.hideVideoWhileDriving(this@CarSetupActivity)
            setOnCheckedChangeListener { _, on ->
                CarPrefs.setHideVideoWhileDriving(this@CarSetupActivity, on)
                updateLocation()
            }
        })
        locationNote = text("", 14f, MUTED)
        column.addView(locationNote)
        locationButton = Button(this).apply {
            text = "Allow location"
            setOnClickListener { requestLocation() }
        }
        column.addView(locationButton)

        column.addView(text("Layout", 19f, top = 24))
        column.addView(Switch(this).apply {
            text = "Keep Nuvio clear of Android Auto's buttons"
            setTextColor(Color.WHITE)
            isChecked = CarPrefs.avoidCarButtons(this@CarSetupActivity)
            setOnCheckedChangeListener { _, on -> CarPrefs.setAvoidCarButtons(this@CarSetupActivity, on) }
        })
        column.addView(text("Changes apply the next time Nuvio opens in the car.", 14f, MUTED))

        column.addView(text("Car log", 19f, top = 24))
        column.addView(text("What happened the last times Nuvio opened in the car. Share it if something fails.", 14f, MUTED))
        logView = text("", 12f, MUTED).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@CarSetupActivity).apply {
                text = "Share log"
                setOnClickListener {
                    val body = CarLog.deviceLine() + "\n" + CarLog.read(this@CarSetupActivity)
                    startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body),
                        "Share Nuvio Auto log",
                    ))
                }
            })
            addView(Button(this@CarSetupActivity).apply {
                text = "Clear"
                setOnClickListener {
                    CarLog.clear(this@CarSetupActivity)
                    updateLog()
                }
            })
        })
        column.addView(logView)

        setContentView(ScrollView(this).apply {
            addView(column)
            isFillViewport = true
        })
        column.gravity = Gravity.TOP
    }

    override fun onResume() {
        super.onResume()
        updateLocation()
        updateLog()
    }

    private fun updateLog() {
        val log = CarLog.read(this).trim()
        logView.text = if (log.isEmpty()) "(empty)" else log.lines().takeLast(60).joinToString("\n")
    }

    private fun hasLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun updateLocation() {
        val wanted = CarPrefs.hideVideoWhileDriving(this)
        locationButton.visibility = if (wanted && !hasLocation()) View.VISIBLE else View.GONE
        locationNote.text = when {
            !wanted -> "Off: video plays while the car moves. Only use this with a passenger watching."
            hasLocation() -> "On: uses the phone's GPS speed. Above about 10 mph the video is covered."
            else -> "Needs location access to know when the car is moving. Until then, video is never hidden."
        }
    }

    private fun requestLocation() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) || !hasAskedBefore()) {
            markAsked()
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 7,
            )
        } else {
            // Denied for good: the system won't show the prompt again, so open the app's settings.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun hasAskedBefore() = getPreferences(MODE_PRIVATE).getBoolean("askedLocation", false)

    private fun markAsked() = getPreferences(MODE_PRIVATE).edit().putBoolean("askedLocation", true).apply()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateLocation()
    }

    private companion object {
        val BG = Color.rgb(13, 13, 13)
        val MUTED = Color.argb(0xB3, 255, 255, 255)
    }
}
