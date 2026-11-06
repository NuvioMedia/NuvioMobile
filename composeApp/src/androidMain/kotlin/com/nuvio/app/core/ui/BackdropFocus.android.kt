package com.nuvio.app.core.ui

import android.graphics.Bitmap
import coil3.Image
import coil3.toBitmap
import kotlin.math.max
import kotlin.math.roundToInt

internal actual fun sampleBackdropPixels(image: Image, targetWidth: Int): SampledPixels? {
    val source = image.toBitmap()
    if (source.width <= 0 || source.height <= 0) return null
    val software = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
    } else {
        source
    }
    val width = targetWidth.coerceAtMost(software.width)
    val height = max(1, (software.height * width.toFloat() / software.width).roundToInt())
    val scaled = Bitmap.createScaledBitmap(software, width, height, true)
    val pixels = IntArray(width * height)
    scaled.getPixels(pixels, 0, width, 0, 0, width, height)
    if (scaled !== software) scaled.recycle()
    if (software !== source) software.recycle()
    return SampledPixels(pixels, width, height)
}
