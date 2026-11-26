package com.nuvio.app.features.audiosync

/** Ensures audio sync persistence and speech-model state are initialized on platforms that support it. */
internal expect fun ensureAudioSyncInitialized()

/** True when live playback-only audio sync ([AudioSamplingMode.OFF]) is supported on this build. */
internal expect fun isPlaybackOnlyAudioSyncSupported(): Boolean
