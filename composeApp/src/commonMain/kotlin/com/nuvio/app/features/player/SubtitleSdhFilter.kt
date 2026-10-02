package com.nuvio.app.features.player

internal object SubtitleSdhFilter {
    private val squareBrackets = Regex("\\[[^]]*][ \\t]*")
    // ">>" marks a speaker change and ">>>" a topic change in CEA-608 style
    // captions, which survive into text subtitles.
    private val speakerChevrons = Regex("[<>]{2,}[ \t]*")
    private val parentheses = Regex(
        "(?:\\((?=[A-Za-z0-9 '#.,\\\"\\\\\\-\\r\\n]*\\))(?![0-9]*\\))[^)]*\\)|" +
            "（(?=[A-Za-z0-9 '#.,\\\"\\\\\\-\\r\\n]*）)(?![0-9]*）)[^）]*）)[ \\t]*",
    )
    private val speakerLabel = Regex(
        "(?m)^([ \\t]*-[ \\t]*)?(?:[A-Za-z0-9 ()'#.,]+|\\[[^]\\r\\n]*]):(?=\\s|$)[ \\t]*",
    )

    fun filter(text: String): String? {
        // Runs before speakerLabel so that ">> NAME:" loses the chevrons first and
        // is then recognised as a speaker label.
        var filtered = speakerChevrons.replace(text, "")
        filtered = speakerLabel.replace(filtered) { match -> match.groups[1]?.value.orEmpty() }
        filtered = squareBrackets.replace(filtered, "")
        filtered = parentheses.replace(filtered, "")
        return filtered.lines()
            .filter { line -> line.any { !it.isWhitespace() && it != '-' } }
            .joinToString("\n")
            .takeIf(String::isNotBlank)
    }
}
