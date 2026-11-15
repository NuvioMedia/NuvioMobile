package com.nuvio.app.features.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

internal enum class SubtitleVerticalPosition { TOP, MIDDLE, BOTTOM }

internal enum class SubtitleHorizontalPosition { START, CENTER, END }

internal data class StyledSubtitleBlock(
    val text: AnnotatedString,
    val vertical: SubtitleVerticalPosition = SubtitleVerticalPosition.BOTTOM,
    val horizontal: SubtitleHorizontalPosition = SubtitleHorizontalPosition.CENTER
)

internal data class StyledSubtitleCues(val blocks: List<StyledSubtitleBlock> = emptyList()) {
    val isEmpty: Boolean get() = blocks.all { it.text.isEmpty() }
}

internal fun styledSubtitleCues(blocks: List<StyledSubtitleBlock>): StyledSubtitleCues =
    StyledSubtitleCues(
        blocks
            .filter { it.text.isNotEmpty() }
            .groupBy { it.vertical to it.horizontal }
            .map { (position, grouped) ->
                StyledSubtitleBlock(grouped.map { it.text }.joinLines(), position.first, position.second)
            }
    )

internal fun List<AnnotatedString>.joinLines(): AnnotatedString = buildAnnotatedString {
    this@joinLines.forEachIndexed { index, line ->
        if (index > 0) append('\n')
        append(line)
    }
}

private val assOverrideBlock = Regex("""\{[^}]*\}""")
private val assAlignment = Regex("""\\an([1-9])(?!\d)""")
private val assStyleTag = Regex("""\\(?:([ibu])(\d*)(?![a-z])|(1?c)(?:&H([0-9A-Fa-f]{1,8})&?|(?![a-z]))|r(?![a-z]))""")

internal fun parseMpvSubtitleText(text: String): StyledSubtitleCues =
    styledSubtitleCues(
        text.split('\n').map(String::trim).filter { it.isNotEmpty() }.map { event ->
            val alignment = assOverrideBlock.findAll(event)
                .flatMap { block -> assAlignment.findAll(block.value) }
                .lastOrNull()
                ?.groupValues
                ?.get(1)
                ?.toInt()
                ?: 2
            StyledSubtitleBlock(
                text = event.toAnnotatedEvent(),
                vertical = when (alignment) {
                    in 7..9 -> SubtitleVerticalPosition.TOP
                    in 4..6 -> SubtitleVerticalPosition.MIDDLE
                    else -> SubtitleVerticalPosition.BOTTOM
                },
                horizontal = when (alignment % 3) {
                    1 -> SubtitleHorizontalPosition.START
                    0 -> SubtitleHorizontalPosition.END
                    else -> SubtitleHorizontalPosition.CENTER
                }
            )
        }
    )

private fun String.toAnnotatedEvent(): AnnotatedString = buildAnnotatedString {
    var italic = false
    var bold = false
    var underline = false
    var color: Color? = null
    var cursor = 0

    fun appendPlain(raw: String) {
        val plain = raw.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", "\u00A0")
        if (plain.isEmpty()) return
        if (!italic && !bold && !underline && color == null) {
            append(plain)
            return
        }
        withStyle(
            SpanStyle(
                color = color ?: Color.Unspecified,
                fontStyle = if (italic) FontStyle.Italic else null,
                fontWeight = if (bold) FontWeight.Bold else null,
                textDecoration = if (underline) TextDecoration.Underline else null
            )
        ) { append(plain) }
    }

    assOverrideBlock.findAll(this@toAnnotatedEvent).forEach { block ->
        appendPlain(substring(cursor, block.range.first))
        assStyleTag.findAll(block.value).forEach { tag ->
            val (style, value, colorTag, bgr) = tag.destructured
            when {
                style.isNotEmpty() -> {
                    val enabled = value != "0"
                    when (style) {
                        "i" -> italic = enabled
                        "b" -> bold = enabled
                        "u" -> underline = enabled
                    }
                }
                colorTag.isNotEmpty() -> color = bgr.takeIf { it.isNotEmpty() }?.let(::assBgrColor)
                else -> {
                    italic = false
                    bold = false
                    underline = false
                    color = null
                }
            }
        }
        cursor = block.range.last + 1
    }
    appendPlain(substring(cursor))
}

private fun assBgrColor(hex: String): Color {
    val bgr = hex.takeLast(6).padStart(6, '0').toInt(16)
    return Color(red = bgr and 0xFF, green = (bgr shr 8) and 0xFF, blue = (bgr shr 16) and 0xFF)
}
