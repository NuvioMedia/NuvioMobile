package com.nuvio.app.features.livetv.sports

import com.nuvio.app.features.livetv.model.LiveChannel

/**
 * "Channels from": which region's (or language's) channels a game's list shows.
 *
 * Worked out from what's already matched to a game (a dozen channels at most), never by
 * searching the guide again, so changing it is instant and costs no loading time. Saved in
 * [SportsSettings.channelRegion]: "" = all, "UK"/"US"/"IE"/"CA"/"AU", "EN" = any of those, or
 * "lang:es" and the like for a language.
 */
internal object ChannelRegions {
    const val ENGLISH = "EN"
    private val ENGLISH_REGIONS = setOf("UK", "US", "IE", "CA", "AU")

    private val LANGUAGES = listOf(
        "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian", "pt" to "Portuguese",
        "nl" to "Dutch", "pl" to "Polish", "tr" to "Turkish", "ar" to "Arabic", "ru" to "Russian"
    )

    /** Every choice, for Sports settings (the game page offers only the ones its channels have). */
    val all: List<Pair<String, String>> = listOf(
        "" to "All regions",
        ENGLISH to "English (UK, US, Ireland, Canada, Australia)",
        "UK" to "United Kingdom",
        "US" to "United States",
        "IE" to "Ireland",
        "CA" to "Canada",
        "AU" to "Australia & New Zealand"
    ) + LANGUAGES.map { (code, name) -> "lang:$code" to name }

    fun name(code: String): String = all.firstOrNull { it.first == code }?.second ?: code

    /** The short form, for the button on the game page. */
    fun shortName(code: String): String = when (code) {
        "" -> "All regions"
        ENGLISH -> "English"
        "UK" -> "UK"
        "US" -> "USA"
        "AU" -> "Australia"
        else -> name(code)
    }

    /** Does a channel from [channelCode] (see [SportsMatcher.channelRegionCode]) belong to [choice]? */
    fun includes(channelCode: String?, choice: String): Boolean = when {
        choice.isBlank() -> true
        channelCode == null -> false
        choice == ENGLISH -> channelCode in ENGLISH_REGIONS
        else -> channelCode == choice
    }

    fun includes(ch: LiveChannel, choice: String): Boolean =
        choice.isBlank() || includes(SportsMatcher.channelRegionCode(ch), choice)

    /**
     * [list] with the chosen region's channels moved up (your own picks stay on top; the order
     * is otherwise kept). Used for every game, so reminders, Watch now and the home rows pick a
     * channel from your region too.
     */
    fun regionFirst(list: List<ChannelMatch>, choice: String): List<ChannelMatch> {
        if (choice.isBlank() || list.size < 2) return list
        return list.sortedWith(
            compareByDescending<ChannelMatch> { if (it.score >= 150) 1 else 0 }
                .thenByDescending { if (includes(it.channel, choice)) 1 else 0 }
        )
    }

    /** The choices a game's channels offer, with how many of its channels each one has. */
    fun choicesFor(codes: Collection<String?>, current: String): List<Pair<String, Int>> {
        val counts = codes.filterNotNull().groupingBy { it }.eachCount()
        val out = ArrayList<Pair<String, Int>>()
        val english = codes.count { it != null && it in ENGLISH_REGIONS }
        // "English" is worth offering when it groups more than one region, or sets them apart from other languages.
        val englishRegions = counts.keys.count { it in ENGLISH_REGIONS }
        if (english > 0 && (englishRegions > 1 || counts.keys.any { it !in ENGLISH_REGIONS })) out += ENGLISH to english
        counts.entries.sortedByDescending { it.value }.forEach { (code, n) -> out += code to n }
        if (current.isNotBlank() && out.none { it.first == current }) out += current to 0
        return out
    }
}
