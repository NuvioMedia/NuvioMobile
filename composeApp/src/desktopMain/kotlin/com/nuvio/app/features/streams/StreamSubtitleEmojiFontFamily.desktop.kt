package com.nuvio.app.features.streams

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily

@OptIn(ExperimentalTextApi::class)
internal actual val streamSubtitleEmojiFontFamily: FontFamily? =
    if (System.getProperty("os.name").orEmpty().startsWith("Windows")) {
        FontFamily("Segoe UI Emoji")
    } else {
        null
    }
