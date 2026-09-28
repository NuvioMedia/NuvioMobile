package com.nuvio.app.core.ui

import android.os.Build
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder =
    components {
        if (Build.VERSION.SDK_INT >= 28) {
            add(AnimatedImageDecoder.Factory())
        } else {
            add(GifDecoder.Factory())
        }
    }

internal actual val platformProvidesImageLoader: Boolean = true