package com.nuvio.app.features.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Player settings that Nuvio Auto overrides while Nuvio is on the car screen, without touching the
 * profile's saved settings. Null means "use the saved setting".
 */
object CarPlayerOverrides {
    private val _resizeMode = MutableStateFlow<PlayerResizeMode?>(null)

    /** Video framing in the car, e.g. Zoom to fill the wide car screen with no black bars. */
    val resizeMode: StateFlow<PlayerResizeMode?> = _resizeMode.asStateFlow()

    fun setResizeMode(mode: PlayerResizeMode?) {
        _resizeMode.value = mode
    }
}
