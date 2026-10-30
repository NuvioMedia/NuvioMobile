@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.text.Cue

internal class SubtitleCuePresenter(private val style: () -> SubtitleStyleState) {
    var text by mutableStateOf("")
        private set
    private var cues = emptyList<Cue>()
    private var canStyle = true

    fun present(cues: List<Cue>, canStyle: Boolean = this.canStyle): List<Cue> {
        this.cues = cues
        this.canStyle = canStyle
        return refresh()
    }

    fun refresh(): List<Cue> {
        if (!canStyle || !style().usesStyledOverlay) {
            text = ""
            return cues
        }
        text = cues.mapNotNull { it.text?.toString() }.joinToString("\n")
        return cues.filter { it.text == null }
    }
}
