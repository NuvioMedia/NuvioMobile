package com.nuvio.app.features.trailer

import android.graphics.Bitmap
import android.view.TextureView

class LetterboxSampler {

    private val bitmap = Bitmap.createBitmap(
        LetterboxDetector.SAMPLE_WIDTH,
        LetterboxDetector.SAMPLE_HEIGHT,
        Bitmap.Config.ARGB_8888
    )
    private val pixels = IntArray(LetterboxDetector.SAMPLE_WIDTH * LetterboxDetector.SAMPLE_HEIGHT)

    fun sample(textureView: TextureView): Float? {
        if (!textureView.isAvailable) return null
        textureView.getBitmap(bitmap)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return LetterboxDetector.detectBar(pixels, bitmap.width, bitmap.height)
    }

    fun release() {
        bitmap.recycle()
    }
}
