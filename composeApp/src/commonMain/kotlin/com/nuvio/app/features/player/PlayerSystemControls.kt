package com.nuvio.app.features.player

/** Handles playback requests from system controls (PiP, Now Playing). False keeps the local path. */
interface PlayerSystemControlHandler {
    fun handleSetPlaying(playing: Boolean): Boolean
    fun handleSeekTo(positionMs: Long): Boolean
    fun handleSeekBy(offsetMs: Long): Boolean
    /** Device-level pause (e.g. headphones disconnected): pause locally without publishing. */
    fun handleSystemPause(): Boolean
    /** System-level resume after a suspension: resume locally without publishing. */
    fun handleSystemResume(): Boolean
}

/** Optional platform capability of a player instance. */
interface PlayerSystemControlSource {
    val keepsPlaybackInBackground: Boolean
    fun setSystemControlHandler(handler: PlayerSystemControlHandler?)
}
