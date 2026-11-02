package com.nuvio.app.features.livetv.sports

/** The Sports hub isn't built for iOS yet: no feeds are read. */
internal actual object SportsPlatform {
    actual suspend fun get(url: String): String = error("Sports feeds aren't available on this platform yet")
    actual fun countryCode(): String = ""
    actual fun languageCode(): String = "en"
    actual fun pause(ms: Long) = Unit
    actual fun foldAccents(text: String): String = SportsAccents.fold(text)
}
