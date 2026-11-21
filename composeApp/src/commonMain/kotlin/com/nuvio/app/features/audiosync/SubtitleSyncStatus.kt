package com.nuvio.app.features.audiosync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** State of the on-device speech recognition model used by audio subtitle sync. */
data class SpeechModelState(
    val supported: Boolean = false,
    val sizeMb: Int = 0,
    val downloaded: Boolean = false,
    val downloading: Boolean = false,
    /** 0..1 while downloading. */
    val progress: Float = 0f,
    val error: String? = null,
)

/** Download state and actions for the optional on-device English speech model. */
internal object SubtitleSyncStatus {
    private val speechModelState = MutableStateFlow(SpeechModelState())
    val speechModel: StateFlow<SpeechModelState> = speechModelState.asStateFlow()
    var modelActions: SpeechModelActions? = null

    fun publishSpeechModel(state: SpeechModelState) {
        speechModelState.value = state
    }
}

internal interface SpeechModelActions {
    fun download()
    fun delete()
}
