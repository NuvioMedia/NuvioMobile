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

    /** The On Demand source for a request, or nothing when it is off, empty, or the request isn't a movie or episode. */
    fun sources(type: String, videoId: String, parentMetaId: String?, season: Int?, episode: Int?): List<ServerStreamSource> {
        val settings = LiveTvPreferences.currentSettings()
        // Only this phone-visible switch counts: the TV's side-menu choice (synced) is a TV layout thing.
        if (!settings.onDemandInStreams) return emptyList()
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
        val meta = runCatching { MetaDetailsRepository.peek(type, parentMetaId ?: videoId.substringBefore(':')) }.getOrNull()
        val title = meta?.name.orEmpty()
        val year = meta?.releaseInfo?.take(4)?.toIntOrNull()
        val tmdbId = runCatching {
            TmdbService.ensureTmdbId(parentMetaId ?: videoId, if (type.equals("movie", true)) "movie" else "series", meta?.imdbId)
        }.getOrNull()
        if (title.isBlank() && tmdbId == null) return emptyList()
        return OnDemandRepository.sourcesFor(type, tmdbId, title, year, season, episode).map { s ->
            val quality = VodTitles.qualityOf(s.title)
            StreamItem(
                name = listOfNotNull(GROUP_NAME, quality).joinToString("\n"),
                description = describe(s),
                url = s.url,
                sourceName = s.providerName,
                addonName = GROUP_NAME,
                addonId = GROUP_ID,
                behaviorHints = StreamBehaviorHints(
                    bingeGroup = BINGE_GROUP,
                    notWebReady = s.headers.isNotEmpty(),
                    filename = releaseName(s),
                    proxyHeaders = s.headers.takeIf { it.isNotEmpty() }?.let { StreamProxyHeaders(request = it) },
                ),
            )
        }
    }

    private fun describe(s: OnDemandRepository.OnDemandSource): String {
        val head = "🎬 " + s.title + (s.year?.let { " ($it)" } ?: "") + (s.episodeTag?.let { " · $it" } ?: "")
        val line2 = listOf("📡 ${s.providerName}", s.ext.uppercase()).joinToString(" | ")
        return listOf(head, line2).joinToString("\n")
    }

    /** A release-style name ("Obsession.2025.mkv") so the app's own badge rules read it like an addon stream. */
    private fun releaseName(s: OnDemandRepository.OnDemandSource): String =
        listOfNotNull(
            s.title.replace(Regex("""[^\p{L}\p{N}]+"""), ".").trim('.'),
            s.year?.toString(),
            s.episodeTag,
        ).joinToString(".") + "." + s.ext
}
