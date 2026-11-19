package com.nuvio.app.features.usenet

import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamBadge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** The source id of the built-in Usenet group among the addon groups. */
const val BUILT_IN_USENET_GROUP_ID = "builtin:usenet"

/** The group's name; like an addon's name, it is not translated. */
const val BUILT_IN_USENET_GROUP_NAME = "Built-in Usenet"

/** A badge drawn as its name in its colours rather than as an image. */
const val USENET_BADGE_STYLE_LABEL = "label"

/** On a release whose articles were missing or damaged when it was last tried. */
const val USENET_BADGE_UNAVAILABLE = "UNAVAILABLE"

/** On a release that showed its first frame recently with these providers. */
const val USENET_BADGE_AVAILABLE = "AVAILABLE"

/** On a release holding a whole season or several, whose size is not the episode's. */
const val USENET_BADGE_SEASON_PACK = "SEASON PACK"

/** "E02 FAILED" or "E02 PLAYED": how another episode of the same upload went. */
internal val USENET_SIBLING_BADGE = Regex("E(\\d+) (FAILED|PLAYED)")

/** Whether [badge] is drawn as a text label rather than an image. */
fun StreamBadge.isLabelBadge(): Boolean = imageURL.isBlank() && tagStyle == USENET_BADGE_STYLE_LABEL

/** The content types the built-in Usenet search covers. */
private val SEARCHED_TYPES = setOf("movie", "series", "tv", "show")

/**
 * The built-in Usenet search as one source of a stream search: each update replaces the group,
 * which stays loading until every indexer answered.
 */
internal class UsenetStreamSource(
    private val config: UsenetSourceConfiguration,
    private val type: String,
    private val videoId: String,
    private val season: Int?,
    private val episode: Int?,
) {
    val addonId: String get() = BUILT_IN_USENET_GROUP_ID
    val addonName: String get() = BUILT_IN_USENET_GROUP_NAME

    /** Changes with any source setting, so a held search for an older setup is not reused. */
    val configurationKey: String get() = config.hashCode().toString(16)

    fun loadingGroup(): AddonStreamGroup =
        AddonStreamGroup(addonName = addonName, addonId = addonId, streams = emptyList(), isLoading = true)

    fun search(): Flow<AddonStreamGroup> = flow {
        var last: AddonStreamGroup? = null
        val failures = mutableListOf<String>()
        try {
            UsenetPlatform.search(config, type, videoId, season, episode, addonName).collect { update ->
                update.failure?.let { failures += it }
                update.group?.let { group ->
                    last = group
                    emit(group.copy(error = failures.firstOrNull().takeIf { group.streams.isEmpty() && !group.isLoading }))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Like an addon that failed: the other sources keep going.
        }
        val final = last
        if (final == null || final.isLoading) {
            emit(
                AddonStreamGroup(
                    addonName = addonName,
                    addonId = addonId,
                    streams = final?.streams.orEmpty(),
                    isLoading = false,
                    error = failures.firstOrNull().takeIf { final?.streams.isNullOrEmpty() },
                ),
            )
        }
    }

    companion object {
        /** The search for this title, or null when built-in Usenet is off, incomplete or cannot search it. */
        fun create(type: String, videoId: String, season: Int?, episode: Int?): UsenetStreamSource? {
            if (!UsenetRepository.isSupported) return null
            val config = UsenetRepository.sources()
            if (!config.ready || !canSearch(type, videoId, season, episode)) return null
            return UsenetStreamSource(config, type, videoId, season, episode)
        }

        /**
         * Whether the built-in search can look up [videoId]: a movie, or an episode that names its
         * season and episode. A series id alone is not searched.
         */
        fun canSearch(type: String, videoId: String, season: Int?, episode: Int?): Boolean {
            val kind = type.lowercase()
            if (kind !in SEARCHED_TYPES) return false
            if (kind == "movie") return true
            val parts = videoId.split(':')
            val fromId = if (parts.size >= (if (videoId.startsWith("tmdb:")) 4 else 3)) {
                parts[parts.lastIndex - 1].toIntOrNull() to parts.last().toIntOrNull()
            } else {
                null to null
            }
            val s = season ?: fromId.first
            val e = episode ?: fromId.second
            return s != null && s >= 0 && e != null && e >= 1
        }
    }
}
