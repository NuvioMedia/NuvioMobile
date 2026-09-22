package com.nuvio.app.features.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import kotlin.math.round
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.streams_size
import org.jetbrains.compose.resources.stringResource

private const val STREAM_SIZE_PLACEHOLDER = "\uE000"

internal val LocalStreamSizeLabelFormat = staticCompositionLocalOf<(String) -> String> {
    { sizeLabel -> "SIZE $sizeLabel" }
}

@Composable
internal fun rememberStreamSizeLabelFormat(): (String) -> String {
    val template = stringResource(Res.string.streams_size, STREAM_SIZE_PLACEHOLDER)
    return remember(template) {
        { sizeLabel -> template.replace(STREAM_SIZE_PLACEHOLDER, sizeLabel) }
    }
}

internal object StreamBadgeChipDefaults {
    val shape = RoundedCornerShape(NuvioTokens.Radius.sm)
    val fileSizeHorizontalPadding = NuvioTokens.Space.s6
    val fileSizeFontSize: TextUnit = NuvioTokens.Type.labelSm
    val fileSizeLineHeight: TextUnit = NuvioTokens.LineHeight.bodySm
    val fileSizeLetterSpacing: TextUnit = NuvioTokens.LetterSpacing.none
}

internal enum class StreamBadgeChipSize(
    val containerHeight: Dp,
    val imageHeight: Dp,
    val minImageWidth: Dp,
    val maxImageWidth: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
) {
    STREAM(
        containerHeight = 20.dp,
        imageHeight = 16.dp,
        minImageWidth = 34.dp,
        maxImageWidth = 92.dp,
        horizontalPadding = 3.dp,
        verticalPadding = 2.dp,
    ),
    PREVIEW(
        containerHeight = 24.dp,
        imageHeight = 18.dp,
        minImageWidth = 38.dp,
        maxImageWidth = 112.dp,
        horizontalPadding = 4.dp,
        verticalPadding = 3.dp,
    ),
}

@Composable
internal fun StreamBadgeChip(
    imageURL: String,
    name: String,
    tagColor: String,
    tagStyle: String,
    borderColor: String,
    size: StreamBadgeChipSize,
    modifier: Modifier = Modifier,
) {
    val backgroundColorArgb = if (tagStyle.equals("filled", ignoreCase = true)) {
        tagColor.toBadgeColorArgbOrNull()
    } else {
        null
    }
    val outlineColorArgb = borderColor.toBadgeColorArgbOrNull()
    val shape = StreamBadgeChipDefaults.shape
    var chipModifier = modifier.height(size.containerHeight)
    if (backgroundColorArgb != null) {
        chipModifier = chipModifier.background(Color(backgroundColorArgb), shape)
    }
    if (outlineColorArgb != null) {
        chipModifier = chipModifier.border(NuvioTokens.Border.thin, Color(outlineColorArgb), shape)
    }

    Box(
        modifier = chipModifier
            .padding(horizontal = size.horizontalPadding, vertical = size.verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = imageURL,
            contentDescription = name,
            modifier = Modifier
                .height(size.imageHeight)
                .widthIn(min = size.minImageWidth, max = size.maxImageWidth)
                .clip(shape),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
internal fun StreamBadgeImage(badge: StreamBadge) {
    StreamBadgeChip(
        imageURL = badge.imageURL,
        name = badge.name,
        tagColor = badge.tagColor,
        tagStyle = badge.tagStyle,
        borderColor = badge.borderColor,
        size = StreamBadgeChipSize.STREAM,
    )
}

@Composable
internal fun StreamFileSizeBadge(stream: StreamItem) {
    val tokens = MaterialTheme.nuvio
    val bytes = stream.behaviorHints.videoSize ?: return
    val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    val sizeLabel = if (gib >= 1.0) {
        val roundedGiB = round(gib * 10.0) / 10.0
        "$roundedGiB ${localizedByteUnit("GB")}"
    } else {
        val mib = bytes.toDouble() / (1024.0 * 1024.0)
        "${round(mib).toInt()} ${localizedByteUnit("MB")}"
    }

    val badgeShape = StreamBadgeChipDefaults.shape
    Box(
        modifier = Modifier
            .height(StreamBadgeChipSize.STREAM.containerHeight)
            .clip(badgeShape)
            .background(tokens.colors.surfacePopover)
            .border(tokens.borders.thin, tokens.colors.borderSubtle, badgeShape)
            .padding(horizontal = StreamBadgeChipDefaults.fileSizeHorizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = LocalStreamSizeLabelFormat.current(sizeLabel),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = StreamBadgeChipDefaults.fileSizeFontSize,
                lineHeight = StreamBadgeChipDefaults.fileSizeLineHeight,
                fontWeight = FontWeight.Bold,
                letterSpacing = StreamBadgeChipDefaults.fileSizeLetterSpacing,
            ),
            color = tokens.colors.textPrimary,
        )
    }
}

private fun String.toBadgeColorArgbOrNull(): Long? {
    val hex = trim().removePrefix("#")
    val argb = when (hex.length) {
        6 -> "FF$hex"
        8 -> hex
        else -> return null
    }
    return argb.toLongOrNull(16)
}
