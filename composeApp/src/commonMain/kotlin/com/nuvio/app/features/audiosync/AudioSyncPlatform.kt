package com.nuvio.app.features.audiosync

/** Ensures audio sync persistence and speech-model state are initialized on platforms that support it. */
internal expect fun ensureAudioSyncInitialized()
