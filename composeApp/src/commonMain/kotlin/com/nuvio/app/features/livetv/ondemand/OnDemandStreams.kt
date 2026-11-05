package com.nuvio.app.features.livetv.ondemand

import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.servers.ServerStreamSource
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders
import com.nuvio.app.features.tmdb.TmdbService

/**
 * Adds an "On Demand" group to the streams Nuvio finds for a movie or episode, when one of your
 * providers has it. It sits next to your addon streams. The TV app's OnDemandStreams.
 */
internal object OnDemandStreams {
    const val GROUP_NAME = "📡 On Demand"
    const val GROUP_ID = "livetv-ondemand"

    /** The same for every provider copy, so "next episode from the same source" stays on your provider. */
    const val BINGE_GROUP = "nuvio-iptv-on-demand"

    fun isOnDemandGroup(addonId: String?): Boolean = addonId == GROUP_ID

    /** Where the On Demand tab goes in the stream list: "FIRST", "AFTER_FIRST" (the first addon) or "LAST". */
    fun tabPosition(): String = LiveTvPreferences.currentSettings().onDemandStreamsPosition

    /** Auto-play may pick your provider's copy: switched on, and its tab is first. */
    fun autoPlayAllowed(): Boolean = LiveTvPreferences.currentSettings().let { it.onDemandAutoPlay && it.onDemandStreamsPosition == "FIRST" }

    /** [mine] put where it is set to go among the already ordered [groups]: first, after the first one, or last. */
    fun <T> place(groups: List<T>, mine: T): List<T> = when (tabPosition()) {
        "FIRST" -> listOf(mine) + groups
        "AFTER_FIRST" -> groups.take(1) + mine + groups.drop(1)
        else -> groups + mine
    }

    /** Title shown on the streams screen, by item id: used when the details aren't cached (opened from a row, search, Continue Watching). */
    private val titleHints = HashMap<String, String>()

    fun rememberTitle(parentMetaId: String?, videoId: String, title: String) {
        val id = baseId(parentMetaId, videoId)
        if (id.isNotBlank() && title.isNotBlank()) titleHints[id] = title
    }

    /** The movie's or show's own id ("tt123", "tmdb:456"), without season/episode parts. */
    private fun baseId(parentMetaId: String?, videoId: String): String =
        parentMetaId?.takeIf { it.isNotBlank() }
            ?: if (videoId.startsWith("tmdb:", true)) videoId.split(':').take(2).joinToString(":") else videoId.substringBefore(':')

    /** The On Demand source for a request, or nothing when it is off, empty, or the request isn't a movie or episode. */
    fun sources(type: String, videoId: String, parentMetaId: String?, season: Int?, episode: Int?): List<ServerStreamSource> {
        val settings = LiveTvPreferences.currentSettings()
        // The TV's "Off the menu only" is a TV layout thing; only "Off" (or this switch) hides it here.
        if (!(settings.onDemandInStreams && settings.onDemandEnabled)) return emptyList()
        if (!OnDemandRepository.hasContent.value) return emptyList()
        if (!type.equals("movie", true) && !type.equals("series", true) && !type.equals("tv", true)) return emptyList()
        return listOf(
            ServerStreamSource(
                addonId = GROUP_ID,
                addonName = GROUP_NAME,
                preferred = false,
                loader = { lookup(type, videoId, parentMetaId, season, episode) },
            ),
        )
    }

    private suspend fun lookup(type: String, videoId: String, parentMetaId: String?, season: Int?, episode: Int?): List<StreamItem> {
        val id = baseId(parentMetaId, videoId)
        val meta = runCatching { MetaDetailsRepository.peek(type, id) }.getOrNull()
            ?: runCatching { MetaDetailsRepository.fetch(type, id) }.getOrNull()
        val title = meta?.name?.takeIf { it.isNotBlank() } ?: titleHints[id].orEmpty()
        val year = meta?.releaseInfo?.take(4)?.toIntOrNull()
        val tmdbId = Regex("^tmdb:(\\d+)", RegexOption.IGNORE_CASE).find(id)?.groupValues?.get(1) ?: runCatching {
            TmdbService.ensureTmdbId(parentMetaId ?: videoId, if (type.equals("movie", true)) "movie" else "series", meta?.imdbId)
        }.getOrNull()
        if (title.isBlank() && tmdbId == null) return emptyList()
        return OnDemandRepository.sourcesFor(type, tmdbId, title, year, season, episode).map { s ->
            val quality = qualityTag(s)
            StreamItem(
                name = listOfNotNull(GROUP_NAME, quality).joinToString("\n"),
                description = describe(s, quality),
                url = s.url,
                sourceName = s.providerName,
                addonName = GROUP_NAME,
                addonId = GROUP_ID,
                behaviorHints = StreamBehaviorHints(
                    bingeGroup = BINGE_GROUP,
                    notWebReady = s.headers.isNotEmpty(),
                    filename = releaseName(s, quality),
                    proxyHeaders = s.headers.takeIf { it.isNotEmpty() }?.let { StreamProxyHeaders(request = it) },
                ),
            )
        }
    }

    /**
     * "4K", "1080p", "720p" or "SD" (plus " HEVC") from the provider's full title: the shown
     * title has tags like "4K-D+ - " taken off, so the quality is read before that.
     */
    private fun qualityTag(s: OnDemandRepository.OnDemandSource): String? {
        val q = VodTitles.qualityOf(s.providerTitle.ifBlank { s.title }) ?: return null
        val hevc = q.endsWith(" HEVC")
        val res = when (q.removeSuffix(" HEVC")) { "Full HD" -> "1080p"; "HD" -> "720p"; else -> q.removeSuffix(" HEVC") }
        return if (hevc) "$res HEVC" else res
    }

    private fun describe(s: OnDemandRepository.OnDemandSource, quality: String?): String {
        val head = "🎬 " + s.title + (s.year?.let { " ($it)" } ?: "") + (s.episodeTag?.let { " · $it" } ?: "")
        val line2 = listOf("📡 ${s.providerName}", s.ext.uppercase()).joinToString(" | ")
        val line3 = quality?.let { "🎞 " + it.replace(" HEVC", " · HEVC") }
        return listOfNotNull(head, line2, line3).joinToString("\n")
    }

    /** A release-style name ("Obsession.2025.2160p.HEVC.mkv") so the app's own badge rules read it like an addon stream. */
    private fun releaseName(s: OnDemandRepository.OnDemandSource, quality: String?): String {
        val res = when (quality?.removeSuffix(" HEVC")) { "4K" -> "2160p"; "1080p" -> "1080p"; "720p" -> "720p"; "SD" -> "480p"; else -> null }
        return listOfNotNull(
            s.title.replace(Regex("""[^\p{L}\p{N}]+"""), ".").trim('.'),
            s.year?.toString(),
            s.episodeTag,
            res,
            "HEVC".takeIf { quality?.endsWith(" HEVC") == true },
        ).joinToString(".") + "." + s.ext
    }
}
