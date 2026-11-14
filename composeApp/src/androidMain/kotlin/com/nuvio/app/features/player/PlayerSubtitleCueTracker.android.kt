package com.nuvio.app.features.player

import androidx.media3.common.text.Cue

internal class SubtitleCueTracker {
    @Volatile
    var version: Long = 0L
        private set

    @Volatile
    var visible: Boolean = false
        private set

    @Volatile
    var onChanged: () -> Unit = {}

    private var lastKey: String? = null

    fun update(cues: List<Cue>) {
        val key = cues.joinToString("\u0000") { cue ->
            cue.bitmap?.let { "\u0001${System.identityHashCode(it)}" } ?: cue.text?.toString().orEmpty()
        }
        if (key == lastKey) return
        lastKey = key
        visible = cues.any { !it.text.isNullOrBlank() || it.bitmap != null }
        version += 1
        onChanged()
    }

    fun updateText(text: String?) {
        val key = text.orEmpty()
        if (key == lastKey) return
        lastKey = key
        visible = key.isNotBlank()
        version += 1
        onChanged()
    }
}

internal fun PlayerPlaybackSnapshot.withSubtitleCues(tracker: SubtitleCueTracker): PlayerPlaybackSnapshot =
    copy(subtitleCueVersion = tracker.version, subtitleCueVisible = tracker.visible)
