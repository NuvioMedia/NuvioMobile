package com.nuvio.app.features.audiosync

import com.nuvio.app.features.player.PlayerSettingsStorage

internal actual fun ensureAudioSyncInitialized() {
    PlayerSettingsStorage.applicationContext()?.let(AudioSyncFallback::initialize)
}

internal actual fun isPlaybackOnlyAudioSyncSupported(): Boolean = true
