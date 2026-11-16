package com.nuvio.app.features.player

import android.content.Context
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.audio.AudioFocusRequestCompat
import androidx.media3.common.audio.AudioManagerCompat
import androidx.media3.common.util.UnstableApi

internal val playbackAudioAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
    .build()

/**
 * Requests audio focus when playback starts so other apps pause. A denied request (e.g. while a
 * call holds focus) doesn't stop playback. Pauses on focus loss and resumes after a transient loss
 * (e.g. an incoming call) if it was playing. Ducking is left to the system.
 */
@OptIn(UnstableApi::class)
internal class PlayerAudioFocus(
    context: Context,
    private val playWhenReady: () -> Boolean,
    private val pause: () -> Unit,
    private val resume: () -> Unit,
) {
    private val audioManager = AudioManagerCompat.getAudioManager(context)
    private var hasFocus = false
    private var resumeOnGain = false

    private val request = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
        .setAudioAttributes(playbackAudioAttributes)
        .setOnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    hasFocus = true
                    if (resumeOnGain) {
                        resumeOnGain = false
                        resume()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    abandon()
                    pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    hasFocus = false
                    resumeOnGain = resumeOnGain || playWhenReady()
                    pause()
                }
            }
        }
        .build()

    fun request() {
        resumeOnGain = false
        if (hasFocus) return
        hasFocus = AudioManagerCompat.requestAudioFocus(audioManager, request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandon() {
        hasFocus = false
        resumeOnGain = false
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, request)
    }
}
