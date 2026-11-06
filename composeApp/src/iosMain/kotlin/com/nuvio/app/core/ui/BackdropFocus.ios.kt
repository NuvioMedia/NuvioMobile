package com.nuvio.app.core.ui

import coil3.Image
import coil3.toBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import kotlin.math.max
import kotlin.math.min

internal actual fun sampleBackdropPixels(image: Image, targetWidth: Int): SampledPixels? {
    val source = image.toBitmap()
    val srcW = source.width
    val srcH = source.height
    if (srcW <= 0 || srcH <= 0) return null
    val info = ImageInfo(srcW, srcH, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
    val bytes = source.readPixels(info, srcW * 4) ?: return null

    val width = min(targetWidth, srcW)
    val height = max(1, srcH * width / srcW)
    val pixels = IntArray(width * height)
    for (ty in 0 until height) {
        val y0 = ty * srcH / height
        val y1 = max(y0 + 1, (ty + 1) * srcH / height)
        for (tx in 0 until width) {
            val x0 = tx * srcW / width
            val x1 = max(x0 + 1, (tx + 1) * srcW / width)
            var r = 0L
            var g = 0L
            var b = 0L
            var n = 0L
            // Stride through the block; full averaging is unnecessary for saliency.
            var y = y0
            while (y < y1) {
                var x = x0
                val row = y * srcW
                while (x < x1) {
                    val o = (row + x) * 4
                    r += bytes[o].toInt() and 0xFF
                    g += bytes[o + 1].toInt() and 0xFF
                    b += bytes[o + 2].toInt() and 0xFF
                    n++
                    x += 2
                }
                y += 2
            }
            pixels[ty * width + tx] = (0xFF shl 24) or
                ((r / n).toInt() shl 16) or
                ((g / n).toInt() shl 8) or
                (b / n).toInt()
        }
    }
    return SampledPixels(pixels, width, height)
}
