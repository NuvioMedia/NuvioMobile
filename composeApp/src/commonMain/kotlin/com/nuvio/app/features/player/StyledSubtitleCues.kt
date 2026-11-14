package com.nuvio.app.features.player

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

internal data class StyledSubtitleCues(
    val top: AnnotatedString = AnnotatedString(""),
    val bottom: AnnotatedString = AnnotatedString(""),
) {
    val isEmpty: Boolean get() = top.isEmpty() && bottom.isEmpty()
}

private val assOverrideBlock = Regex("""\{[^}]*\}""")
private val assTopAlignment = Regex("""\\an[789](?!\d)""")
private val assStyleTag = Regex("""\\([ibu])(\d*)(?![a-z])""")

internal fun parseMpvSubtitleText(text: String): StyledSubtitleCues {
    val events = text.split('\n').map(String::trim).filter { it.isNotEmpty() }
    val (top, bottom) = events.partition { event ->
        assOverrideBlock.findAll(event).any { assTopAlignment.containsMatchIn(it.value) }
    }
    return StyledSubtitleCues(top = top.joinEvents(), bottom = bottom.joinEvents())
}

private fun List<String>.joinEvents(): AnnotatedString = buildAnnotatedString {
    this@joinEvents.forEachIndexed { index, event ->
        if (index > 0) append('\n')
        append(event.toAnnotatedEvent())
    }
}

private fun String.toAnnotatedEvent(): AnnotatedString = buildAnnotatedString {
    var italic = false
    var bold = false
    var underline = false
    var cursor = 0

    fun appendPlain(raw: String) {
        val plain = raw.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", "\u00A0")
        if (plain.isEmpty()) return
        if (!italic && !bold && !underline) {
            append(plain)
            return
        }
        withStyle(
            SpanStyle(
                fontStyle = if (italic) FontStyle.Italic else null,
                fontWeight = if (bold) FontWeight.Bold else null,
                textDecoration = if (underline) TextDecoration.Underline else null,
            ),
        ) { append(plain) }
    }

    assOverrideBlock.findAll(this@toAnnotatedEvent).forEach { block ->
        appendPlain(substring(cursor, block.range.first))
        assStyleTag.findAll(block.value).forEach { tag ->
            val enabled = tag.groupValues[2] != "0"
            when (tag.groupValues[1]) {
                "i" -> italic = enabled
                "b" -> bold = enabled
                "u" -> underline = enabled
            }
        }
        cursor = block.range.last + 1
    }
    appendPlain(substring(cursor))
}
