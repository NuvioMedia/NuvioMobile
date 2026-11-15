@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.media3.common.text.Cue

internal class SubtitleCuePresenter(private val style: () -> SubtitleStyleState) {
    var cues by mutableStateOf(StyledSubtitleCues())
        private set
    private var lastCues = emptyList<Cue>()
    private var canStyle = true

    fun present(cues: List<Cue>, canStyle: Boolean = this.canStyle): List<Cue> {
        this.lastCues = cues
        this.canStyle = canStyle
        return refresh()
    }

    fun refresh(): List<Cue> {
        if (!canStyle || !style().usesStyledOverlay) {
            cues = StyledSubtitleCues()
            return lastCues
        }
        cues = styledSubtitleCues(
            lastCues.filter { !it.text.isNullOrBlank() }.map { cue ->
                StyledSubtitleBlock(
                    text = cue.text?.toSubtitleAnnotatedString() ?: AnnotatedString(""),
                    vertical = cue.verticalPosition(),
                    horizontal = cue.horizontalPosition(),
                )
            },
        )
        return lastCues.filter { it.text == null }
    }
}

private fun Cue.verticalPosition(): SubtitleVerticalPosition = when {
    line == Cue.DIMEN_UNSET -> SubtitleVerticalPosition.BOTTOM
    lineType == Cue.LINE_TYPE_NUMBER ->
        if (line >= 0f) SubtitleVerticalPosition.TOP else SubtitleVerticalPosition.BOTTOM
    lineType != Cue.LINE_TYPE_FRACTION -> SubtitleVerticalPosition.BOTTOM
    line < 1f / 3f -> SubtitleVerticalPosition.TOP
    line <= 2f / 3f -> SubtitleVerticalPosition.MIDDLE
    else -> SubtitleVerticalPosition.BOTTOM
}

private fun Cue.horizontalPosition(): SubtitleHorizontalPosition = when {
    position == Cue.DIMEN_UNSET -> SubtitleHorizontalPosition.CENTER
    position < 1f / 3f -> SubtitleHorizontalPosition.START
    position > 2f / 3f -> SubtitleHorizontalPosition.END
    else -> SubtitleHorizontalPosition.CENTER
}

private fun CharSequence.toSubtitleAnnotatedString(): AnnotatedString {
    val spanned = this as? Spanned ?: return AnnotatedString(toString())
    return buildAnnotatedString {
        append(spanned.toString())
        spanned.getSpans(0, spanned.length, Any::class.java).forEach { span ->
            val spanStyle = when {
                span is UnderlineSpan -> SpanStyle(textDecoration = TextDecoration.Underline)
                span is ForegroundColorSpan -> SpanStyle(color = Color(span.foregroundColor))
                span is StyleSpan && span.style == Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                span is StyleSpan && span.style == Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                span is StyleSpan && span.style == Typeface.BOLD_ITALIC ->
                    SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                else -> null
            } ?: return@forEach
            addStyle(spanStyle, spanned.getSpanStart(span), spanned.getSpanEnd(span))
        }
    }
}
