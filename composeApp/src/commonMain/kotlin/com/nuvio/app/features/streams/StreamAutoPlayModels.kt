package com.nuvio.app.features.streams

enum class StreamAutoPlayMode {
    MANUAL,
    FIRST_STREAM,
    REGEX_MATCH,
    /** Show a quality picker, then automatically try matching/lower-quality fallbacks. */
    SEMI_AUTOMATIC,
}

enum class StreamAutoPlayPreferredQuality(val targetHeight: Int?) {
    ANY(null), P2160(2160), P1440(1440), P1080(1080), P720(720), P480(480), P360(360),
}

enum class StreamAutoPlaySource {
    ALL_SOURCES,
    INSTALLED_ADDONS_ONLY,
    ENABLED_PLUGINS_ONLY,
}
