package com.nuvio.app.features.mdblist

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.buildTmdbUrl
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.parseTrackingExternalIds
import com.nuvio.app.features.trakt.EpisodeMappingEntry
import com.nuvio.app.features.trakt.TraktEpisodeMappingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Remaps episodes between the addon's numbering and MDBList's, which follows TMDB.
 *
 * Anime is often split into seasons differently on TMDB and in the metadata an addon serves, so
 * TMDB S1E35 can be S2E11 for the addon. When the two sides have different seasons, episodes are
 * matched by title and otherwise by their position in the whole episode list, the same way Trakt
 * numbering is remapped. Shows with the same seasons keep their numbers, so a few merged
 * two-parters do not shift every later season.
 */
class MdbListEpisodeMappingService internal constructor(
    private val coroutineScope: CoroutineScope,
    private val addonEpisodes: suspend (contentId: String) -> List<EpisodeMappingEntry> = { contentId ->
        TraktEpisodeMappingService.getAddonEpisodes(contentId, "series")
    },
    private val tmdb: MdbListTmdbEpisodes = TmdbMdbListEpisodes,
) {
    private val log = Logger.withTag("MdbListEpMap")
    private val mutableResolved = MutableStateFlow<Map<String, MdbListEpisodeMapping>>(emptyMap())
    private val mutableRemapped = MutableStateFlow<Map<String, MdbListEpisodeMapping>>(emptyMap())
    private val prefetched = MutableStateFlow<Set<String>>(emptySet())
    private val inFlightMutex = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<MdbListEpisodeMapping?>>()

    internal val remapped: StateFlow<Map<String, MdbListEpisodeMapping>> = mutableRemapped.asStateFlow()

    /** The mapping for [contentId] when it was already resolved, without touching the network. */
    internal fun cached(contentId: String): MdbListEpisodeMapping? = mutableResolved.value[contentId.trim()]

    /**
     * Resolves the mapping for the show the addon serves as [contentId], using the TMDB id MDBList
     * reported when [projection] has one. Returns null when either side's episodes could not be
     * loaded, so the caller keeps the numbering it has.
     */
    internal suspend fun resolve(contentId: String, projection: MdbListProgressProjection): MdbListEpisodeMapping? {
        val key = contentId.trim().takeIf(String::isNotBlank) ?: return null
        cached(key)?.let { return remember(key, it, projection) }
        val (deferred, owner) = inFlightMutex.withLock {
            cached(key)?.let { return remember(key, it, projection) }
            inFlight[key]?.let { it to false }
                ?: CompletableDeferred<MdbListEpisodeMapping?>().also { inFlight[key] = it }.let { it to true }
        }
        if (!owner) return deferred.await()
        return try {
            load(key, projection.tmdbShowId(key)).also { mapping ->
                if (mapping != null) remember(key, mapping, projection)
                deferred.complete(mapping)
            }
        } catch (error: CancellationException) {
            deferred.complete(null)
            throw error
        } catch (error: Exception) {
            log.w { "episode mapping failed for $key: ${error.message}" }
            deferred.complete(null)
            null
        } finally {
            inFlightMutex.withLock { inFlight.remove(key) }
        }
    }

    /** Resolves in the background the shows in [contentIds] that were not tried yet. */
    internal fun prefetch(contentIds: Collection<String>, projection: MdbListProgressProjection) {
        val pending = contentIds.filterTo(linkedSetOf()) { it !in mutableResolved.value && it !in prefetched.value }
        if (pending.isEmpty()) return
        prefetched.update { it + pending }
        coroutineScope.launch { pending.forEach { resolve(it, projection) } }
    }

    /**
     * [reference] with its episode moved from the addon's numbering to TMDB's, which MDBList reads
     * season and episode numbers in. References that need no remapping come back unchanged.
     */
    internal suspend fun toMdbListNumbering(
        reference: TrackingMediaReference,
        projection: MdbListProgressProjection,
    ): TrackingMediaReference {
        if (reference.kind == TrackingMediaKind.MOVIE) return reference
        val episode = reference.episode ?: return reference
        val season = episode.season ?: return reference
        val catalog = reference.catalog ?: return reference
        val tmdb = resolve(catalog.contentId, projection)
            ?.toTmdb(season, episode.number, catalog.videoId) ?: return reference
        if (tmdb.season == season && tmdb.episode == episode.number) return reference
        return reference.copy(episode = episode.copy(season = tmdb.season, number = tmdb.episode))
    }

    /**
     * Keeps [mapping] under every id MDBList knows the show by, so records that name it by another
     * id find it too. Ids MDBList learns later are added the next time the show is resolved.
     */
    private fun remember(
        contentId: String,
        mapping: MdbListEpisodeMapping,
        projection: MdbListProgressProjection,
    ): MdbListEpisodeMapping {
        val ids = projection.showIdSiblings[contentId].orEmpty() + contentId
        if (ids.any { mutableResolved.value[it] !== mapping }) {
            val added = ids.associateWith { mapping }
            mutableResolved.update { current -> current + added }
            if (mapping.remaps) mutableRemapped.update { current -> current + added }
        }
        return mapping
    }

    internal fun clear() {
        mutableResolved.value = emptyMap()
        mutableRemapped.value = emptyMap()
        prefetched.value = emptySet()
    }

    private suspend fun load(contentId: String, tmdbShowId: Long?): MdbListEpisodeMapping? {
        val addon = addonEpisodes(contentId)
        if (addon.isEmpty()) return null
        val tmdbId = tmdbShowId ?: tmdb.showId(contentId) ?: return null
        val seasons = tmdb.seasonCounts(tmdbId)
        if (seasons.isEmpty()) return null
        if (seasons.keys == addon.mapTo(mutableSetOf(), EpisodeMappingEntry::season)) return MdbListEpisodeMapping.Unchanged

        val tmdbEpisodes = coroutineScope {
            val semaphore = Semaphore(TMDB_SEASON_REQUEST_CONCURRENCY)
            seasons.keys.sorted().map { season ->
                async { semaphore.withPermit { tmdb.episodes(tmdbId, season) } }
            }.awaitAll().flatten()
        }
        if (tmdbEpisodes.isEmpty()) return null
        log.d { "remapping $contentId: addon=${addon.seasonCounts()} tmdb=${tmdbEpisodes.seasonCounts()}" }
        return MdbListEpisodeMapping.between(addon, tmdbEpisodes)
    }

    private fun List<EpisodeMappingEntry>.seasonCounts(): Map<Int, Int> =
        groupBy { it.season }.mapValues { it.value.size }

    private companion object {
        const val TMDB_SEASON_REQUEST_CONCURRENCY = 4
    }
}

