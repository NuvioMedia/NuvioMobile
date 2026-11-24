package com.nuvio.app.features.streams

internal object SourceLanguageStreamOrdering {
    private val terms = mapOf(
        "en" to listOf("english", "eng"),
        "es" to listOf("spanish", "espanol", "español", "castellano", "spa"),
        "la" to listOf("latino", "latina", "lat", "latam"),
        "fr" to listOf("french", "francais", "français", "fre"),
        "de" to listOf("german", "deutsch", "ger"),
        "it" to listOf("italian", "italiano", "ita"),
        "pt" to listOf("portuguese", "portugues", "português", "por"),
        "ja" to listOf("japanese", "jpn"),
        "ko" to listOf("korean", "kor"),
        "zh" to listOf("chinese", "chi"),
        "hi" to listOf("hindi", "hin"),
    )

    fun order(streams: List<StreamItem>, language: String): List<StreamItem> {
        val selectedTerms = terms[language.lowercase()] ?: return streams
        return streams.withIndex().sortedWith(
            compareBy<IndexedValue<StreamItem>> { entry ->
                val text = listOfNotNull(entry.value.name, entry.value.title, entry.value.description)
                    .joinToString(" ").lowercase()
                if (selectedTerms.any(text::contains)) 0 else 1
            }.thenBy { it.index },
        ).map { it.value }
    }
}
