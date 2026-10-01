package com.nuvio.app.features.streams

import com.nuvio.app.features.player.PlayerSettingsUiState

object StreamAutoPlayPolicy {
    fun isEffectivelyEnabled(settings: PlayerSettingsUiState): Boolean {
        if (settings.streamReuseLastLinkEnabled) return true
        if (settings.streamAutoPlayReuseBingeGroup && settings.streamAutoPlayPreferBingeGroup) return true

        return when (settings.streamAutoPlayMode) {
            StreamAutoPlayMode.MANUAL -> false
            StreamAutoPlayMode.FIRST_STREAM -> true
            StreamAutoPlayMode.BEST_QUALITY -> true
            StreamAutoPlayMode.REGEX_MATCH -> isRegexSelectionConfigured(settings.streamAutoPlayRegex)
        }
    }

    fun isRegexSelectionConfigured(regexPattern: String): Boolean {
        val pattern = regexPattern.trim()
        if (pattern.isEmpty() || !pattern.any { it.isLetterOrDigit() }) return false
        return runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.isSuccess
    }
}
