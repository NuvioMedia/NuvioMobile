package com.nuvio.app.core.ui

import coil3.Image
import coil3.toBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

internal actual fun sampleBackdropPixels(image: Image, targetWidth: Int): SampledPixels? {
    val source = image.toBitmap()
    val width = source.width
    val height = source.height
    if (width <= 0 || height <= 0) return null
    val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
    val bytes = source.readPixels(info, width * 4) ?: return null
    val pixels = IntArray(width * height) { index ->
        val offset = index * 4
        (0xFF shl 24) or
            ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)
    }
    return downsampleArgb(pixels, width, height, targetWidth)
}
