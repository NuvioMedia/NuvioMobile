package com.nuvio.app.features.details.components

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.Image
import coil3.toBitmap

internal actual fun prepareDetailBackdrop(image: Image): ImageBitmap {
    val bitmap = requireNotNull(image.toBitmap().copy(Bitmap.Config.ARGB_8888, true))
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    blurBackdropPixels(pixels, bitmap.width, bitmap.height)
    bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return bitmap.asImageBitmap()
}
