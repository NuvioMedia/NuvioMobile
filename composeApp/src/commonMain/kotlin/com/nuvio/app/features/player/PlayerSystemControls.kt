package com.nuvio.app.features.player

/** Handles playback requests from system controls (PiP, Now Playing). False keeps the local path. */
interface PlayerSystemControlHandler {
    fun handleSetPlaying(playing: Boolean): Boolean
    fun handleSeekTo(positionMs: Long): Boolean
    fun handleSeekBy(offsetMs: Long): Boolean
}

/** Optional platform capability of a player instance. */
interface PlayerSystemControlSource {
    val keepsPlaybackInBackground: Boolean
    fun setSystemControlHandler(handler: PlayerSystemControlHandler?)
}
