package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue

/**
 * Keeps only the dialogue of an ASS/SSA subtitle for AutoSync's analysis.
 *
 * Fansub ASS files carry much more than dialogue: karaoke for the opening and ending, signs that
 * translate on-screen text, titles and notes. Another release of the same episode (the embedded
 * track, or another add-on subtitle) often has a different set of those, or none, so matching them
 * cue by cue only adds noise. Dialogue is what every release shares.
 *
 * Styles are classified first: a style is not dialogue when its name says so (sign, song, OP, ...),
 * when it is placed in the middle of the screen, or when most of its events are typeset (positioned,
 * animated, clipped, karaoke or drawings). Within dialogue styles, typeset events and events with an
 * Effect are dropped too. The filter only narrows what AutoSync compares; the subtitle that is shown
 * and retimed keeps every event.
 */
internal object AutoSyncAssDialogueFilter {
    private const val MIN_DIALOGUE_EVENTS = 20
    private const val TYPESET_STYLE_RATIO = 0.5
    private const val MIN_DIALOGUE_DURATION_MS = 300L
    private const val MAX_DIALOGUE_DURATION_MS = 12_000L
    private const val BURST_WINDOW_MS = 1_000L
    private const val BURST_MIN_STARTS = 5

    private val nonDialogueName = Regex(
        """(?:^|[^a-z])(?:signs?|songs?|lyrics?|karaoke|kara|kfx|fx|op|ed|opening|ending|insert|""" +
            """romaji|kanji|title|eyecatch|preview|next|logo|credits?|staff|notes?|tl ?note|""" +
            """typeset|ts|ruby|furigana|screen|text)(?:$|[^a-z])""",
    )
    private val dialogueName = Regex(
        """(?:^|[^a-z])(?:default|main|dialog|dialogue|dial|italics?|flashback|thoughts?|""" +
            """internal|narrat\w*|overlap|top|alt|sub|subs|subtitles?)(?:$|[^a-z])""",
    )

    // Override tags that place, animate, clip or draw text: typesetting, not dialogue.
    private val typesetTag = Regex(
        """\\(?:pos|move|org|i?clip)\s*\(|\\t\s*\(|\\p[1-9]|\\[kK][fo]?\d|\\fr[xyz]?-?\d|\\fa[xy]""",
    )

    internal data class Style(val name: String, val alignment: Int?)

    internal data class Event(
        val startTimeMs: Long,
        val endTimeMs: Long,
        val style: String,
        val name: String,
        val effect: String,
        val rawText: String,
    )

    /**
     * The dialogue events among [events]. Falls back to every event when the classification leaves
     * too few to be the dialogue of a whole episode, so an unusual file is still analysed as before.
     */
    fun dialogueEvents(events: List<Event>, styles: List<Style>): List<Event> {
        if (events.size < MIN_DIALOGUE_EVENTS) return events
        val stylesByName = styles.associateBy { it.name.lowercase() }
        val eventsByStyle = events.groupBy { it.style.lowercase() }
        val dialogueStyles = eventsByStyle
            .filter { (style, styleEvents) ->
                isDialogueStyle(style, stylesByName[style], styleEvents)
            }
            .keys
        val dialogue = events.filter { event ->
            event.style.lowercase() in dialogueStyles && isDialogueEvent(event)
        }
        return if (dialogue.size < MIN_DIALOGUE_EVENTS) events else dialogue
    }

    /**
     * The likely dialogue of an embedded ASS/SSA track known only by its timing (the Matroska Cues
     * index has no styles or text). Karaoke and effects show up as runs of very short events or as
     * many events starting within a moment of each other, and signs or titles as events held far
     * longer than a line of dialogue; those are dropped. [estimatedEndStartsMs] are the starts whose
     * end was estimated rather than indexed, whose duration says nothing about the event. Falls back
     * to [cues] when too little would be left.
     */
    fun indexedDialogueCues(
        cues: List<SubtitleSyncCue>,
        estimatedEndStartsMs: Set<Long>,
    ): List<SubtitleSyncCue> {
        if (cues.size < MIN_DIALOGUE_EVENTS) return cues
        val sorted = cues.sortedBy { it.startTimeMs }
        val starts = LongArray(sorted.size) { sorted[it].startTimeMs }
        val dialogue = sorted.filterIndexed { index, cue ->
            val durationMs = cue.endTimeMs - cue.startTimeMs
            val explicit = cue.startTimeMs !in estimatedEndStartsMs
            if (explicit && durationMs !in MIN_DIALOGUE_DURATION_MS..MAX_DIALOGUE_DURATION_MS) {
                return@filterIndexed false
            }
            startsWithin(starts, index, BURST_WINDOW_MS) < BURST_MIN_STARTS
        }
        return if (dialogue.size < MIN_DIALOGUE_EVENTS) cues else dialogue
    }

    /** How many of the sorted [starts] lie within [radiusMs] of the one at [index], itself included. */
    private fun startsWithin(starts: LongArray, index: Int, radiusMs: Long): Int {
        val center = starts[index]
        var first = index
        while (first > 0 && center - starts[first - 1] <= radiusMs) first--
        var last = index
        while (last < starts.lastIndex && starts[last + 1] - center <= radiusMs) last++
        return last - first + 1
    }

    private fun isDialogueStyle(name: String, style: Style?, events: List<Event>): Boolean {
        val namedDialogue = dialogueName.containsMatchIn(name)
        if (!namedDialogue && nonDialogueName.containsMatchIn(name)) return false
        // Numpad alignment: 4-6 is the middle row, where dialogue is not placed.
        if (!namedDialogue && style?.alignment in 4..6) return false
        val typeset = events.count { typesetTag.containsMatchIn(it.rawText) }
        return typeset < events.size * TYPESET_STYLE_RATIO
    }

    private fun isDialogueEvent(event: Event): Boolean {
        if (event.effect.isNotBlank()) return false
        val actor = event.name.lowercase()
        if (actor.isNotEmpty() && !dialogueName.containsMatchIn(actor) &&
            nonDialogueName.containsMatchIn(actor)
        ) {
            return false
        }
        return !typesetTag.containsMatchIn(event.rawText)
    }
}
