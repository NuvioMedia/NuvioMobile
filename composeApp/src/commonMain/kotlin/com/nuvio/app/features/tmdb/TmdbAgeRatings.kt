package com.nuvio.app.features.tmdb

private val languageDefaultRegions = mapOf(
    "ar" to "SA", "bg" to "BG", "bs" to "BA", "cs" to "CZ", "da" to "DK",
    "de" to "DE", "el" to "GR", "es" to "ES", "et" to "EE", "fi" to "FI",
    "fr" to "FR", "he" to "IL", "hi" to "IN", "hr" to "HR", "hu" to "HU",
    "id" to "ID", "it" to "IT", "ja" to "JP", "ko" to "KR", "lt" to "LT",
    "lv" to "LV", "nb" to "NO", "nl" to "NL", "no" to "NO", "pl" to "PL", "pt" to "PT",
    "ro" to "RO", "ru" to "RU", "sk" to "SK", "sl" to "SI", "sr" to "RS",
    "sv" to "SE", "th" to "TH", "tr" to "TR", "uk" to "UA", "ur" to "PK", "vi" to "VN",
    "zh" to "CN",
)

internal fun preferredAgeRatingRegions(normalizedLanguage: String): List<String> {
    val languageCode = normalizedLanguage.substringBefore("-").lowercase()
    val region = normalizedLanguage.substringAfter("-", "").uppercase().takeIf { it.length == 2 }
        ?: languageDefaultRegions[languageCode]
    return buildList {
        if (region != null) add(region)
        add("US")
        add("GB")
    }.distinct()
}
