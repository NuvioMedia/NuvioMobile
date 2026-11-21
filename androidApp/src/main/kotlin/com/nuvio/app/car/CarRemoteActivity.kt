package com.nuvio.app.car

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.nuvio.app.AppScreenTab
import com.nuvio.app.NuvioAndroidBootstrap
import com.nuvio.app.core.external.ExternalAppCommands
import com.nuvio.app.remote.NuvioRemoteActions
import com.nuvio.app.remote.NuvioRemoteView

/**
 * Phone remote for Nuvio on the car screen, for a passenger: search by typing or voice, jump between
 * Home, Search and Library, and see and control what's playing. Opened from the "Nuvio is on your car
 * screen" notification or Nuvio Auto Setup. The UI itself is [NuvioRemoteView] (Compose, in composeApp).
 */
class CarRemoteActivity : ComponentActivity() {

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (text.isNotEmpty()) {
            NuvioRemoteView.showHeard(text)
            sendSearch(text)
        }
    }

    private val actions = object : NuvioRemoteActions {
        override fun isConnected(): Boolean = CarSession.active

        override fun search(query: String) = sendSearch(query)

        override fun voiceSearch() {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a movie or show")
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            try {
                voice.launch(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this@CarRemoteActivity, "No voice search app on this phone", Toast.LENGTH_SHORT).show()
            }
        }

        override fun openTab(tab: AppScreenTab) {
            if (locked()) return
            ExternalAppCommands.send(ExternalAppCommands.Command.OpenTab(tab))
        }

        override fun back() {
            CarSession.host?.back()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        NuvioAndroidBootstrap.initialize(this)
        setContentView(NuvioRemoteView.create(this, actions))
    }

    /** Car lock: the remote only controls playback. */
    private fun locked(): Boolean {
        val on = ExternalAppCommands.carLocked.value
        if (on) Toast.makeText(this, "Car lock is on: playback controls only", Toast.LENGTH_SHORT).show()
        return on
    }

    private fun sendSearch(query: String) {
        if (locked()) return
        if (!CarSession.active) {
            Toast.makeText(this, "Open Nuvio on the car screen first", Toast.LENGTH_SHORT).show()
            return
        }
        ExternalAppCommands.send(ExternalAppCommands.Command.Search(query))
        Toast.makeText(this, "Searching \"$query\" on the car screen", Toast.LENGTH_SHORT).show()
    }
}
