package com.nuvio.app.features.audiosync.asr

internal object AsrTokens {
    fun merge(tokens: List<String>, timestamps: List<Double>): List<Pair<Double, String>> {
        val words = ArrayList<Pair<Double, String>>()
        val current = StringBuilder()
        var start = 0.0
        for (i in tokens.indices) {
            val token = tokens[i]
            val startsWord = token.startsWith('▁') || token.startsWith(' ') || current.isEmpty()
            if (startsWord) {
                if (current.isNotEmpty()) words += start to current.toString()
                current.clear()
                start = timestamps.getOrElse(i) { 0.0 }
            }
            current.append(token.trimStart('▁', ' '))
        }
        if (current.isNotEmpty()) words += start to current.toString()
        return words
    }
}
