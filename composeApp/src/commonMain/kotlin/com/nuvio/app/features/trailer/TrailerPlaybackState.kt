package com.nuvio.app.features.trailer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerPlaybackSnapshot

@Stable
internal class TrailerPlaybackState {
    var snapshot by mutableStateOf(PlayerPlaybackSnapshot())
    var playWhenReady by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
    var scrubPositionMs by mutableStateOf<Long?>(null)
    var controller: PlayerEngineController? = null
    var presentationId by mutableIntStateOf(0)
        private set

    fun changePresentation() {
        controller?.pause()
        controller = null
        scrubPositionMs = null
        snapshot = snapshot.copy(isLoading = true)
        presentationId += 1
    }

    fun togglePlayback() {
        if (snapshot.isEnded) {
            seekTo(0L)
            playWhenReady = true
            controller?.play()
        } else {
            playWhenReady = if (snapshot.isLoading) !playWhenReady else !snapshot.isPlaying
            if (playWhenReady) controller?.play() else controller?.pause()
        }
    }

    fun seekTo(positionMs: Long) {
        val position = positionMs.coerceIn(0L, snapshot.durationMs.coerceAtLeast(0L))
        controller?.seekTo(position)
        snapshot = snapshot.copy(positionMs = position, isEnded = false)
        scrubPositionMs = null
    }
}
