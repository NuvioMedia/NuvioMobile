package com.nuvio.app.features.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val TOP_SUBTITLE_INSET_FRACTION = 0.08f
private const val SIDE_SUBTITLE_INSET_FRACTION = 0.05f

@Composable
internal fun StyledSubtitleOverlay(cues: StyledSubtitleCues, style: SubtitleStyleState, modifier: Modifier = Modifier) {
    val fontFamily = rememberSubtitleFontFamily(style.appearance.fontId)
    if (cues.isEmpty) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val scale = (maxHeight / 360.dp).coerceAtMost(1f)
        val bottomInset = maxHeight * (0.0533f + style.bottomOffset.coerceIn(0, 200) / 1000f)
        val topInset = maxHeight * TOP_SUBTITLE_INSET_FRACTION
        val sideInset = maxWidth * SIDE_SUBTITLE_INSET_FRACTION
        cues.blocks.forEach { block ->
            val horizontalInset = 16.dp * scale + if (block.horizontal == SubtitleHorizontalPosition.CENTER) 0.dp else sideInset
            Box(
                Modifier.fillMaxSize().padding(
                    start = horizontalInset,
                    end = horizontalInset,
                    top = if (block.vertical == SubtitleVerticalPosition.TOP) topInset else 0.dp,
                    bottom = if (block.vertical == SubtitleVerticalPosition.BOTTOM) bottomInset else 0.dp,
                ),
                contentAlignment = block.alignment(),
            ) {
                StyledSubtitleText(
                    block.text, style, scale = scale, fontFamily = fontFamily, horizontal = block.horizontal,
                )
            }
        }
    }
}

private fun StyledSubtitleBlock.alignment(): Alignment = when (vertical) {
    SubtitleVerticalPosition.TOP -> when (horizontal) {
        SubtitleHorizontalPosition.START -> Alignment.TopStart
        SubtitleHorizontalPosition.CENTER -> Alignment.TopCenter
        SubtitleHorizontalPosition.END -> Alignment.TopEnd
    }
    SubtitleVerticalPosition.MIDDLE -> when (horizontal) {
        SubtitleHorizontalPosition.START -> Alignment.CenterStart
        SubtitleHorizontalPosition.CENTER -> Alignment.Center
        SubtitleHorizontalPosition.END -> Alignment.CenterEnd
    }
    SubtitleVerticalPosition.BOTTOM -> when (horizontal) {
        SubtitleHorizontalPosition.START -> Alignment.BottomStart
        SubtitleHorizontalPosition.CENTER -> Alignment.BottomCenter
        SubtitleHorizontalPosition.END -> Alignment.BottomEnd
    }
}

@Composable
internal fun StyledSubtitleText(
    text: String,
    style: SubtitleStyleState,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    fontFamily: FontFamily = rememberSubtitleFontFamily(style.appearance.fontId),
) {
    StyledSubtitleText(AnnotatedString(text), style, modifier, scale, fontFamily)
}

@Composable
internal fun StyledSubtitleText(
    text: AnnotatedString,
    style: SubtitleStyleState,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    fontFamily: FontFamily = rememberSubtitleFontFamily(style.appearance.fontId),
    horizontal: SubtitleHorizontalPosition = SubtitleHorizontalPosition.CENTER,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val paddingX = with(density) { (8.dp * scale).toPx() }
        val paddingY = with(density) { (4.dp * scale).toPx() }
        val maxTextWidth = (constraints.maxWidth - paddingX * 2).toInt().coerceAtLeast(1)
        val textStyle = TextStyle(
            fontFamily = fontFamily,
            fontSize = (style.fontSizeSp * scale).sp,
            fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = when (horizontal) {
                SubtitleHorizontalPosition.START -> TextAlign.Left
                SubtitleHorizontalPosition.CENTER -> TextAlign.Center
                SubtitleHorizontalPosition.END -> TextAlign.Right
            },
            textDirection = TextDirection.ContentOrLtr,
        )
        val textConstraints = Constraints(maxWidth = maxTextWidth)
        val textAlpha = style.textColor.alpha
        val fillText = text.mapColors { subtitleFileColor(it, textAlpha) }
        val layout = measurer.measure(text = fillText, style = textStyle, constraints = textConstraints)
        val outlineLayout = if (text.spanStyles.any { it.item.color.isSpecified }) {
            measurer.measure(text = text.mapColors { Color.Unspecified }, style = textStyle, constraints = textConstraints)
        } else {
            layout
        }
        val lineLeft = (0 until layout.lineCount).minOfOrNull { layout.getLineLeft(it) } ?: 0f
        val lineRight = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) } ?: 0f
        val textHeight = with(density) { (layout.size.height + paddingY * 2).toDp() }
        val radius = with(density) { (style.appearance.backgroundRadius.dp * scale).toPx() }
        val outline = with(density) { (style.outlineWidth.dp * scale).toPx() } / 2f
        val shadowSize = with(density) { (style.appearance.shadowSize.dp * scale).toPx() }
        Canvas(
            Modifier.fillMaxWidth().height(textHeight).semantics { this.text = text },
        ) {
            val (left, boxWidth, textLeft) = when (horizontal) {
                SubtitleHorizontalPosition.CENTER -> {
                    val width = layout.size.width + paddingX * 2
                    val boxLeft = (size.width - width) / 2f
                    Triple(boxLeft, width, boxLeft + paddingX)
                }
                SubtitleHorizontalPosition.START ->
                    Triple(0f, lineRight - lineLeft + paddingX * 2, paddingX - lineLeft)
                SubtitleHorizontalPosition.END -> {
                    val width = lineRight - lineLeft + paddingX * 2
                    Triple(size.width - width, width, size.width - paddingX - lineRight)
                }
            }
            val origin = Offset(textLeft, paddingY)
            drawRoundRect(
                color = style.backgroundColor,
                topLeft = Offset(left, 0f),
                size = androidx.compose.ui.geometry.Size(boxWidth, size.height),
                cornerRadius = CornerRadius(radius),
            )
            if (style.outlineEnabled && outline > 0) {
                drawText(
                    outlineLayout, color = style.outlineColor.copy(alpha = style.outlineColor.alpha * style.textColor.alpha),
                    topLeft = origin, drawStyle = Stroke(outline * 2), shadow = Shadow.None,
                )
            }
            drawText(
                layout, color = style.textColor, topLeft = origin, drawStyle = Fill,
                shadow = if (style.appearance.shadowEnabled) Shadow(
                    color = Color.Black.copy(alpha = style.appearance.shadowOpacity / 100f * style.textColor.alpha),
                    offset = Offset(shadowSize, shadowSize),
                    blurRadius = shadowSize * 2,
                ) else Shadow.None,
            )
        }
    }
}

internal fun subtitleFileColor(color: Color, textAlpha: Float): Color =
    color.copy(alpha = color.alpha * textAlpha)

private fun AnnotatedString.mapColors(transform: (Color) -> Color): AnnotatedString = buildAnnotatedString {
    append(this@mapColors.text)
    this@mapColors.spanStyles.forEach { range ->
        val color = range.item.color
        addStyle(
            if (color.isSpecified) range.item.copy(color = transform(color)) else range.item,
            range.start,
            range.end,
        )
    }
}
