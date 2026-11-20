package com.nuvio.app.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.media.CarAudioRecord
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.nuvio.android.R

/**
 * Voice search from the car: records with the car's microphone through Android Auto (CarAudioRecord) and
 * feeds that audio to the phone's speech recognizer. The phone mic isn't used, so it works with the phone
 * in a pocket. The recognized text goes to Nuvio's Search tab like the keyboard search.
 */
internal class CarVoiceScreen(
    ctx: CarContext,
    private val onResult: (String) -> Unit,
) : Screen(ctx) {

    private enum class Phase { LISTENING, WORKING, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private var phase = Phase.LISTENING
    private var message = "Say a movie or show"
    private var recognizer: SpeechRecognizer? = null
    private var audio: CarAudioRecord? = null
    private var pipe: Array<ParcelFileDescriptor>? = null
    private var triedOnDevice = false

    @Volatile
    private var streaming = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (phase == Phase.LISTENING && recognizer == null) start(onDevice = false)
            }

            override fun onDestroy(owner: LifecycleOwner) = stopAll()
        })
    }

    override fun onGetTemplate(): Template {
        val mic = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.car_ic_mic)).build()
        val builder = MessageTemplate.Builder(message)
            .setTitle(
                when (phase) {
                    Phase.LISTENING -> "Listening…"
                    Phase.WORKING -> "Searching…"
                    Phase.FAILED -> "Voice search"
                },
            )
            .setIcon(mic)
            .setHeaderAction(Action.BACK)
        if (phase == Phase.FAILED) {
            builder.addAction(
                Action.Builder().setTitle("Try again").setFlags(Action.FLAG_PRIMARY).setOnClickListener {
                    phase = Phase.LISTENING
                    message = "Say a movie or show"
                    triedOnDevice = false
                    invalidate()
                    start(onDevice = false)
                }.build(),
            )
        }
        builder.addAction(Action.Builder().setTitle("Cancel").setOnClickListener { screenManager.pop() }.build())
        return builder.build()
    }

    @SuppressLint("MissingPermission")
    private fun start(onDevice: Boolean) {
        stopAll()
        if (carContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Allow the microphone in Nuvio Auto Setup on your phone, then try again.")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            fail("Car voice search needs Android 13 or newer. Use the phone remote's mic instead.")
            return
        }
        val available = if (onDevice) {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(carContext)
        } else {
            SpeechRecognizer.isRecognitionAvailable(carContext)
        }
        if (!available) {
            if (!onDevice) start(onDevice = true) else fail("No speech recognizer on this phone.")
            return
        }
        try {
            val p = ParcelFileDescriptor.createPipe()
            pipe = p
            val rec = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(carContext)
            else SpeechRecognizer.createSpeechRecognizer(carContext)
            recognizer = rec
            rec.setRecognitionListener(listener)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, p[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE)
            rec.startListening(intent)

            val record = CarAudioRecord.create(carContext)
            audio = record
            record.startRecording()
            streaming = true
            Thread({ pump(record, p[1]) }, "NuvioCarVoice").start()
            CarLog.d("Car voice search started (on-device recognizer: $onDevice)")
        } catch (e: Exception) {
            CarLog.d("Car voice search couldn't start", e)
            if (!onDevice && !triedOnDevice) {
                triedOnDevice = true
                start(onDevice = true)
            } else {
                fail("Voice search isn't available here. Use the keyboard or the phone remote's mic.")
            }
        }
    }

    /** Copies car-microphone audio into the recognizer's pipe for up to [MAX_LISTEN_MS]. */
    private fun pump(record: CarAudioRecord, sink: ParcelFileDescriptor) {
        val buffer = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
        val end = SystemClock.elapsedRealtime() + MAX_LISTEN_MS
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(sink).use { out ->
                while (streaming && SystemClock.elapsedRealtime() < end) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                }
            }
        } catch (e: Exception) {
            CarLog.d("Car microphone stream ended", e)
        } finally {
            main.post { stopAudio() }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onEndOfSpeech() {
            stopAudio()
            phase = Phase.WORKING
            message = "One moment"
            invalidate()
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            stopAll()
            if (text.isEmpty()) {
                fail("Didn't catch that. Try again, or use the keyboard.")
                return
            }
            CarLog.d("Car voice search heard \"$text\"")
            screenManager.pop()
            onResult(text)
        }

        override fun onError(error: Int) {
            CarLog.d("Car voice search error $error")
            stopAll()
            // Some recognizers can't read an audio stream: retry once with the on-device one.
            if (!triedOnDevice && error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                triedOnDevice = true
                start(onDevice = true)
                return
            }
            fail(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "Didn't catch that. Try again, or use the keyboard."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "No connection for voice search right now."
                    else -> "Voice search didn't work (error $error). Use the keyboard or the phone remote's mic."
                },
            )
        }
    }

    private fun fail(text: String) {
        phase = Phase.FAILED
        message = text
        invalidate()
    }

    private fun stopAudio() {
        streaming = false
        audio?.let { runCatching { it.stopRecording() } }
        audio = null
    }

    private fun stopAll() {
        stopAudio()
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        recognizer = null
        pipe?.forEach { runCatching { it.close() } }
        pipe = null
    }

    private companion object {
        const val MAX_LISTEN_MS = 8_000L
    }
}
