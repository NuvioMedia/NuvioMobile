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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
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

@Composable
internal fun StyledSubtitleOverlay(text: String, style: SubtitleStyleState, modifier: Modifier = Modifier) {
    val fontFamily = rememberSubtitleFontFamily(style.appearance.fontId)
    if (text.isBlank()) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val scale = (maxHeight / 360.dp).coerceAtMost(1f)
        Box(
            Modifier.fillMaxSize().padding(
                horizontal = 16.dp * scale,
                vertical = maxHeight * (0.0533f + style.bottomOffset.coerceIn(0, 200) / 1000f),
            ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            StyledSubtitleText(text, style, scale = scale, fontFamily = fontFamily)
        }
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
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val paddingX = with(density) { (8.dp * scale).toPx() }
        val paddingY = with(density) { (4.dp * scale).toPx() }
        val maxTextWidth = (constraints.maxWidth - paddingX * 2).toInt().coerceAtLeast(1)
        val layout = measurer.measure(
            text = AnnotatedString(text),
            style = TextStyle(
                fontFamily = fontFamily,
                fontSize = (style.fontSizeSp * scale).sp,
                fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                textDirection = TextDirection.ContentOrLtr,
            ),
            constraints = Constraints(maxWidth = maxTextWidth),
        )
        val textHeight = with(density) { (layout.size.height + paddingY * 2).toDp() }
        val radius = with(density) { (style.appearance.backgroundRadius.dp * scale).toPx() }
        val outline = with(density) { (style.outlineWidth.dp * scale).toPx() }
        val shadowSize = with(density) { (style.appearance.shadowSize.dp * scale).toPx() }
        Canvas(
            Modifier.fillMaxWidth().height(textHeight).semantics { this.text = AnnotatedString(text) },
        ) {
            val boxWidth = layout.size.width + paddingX * 2
            val left = (size.width - boxWidth) / 2f
            val origin = Offset(left + paddingX, paddingY)
            drawRoundRect(
                color = style.backgroundColor,
                topLeft = Offset(left, 0f),
                size = androidx.compose.ui.geometry.Size(boxWidth, size.height),
                cornerRadius = CornerRadius(radius),
            )
            if (style.outlineEnabled && outline > 0) {
                drawText(
                    layout, color = style.outlineColor.copy(alpha = style.outlineColor.alpha * style.textColor.alpha),
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
