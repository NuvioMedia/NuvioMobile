package com.nuvio.app.core.ui

import android.graphics.Bitmap
import coil3.Image
import coil3.toBitmap

internal actual fun sampleBackdropPixels(image: Image, targetWidth: Int): SampledPixels? {
    val source = image.toBitmap()
    if (source.width <= 0 || source.height <= 0) return null
    val software = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
    } else {
        source
    }
    val width = software.width
    val height = software.height
    val pixels = IntArray(width * height)
    software.getPixels(pixels, 0, width, 0, 0, width, height)
    if (software !== source) software.recycle()
    return downsampleArgb(pixels, width, height, targetWidth)
}
