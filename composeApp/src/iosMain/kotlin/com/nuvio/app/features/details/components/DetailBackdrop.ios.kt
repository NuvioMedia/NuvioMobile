package com.nuvio.app.features.details.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.Image
import coil3.toBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

internal actual fun prepareDetailBackdrop(image: Image): ImageBitmap {
    val source = image.toBitmap()
    val info = ImageInfo(source.width, source.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
    val bytes = requireNotNull(source.readPixels(info, source.width * 4))
    val pixels = IntArray(source.width * source.height) { index ->
        val offset = index * 4
        ((bytes[offset + 3].toInt() and 255) shl 24) or
            ((bytes[offset].toInt() and 255) shl 16) or
            ((bytes[offset + 1].toInt() and 255) shl 8) or
            (bytes[offset + 2].toInt() and 255)
    }
    blurBackdropPixels(pixels, source.width, source.height)
    pixels.forEachIndexed { index, pixel ->
        val offset = index * 4
        bytes[offset] = (pixel ushr 16).toByte()
        bytes[offset + 1] = (pixel ushr 8).toByte()
        bytes[offset + 2] = pixel.toByte()
        bytes[offset + 3] = (pixel ushr 24).toByte()
    }
    return Bitmap().apply {
        check(installPixels(info, bytes, source.width * 4))
    }.asComposeImageBitmap()
}
