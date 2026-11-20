package com.nuvio.app.features.streams

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An optional resolution cap, set by Nuvio Auto while the car is connected and the phone is on mobile data.
 * Streams at or under the cap move to the top of each list (and are what auto-play picks); bigger ones stay
 * available below. Order is otherwise kept, so an add-on's own quality sorting still applies.
 */
object StreamQualityCap {
    private val _maxHeight = MutableStateFlow<Int?>(null)

    /** Highest preferred video height (e.g. 720), or null for no cap. */
    val maxHeight: StateFlow<Int?> = _maxHeight.asStateFlow()

    fun set(maxHeight: Int?) {
        _maxHeight.value = maxHeight
    }

    private val patterns = listOf(
        Regex("""(?i)(?<![a-z0-9])(2160p|4k|uhd)(?![a-z0-9])""") to 2160,
        Regex("""(?i)(?<![a-z0-9])1440p(?![a-z0-9])""") to 1440,
        Regex("""(?i)(?<![a-z0-9])(1080p|1080i|fhd)(?![a-z0-9])""") to 1080,
        Regex("""(?i)(?<![a-z0-9])720p(?![a-z0-9])""") to 720,
        Regex("""(?i)(?<![a-z0-9])(576p|540p|480p|360p|sd)(?![a-z0-9])""") to 480,
    )

    /** Video height named in the stream's labels or file name, or null when none is given. */
    fun heightOf(stream: StreamItem): Int? {
        val text = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints.filename)
            .joinToString(" ")
        if (text.isBlank()) return null
        return patterns.firstOrNull { (regex, _) -> regex.containsMatchIn(text) }?.second
    }
}

/** Streams within [maxHeight] first, then ones with no stated resolution, then bigger ones. */
fun List<StreamItem>.preferringMaxHeight(maxHeight: Int?): List<StreamItem> {
    if (maxHeight == null || size < 2) return this
    val within = ArrayList<StreamItem>(size)
    val unknown = ArrayList<StreamItem>()
    val above = ArrayList<StreamItem>()
    for (stream in this) {
        val height = StreamQualityCap.heightOf(stream)
        when {
            height == null -> unknown += stream
            height <= maxHeight -> within += stream
            else -> above += stream
        }
    }
    if (within.isEmpty()) return this
    return within + unknown + above
}

fun StreamsUiState.preferringMaxHeight(maxHeight: Int?): StreamsUiState =
    if (maxHeight == null) this
    else copy(groups = groups.map { group -> group.copy(streams = group.streams.preferringMaxHeight(maxHeight)) })
