package com.nuvio.app.features.livetv.sports

/** What the Sports hub needs from the device: plain HTTP GETs of JSON feeds, and the device's locale. */
internal expect object SportsPlatform {
    /** GET [url] as text (JSON). Throws with a plain message on failure ("HTTP 404", "timed out"). */
    suspend fun get(url: String): String

    /** The device's country ("US", "GB"), upper case; "" when unknown. */
    fun countryCode(): String

    /** The device's language ("en", "es"), lower case. */
    fun languageCode(): String

    /** Strips accents the way java.text.Normalizer NFD + mark removal does, where the platform can. */
    fun foldAccents(text: String): String
}