/** Episode numbering of one show, translated between the addon and MDBList (TMDB). */
internal class MdbListEpisodeMapping private constructor(
    private val addonByTmdb: Map<Pair<Int, Int>, EpisodeMappingEntry>,
    private val tmdbByAddon: Map<Pair<Int, Int>, EpisodeMappingEntry>,
    private val tmdbByVideoId: Map<String, EpisodeMappingEntry>,
) {
    /** False when both sides split the show the same way and episodes keep their numbers. */
    val remaps: Boolean
        get() = addonByTmdb.isNotEmpty() || tmdbByAddon.isNotEmpty()

    /** The addon's episode for TMDB [season]/[episode], or null when it has no match. */
    fun toAddon(season: Int, episode: Int): EpisodeMappingEntry? = addonByTmdb[season to episode]

    /** TMDB's episode for the addon's [season]/[episode], or null when it has no match. */
    fun toTmdb(season: Int, episode: Int, videoId: String? = null): EpisodeMappingEntry? =
        videoId?.takeIf(String::isNotBlank)?.let(tmdbByVideoId::get) ?: tmdbByAddon[season to episode]

    companion object {
        val Unchanged = MdbListEpisodeMapping(emptyMap(), emptyMap(), emptyMap())

        fun between(addonEpisodes: List<EpisodeMappingEntry>, tmdbEpisodes: List<EpisodeMappingEntry>): MdbListEpisodeMapping {
            val addon = addonEpisodes.ordered()
            val tmdb = tmdbEpisodes.ordered()
            val tmdbByAddonEpisode = addon.matchedIn(tmdb)
            return MdbListEpisodeMapping(
                addonByTmdb = tmdb.matchedIn(addon).mapKeys { (episode, _) -> episode.season to episode.episode },
                tmdbByAddon = tmdbByAddonEpisode.mapKeys { (episode, _) -> episode.season to episode.episode },
                tmdbByVideoId = tmdbByAddonEpisode.mapNotNull { (episode, match) -> episode.videoId?.let { it to match } }.toMap(),
            )
        }

        private fun List<EpisodeMappingEntry>.ordered() =
            distinctBy { it.season to it.episode }.sortedWith(compareBy(EpisodeMappingEntry::season, EpisodeMappingEntry::episode))

        /** Each episode's match in [target]: its title when exactly one target episode has it, else its position. */
        private fun List<EpisodeMappingEntry>.matchedIn(target: List<EpisodeMappingEntry>): Map<EpisodeMappingEntry, EpisodeMappingEntry> {
            val targetByTitle = target.groupBy { normalizedTitle(it.title) }
            return withIndex().mapNotNull { (index, episode) ->
                val title = normalizedTitle(episode.title)
                val match = targetByTitle[title]?.singleOrNull()?.takeIf { isUsefulTitle(title) }
                    ?: target.getOrNull(index) ?: return@mapNotNull null
                episode to match
            }.toMap(linkedMapOf())
        }

        private fun normalizedTitle(title: String?): String =
            title.orEmpty().lowercase().replace(NON_ALPHANUMERIC, " ").trim().replace(COLLAPSED_SPACES, " ")

        private fun isUsefulTitle(title: String): Boolean = title.isNotBlank() && !GENERIC_TITLE.matches(title)

        private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
        private val COLLAPSED_SPACES = Regex("\\s+")
        private val GENERIC_TITLE = Regex("(episode|ep|e) \\d+")
    }
}

