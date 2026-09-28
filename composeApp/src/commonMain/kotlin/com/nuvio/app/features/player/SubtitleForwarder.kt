package com.nuvio.app.features.player

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

object SubtitleForwarder {

    /**
     * Fetches addon subtitles for the given content and filters them by the user's
     * preferred and secondary language. Returns null on failure or timeout for
     * graceful degradation (external player launches without subtitles).
     */
    suspend fun fetchForExternalPlayer(
        type: String,
        videoId: String,
        preferredLanguage: String,
        secondaryLanguage: String?,
        extras: SubtitleRequestExtras? = null,
        timeoutMs: Long = 10_000L,
    ): List<SubtitleInput>? {
        return try {
            withTimeoutOrNull(timeoutMs) {
                SubtitleRepository.fetchAddonSubtitles(type, videoId, extras)

                // Give the internal coroutine a chance to start and set isLoading = true
                kotlinx.coroutines.delay(50)

                // Wait for loading to complete (isLoading goes from true back to false)
                // If it's already false (fetch completed very quickly or never started), skip waiting
                if (SubtitleRepository.isLoading.value) {
                    SubtitleRepository.isLoading.first { !it }
                }

                val allSubtitles = SubtitleRepository.addonSubtitles.value

                val filtered = allSubtitles.filter { subtitle ->
                    languageMatchesPreference(subtitle.language, preferredLanguage) ||
                        (secondaryLanguage != null &&
                            languageMatchesPreference(subtitle.language, secondaryLanguage))
                }

                filtered.map { subtitle ->
                    SubtitleInput(
                        url = subtitle.url,
                        name = subtitle.display,
                        lang = subtitle.language,
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