/** TMDB's episode list for a show, as MDBList numbers it. */
internal interface MdbListTmdbEpisodes {
    suspend fun showId(contentId: String): Long?
    suspend fun seasonCounts(showId: Long): Map<Int, Int>
    suspend fun episodes(showId: Long, season: Int): List<EpisodeMappingEntry>
}

internal object TmdbMdbListEpisodes : MdbListTmdbEpisodes {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun showId(contentId: String): Long? {
        val ids = parseTrackingExternalIds(contentId)
        ids.tmdb?.takeIf { it > 0 }?.let { return it }
        ids.imdb?.let { return TmdbService.ensureTmdbId(it, "tv")?.toLongOrNull() }
        ids.tvdb?.let { tvdb ->
            return fetch<TmdbFindDto>("find/$tvdb", mapOf("external_source" to "tvdb_id"))
                ?.tvResults?.firstOrNull()?.id
        }
        return null
    }

    override suspend fun seasonCounts(showId: Long): Map<Int, Int> =
        fetch<TmdbShowDto>("tv/$showId", mapOf("language" to LANGUAGE))?.seasons.orEmpty()
            .mapNotNull { season ->
                val number = season.seasonNumber?.takeIf { it > 0 } ?: return@mapNotNull null
                val count = season.episodeCount?.takeIf { it > 0 } ?: return@mapNotNull null
                number to count
            }
            .toMap()

    override suspend fun episodes(showId: Long, season: Int): List<EpisodeMappingEntry> =
        fetch<TmdbSeasonDto>("tv/$showId/season/$season", mapOf("language" to LANGUAGE))?.episodes.orEmpty()
            .mapNotNull { episode ->
                val number = episode.episodeNumber?.takeIf { it > 0 } ?: return@mapNotNull null
                EpisodeMappingEntry(season = season, episode = number, title = episode.name)
            }

    private suspend inline fun <reified T> fetch(endpoint: String, query: Map<String, String>): T? {
        val apiKey = TmdbSettingsRepository.effectiveApiKey().takeIf(String::isNotBlank) ?: return null
        return try {
            json.decodeFromString<T>(httpGetText(buildTmdbUrl(endpoint = endpoint, apiKey = apiKey, query = query)))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private const val LANGUAGE = "en-US"
}

@Serializable
private data class TmdbFindDto(@SerialName("tv_results") val tvResults: List<TmdbIdDto> = emptyList())

@Serializable
private data class TmdbIdDto(val id: Long)

@Serializable
private data class TmdbShowDto(val seasons: List<TmdbSeasonSummaryDto> = emptyList())

@Serializable
private data class TmdbSeasonSummaryDto(
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
)

@Serializable
private data class TmdbSeasonDto(val episodes: List<TmdbEpisodeDto> = emptyList())

@Serializable
private data class TmdbEpisodeDto(
    val name: String? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
)
