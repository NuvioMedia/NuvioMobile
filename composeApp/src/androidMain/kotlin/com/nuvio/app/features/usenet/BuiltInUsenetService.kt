package com.nuvio.app.features.usenet

import android.util.Log
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamBadge
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** The TMDB lookups a search makes; tests replace them. */
internal interface UsenetMetadataSource {
    suspend fun title(imdbId: String?, tmdbId: String?, videoId: String, series: Boolean): UsenetTitleMetadata
    suspend fun seasonEpisodes(tmdbId: String?, videoId: String): Map<Int, Int>?
}

internal object TmdbUsenetMetadataSource : UsenetMetadataSource {
    override suspend fun title(imdbId: String?, tmdbId: String?, videoId: String, series: Boolean) =
        UsenetMetadata.title(imdbId, tmdbId, videoId, series)

    override suspend fun seasonEpisodes(tmdbId: String?, videoId: String) =
        UsenetMetadata.seasonEpisodes(tmdbId, videoId)
}

/** The wording of results and failures, as format patterns; English where no translation is loaded. */
internal data class UsenetLabels(
    val indexerPaused: String = "%1\$s: indexer limit reached, paused until %2\$s.",
    val indexerFailed: String = "%1\$s: indexer search failed. Check the API URL, key and limits.",
    val sizeSeasonShare: String = "%1\$s whole season (~%2\$s per episode)",
    val sizePackShare: String = "%1\$s whole pack (~%2\$s per episode)",
    val sizeSeason: String = "%1\$s (whole season)",
    val sizePack: String = "%1\$s (whole pack)",
)

/** How a listed release, and its upload's other episodes, last went. */
private class ListedOutcome(val failed: Boolean, val played: Boolean, val sibling: SiblingOutcome?)

internal class BuiltInUsenetService(
    private val client: NewznabClient,
    private val metadata: UsenetMetadataSource = TmdbUsenetMetadataSource,
    private val unplayable: UnplayableReleases? = null,
    private val labels: suspend () -> UsenetLabels = { UsenetLabels() },
    /** Counts each indexer's searches; null in unit tests that do not look. */
    private val stats: UsenetSourceStats? = null,
) {
    fun search(config: UsenetSourceConfiguration, type: String, videoId: String,
        season: Int?, episode: Int?, groupName: String = BUILT_IN_USENET_GROUP_NAME,
        groupId: String = BUILT_IN_USENET_GROUP_ID): Flow<UsenetSearchUpdate> = flow {
        if (!config.ready || type.lowercase() !in BUILT_IN_USENET_TYPES) return@flow
        val text = labels()
        val series = type.lowercase() != "movie"
        val (idSeason, idEpisode) = episodeNumbers(videoId)
        val resolvedSeason = season ?: idSeason
        val resolvedEpisode = episode ?: idEpisode
        val baseImdb = videoId.substringBefore(':').takeIf { it.matches(Regex("tt\\d+")) }
        val baseTmdb = videoId.removePrefix("tmdb:").substringBefore(':')
            .takeIf { it.toIntOrNull() != null }
        val request = UsenetSearchRequest(baseImdb, baseTmdb, season = resolvedSeason,
            episode = resolvedEpisode, series = series)
        if (series && (resolvedSeason == null || resolvedSeason < 0 || resolvedEpisode == null || resolvedEpisode < 1)) return@flow
        coroutineScope {
            // A failed child would cancel this scope; keep lookup failures per indexer.
            val titleLookup = async(start = CoroutineStart.LAZY) {
                try {
                    metadata.title(request.imdbId, request.tmdbId, videoId, series).let { found ->
                        request.copy(imdbId = found.imdbId, tmdbId = found.tmdbId, tvdbId = found.tvdbId,
                            title = found.title, originalTitle = found.originalTitle,
                            otherTitles = found.otherTitles, year = found.year)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            }
            // A movie's ID results are checked against its names and year, asked for alongside the indexers.
            if (!series) titleLookup.start()
            val releases = mutableListOf<UsenetRelease>()
            val servers = config.servers()
            // Each season's episode count lets season packs be filtered and sorted by their size per
            // episode. It is asked of TMDB once the first pack shows up, and the list never waits for it.
            val seasonEpisodes = AtomicReference<Map<Int, Int>?>(null)
            val seasonLookup = if (!series) null else launch(start = CoroutineStart.LAZY) {
                val counts = withTimeoutOrNull(SEASON_LOOKUP_TIMEOUT_MS) {
                    try { metadata.seasonEpisodes(request.tmdbId, videoId) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                }
                seasonEpisodes.set(counts)
            }
            fun seasonCounts(): Map<Int, Int>? = seasonEpisodes.get()
            fun posted(release: UsenetRelease) = release.publishedAt.takeIf { it > 0 }
            // Each release's outcome is looked up once per list, by nzbUrl.
            fun outcomes(): Map<String, ListedOutcome> = releases.associate {
                val known = unplayable?.outcome(it.nzbUrl, it.title, it.size, servers, posted(it)) ?: ReleaseOutcome()
                it.nzbUrl to ListedOutcome(known.failed, known.played, unplayable?.sibling(it.title, servers, posted(it)))
            }
            // Releases that already failed for good with these providers go last, still selectable;
            // ones that played recently go first when chosen.
            fun arranged(outcomes: Map<String, ListedOutcome>): List<UsenetRelease> {
                val ranks = outcomes.mapValues { (_, it) ->
                    outcomeRank(it.failed || it.sibling?.blank == true, it.played, config.availableFirst)
                }
                val listed = if (config.hideDuplicates) {
                    NewznabProtocol.withoutDuplicates(releases, config, seasonEpisodes = seasonCounts(), series = series)
                } else {
                    releases
                }
                return NewznabProtocol.arrange(listed, config, seasonEpisodes = seasonCounts(), series = series) {
                    ranks[it.nzbUrl] ?: 1
                }
            }
            val enabled = config.indexers.filter { it.enabled }
            // Every indexer is searched at once unless the user chose priority fallback: then equal
            // priorities are searched together and lower ones only when nothing playable was found.
            val tiers = if (config.indexerFallback) enabled.groupBy { it.priority }.toSortedMap().values.toList() else listOf(enabled)
            for (tier in tiers) {
                val results = Channel<Pair<UsenetIndexer, Result<List<UsenetRelease>>>>(Channel.UNLIMITED)
                val jobs = tier.map { indexer ->
                    launch {
                        val result = try {
                            val caps = client.capabilities(indexer)
                            val canSearchId = NewznabProtocol.searchUrl(indexer, request, caps) != null
                            val search = if (canSearchId) request else titleLookup.await() ?: error("Metadata unavailable")
                            // Without the ids or title TMDB gives, such an indexer cannot be asked at all:
                            // that is a failure to show, not a search that found nothing.
                            if (!canSearchId && NewznabProtocol.searchUrl(indexer, search, caps) == null) {
                                error("Metadata unavailable")
                            }
                            val fetchLimit = config.indexerResults(indexer)
                            val found = client.search(indexer, search, caps, fetchLimit)
                                .let { if (canSearchId && !series) confirmed(indexer, it) { titleLookup.await() } else it }
                            Result.success(
                                if (found.isEmpty()) askAgain(indexer, search, caps, fetchLimit) { titleLookup.await() } ?: found
                                else found,
                            )
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { Result.failure(e) }
                        countSearch(indexer, if (series) ContentKind.SHOW else ContentKind.MOVIE, result)
                        results.send(indexer to result)
                    }
                }
                launch { jobs.joinAll(); results.close() }
                val tierStarted = System.currentTimeMillis()
                fun log(message: String) = runCatching {
                    Log.i("UsenetSearch", "${System.currentTimeMillis() - tierStarted}ms $message")
                }
                suspend fun show(loading: Boolean) {
                    val outcomes = outcomes()
                    val arranged = arranged(outcomes)
                    log("show ${arranged.size} results")
                    emit(UsenetSearchUpdate(group = AddonStreamGroup(groupName, groupId,
                        arranged.map {
                            val outcome = outcomes.getValue(it.nzbUrl)
                            it.toStream(config, series, groupName, groupId, text, outcome.failed, outcome.played,
                                pack = series && it.packSeasons.isNotEmpty(), seasonEpisodes = seasonCounts(),
                                sibling = outcome.sibling)
                        }, isLoading = loading)))
                }
                // The list appears whole once every indexer answered, unless one is slow: after a short
                // wait it shows what came and fills in from there. Progressive results show each answer.
                val wait = if (config.progressiveResults) null else async { delay(COMPLETE_RESULTS_WAIT_MS) }
                var waiting = wait != null
                var lookupShown = false
                while (true) {
                    val event = select<Any> {
                        results.onReceiveCatching { it }
                        if (waiting) wait?.onAwait { WAIT_OVER }
                        // Arranged again once the episode count came, if the list is already shown.
                        if (!lookupShown && seasonLookup?.isActive == true) seasonLookup.onJoin { SEASON_COUNTED }
                    }
                    if (event === WAIT_OVER) {
                        log("wait over")
                        waiting = false
                        show(loading = true)
                        continue
                    }
                    if (event === SEASON_COUNTED) {
                        lookupShown = true
                        if (!waiting && seasonEpisodes.get() != null) show(loading = true)
                        continue
                    }
                    @Suppress("UNCHECKED_CAST")
                    val received = event as ChannelResult<Pair<UsenetIndexer, Result<List<UsenetRelease>>>>
                    val (indexer, result) = received.getOrNull() ?: break
                    log("${indexer.name} answered: ${result.getOrNull()?.size ?: "failed"}")
                    if (result.isFailure) {
                        // Never display raw exception messages: network/parser errors can contain keys.
                        val cooldown = result.exceptionOrNull() as? IndexerCooldownException
                        emit(UsenetSearchUpdate(failure = if (cooldown != null) {
                            text.indexerPaused.format(indexer.name,
                                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(cooldown.until)))
                        } else {
                            text.indexerFailed.format(indexer.name)
                        }))
                    } else {
                        releases += result.getOrThrow()
                        if (releases.any { it.packSeasons.isNotEmpty() }) seasonLookup?.start()
                        if (!waiting) show(loading = true)
                    }
                }
                wait?.cancel()
                // Every indexer answered before the episode count: the list is shown, and arranged
                // again when the count comes within its few seconds.
                if (!lookupShown && seasonLookup?.isActive == true) {
                    show(loading = true)
                    seasonLookup.join()
                }
                val last = tier === tiers.last()
                val found = arranged(outcomes()).isNotEmpty()
                show(loading = !last && !found)
                // A lower priority is searched only when this one listed nothing; after the last, nothing is.
                if (last || found) break
            }
            titleLookup.cancel()
            seasonLookup?.cancel()
        }
    }

    /**
     * Asks again when a search found nothing: by title after an ID search (some indexers have thin
     * ID coverage), then by the original title, under which foreign releases are often posted.
     * Each attempt is made only when the ones before it found nothing. Null when none found anything.
     */
    private suspend fun askAgain(indexer: UsenetIndexer, searched: UsenetSearchRequest, caps: NewznabCapabilities,
        results: Int, metadata: suspend () -> UsenetSearchRequest?): List<UsenetRelease>? {
        val url = NewznabProtocol.searchUrl(indexer, searched, caps) ?: return null
        val byTitle = (metadata() ?: return null).copy(imdbId = null, tmdbId = null, tvdbId = null)
        val attempts = listOfNotNull(
            byTitle.takeIf { NewznabProtocol.isIdSearch(url) },
            // Indexers search Latin text; an original title in another script would find nothing.
            byTitle.originalTitle?.takeIf { NewznabProtocol.hasLatinLetter(it) }
                ?.let { byTitle.copy(title = it, originalTitle = byTitle.title) },
        )
        for (attempt in attempts) {
            if (NewznabProtocol.searchUrl(indexer, attempt, caps) == null) continue
            val found = try { client.search(indexer, attempt, caps, results) }
            catch (e: CancellationException) { throw e }
            catch (e: IndexerCooldownException) { throw e }
            // The first search succeeded; a failed later attempt only means nothing more was found.
            catch (_: Exception) { continue }
            if (found.isNotEmpty()) return found
        }
        return null
    }

    /**
     * A movie's ID results that its names and year confirm, once its metadata comes within
     * [ID_CHECK_WAIT_MS] of the indexer's answer; without it, the indexer's tags are trusted.
     */
    private suspend fun confirmed(indexer: UsenetIndexer, releases: List<UsenetRelease>,
        metadata: suspend () -> UsenetSearchRequest?): List<UsenetRelease> {
        if (releases.isEmpty()) return releases
        val movie = withTimeoutOrNull(ID_CHECK_WAIT_MS) { metadata() } ?: return releases
        return NewznabProtocol.idResultsOf(movie, releases).also { kept ->
            if (kept.size < releases.size) runCatching {
                Log.i("UsenetSearch", "${indexer.name}: ${releases.size - kept.size} ID results of another film")
            }
        }
    }

    private fun UsenetRelease.toStream(config: UsenetSourceConfiguration, series: Boolean, groupName: String,
        groupId: String, text: UsenetLabels, failed: Boolean = false, played: Boolean = false, pack: Boolean = false,
        seasonEpisodes: Map<Int, Int>? = null, sibling: SiblingOutcome? = null): StreamItem {
        fun gb(bytes: Long) = "%.2f GB".format(Locale.ROOT, bytes / (1024.0 * 1024 * 1024))
        // A pack's size is its seasons', not the episode's that plays: with their episode counts, the
        // size badge shows an episode's share and the details keep the whole size.
        val episodeShare = episodeSize(seasonEpisodes).takeIf { pack && it != size }
        val season = packSeasons.size == 1
        val sizeLabel = if (size <= 0) null else when {
            !pack -> gb(size)
            episodeShare != null && season -> text.sizeSeasonShare.format(gb(size), gb(episodeShare))
            episodeShare != null -> text.sizePackShare.format(gb(size), gb(episodeShare))
            season -> text.sizeSeason.format(gb(size))
            else -> text.sizePack.format(gb(size))
        }
        val age = if (publishedAt > 0) "${((System.currentTimeMillis() - publishedAt) / 86400000).coerceAtLeast(0)}d" else null
        val details = listOfNotNull(sizeLabel, age, indexerName).joinToString(" • ")
        val resolutionLabel = when {
            resolution >= 2160 -> "4K"
            resolution > 0 -> "${resolution}p"
            else -> null
        }
        return StreamItem(
            name = listOfNotNull(resolutionLabel, "Usenet", indexerName).joinToString(" • "),
            title = title,
            description = if (config.showReleaseNames) "$title\n$details" else details,
            sourceName = groupName,
            addonName = groupName,
            addonId = groupId,
            // The size badge, as other sources show it, and the upload's binge group.
            behaviorHints = StreamBehaviorHints(
                bingeGroup = bingeGroup(title, series),
                videoSize = (episodeShare ?: size).takeIf { it > 0 },
            ),
            badges = listOfNotNull(
                UNAVAILABLE_BADGE.takeIf { failed },
                AVAILABLE_BADGE.takeIf { played && !failed },
                sibling?.takeIf { !failed && !played }?.let(::siblingBadge),
                SEASON_PACK_BADGE.takeIf { pack },
                blurayLabel?.let { label(it, BLURAY_COLOR) },
            ),
            nzbUrl = nzbUrl,
            nzbServers = config.servers(),
            publishedAt = publishedAt.takeIf { it > 0 },
        )
    }

    /** One search of [indexer] in its statistics; its releases count for it once downloaded or played. */
    private fun countSearch(indexer: UsenetIndexer, kind: ContentKind, result: Result<List<UsenetRelease>>) {
        val stats = stats ?: return
        result.onSuccess { found ->
            stats.indexerSearch(indexer.id, kind, SearchOutcome.ANSWERED, found.size)
            stats.remember(found.map { it.nzbUrl }, indexer.id, kind)
        }.onFailure {
            stats.indexerSearch(indexer.id, kind, if (it is IndexerCooldownException) SearchOutcome.PAUSED else SearchOutcome.FAILED)
        }
    }

    companion object {
        private val WAIT_OVER = Any()
        private val SEASON_COUNTED = Any()
        /** How long a search may use for a season's episode count; the list never waits for it. */
        private const val SEASON_LOOKUP_TIMEOUT_MS = 3_000L
        /** How long a movie's ID results wait for its metadata before they are listed unchecked. */
        private const val ID_CHECK_WAIT_MS = 3_000L
        /** How long a search waits for every indexer before it shows what came. */
        const val COMPLETE_RESULTS_WAIT_MS = 5_000L
        private const val BLURAY_COLOR = "#0B5ED7"

        /**
         * One upload's episodes share a binge group, so the next episode plays from the same upload
         * when binge groups are preferred: its title without the episode's number and name. A
         * season pack is its own group. A movie's release is its own group too, so reusing the
         * binge group opens the same release again.
         */
        fun bingeGroup(title: String?, series: Boolean = true): String? {
            val name = title?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
            if (!series) return "$BINGE_PREFIX|$name"
            val upload = NewznabProtocol.episodeTemplate(name)?.first
                ?: name.takeIf { NewznabProtocol.packSeasons(it).isNotEmpty() } ?: return null
            return "$BINGE_PREFIX|$upload"
        }

        private const val BINGE_PREFIX = "Built-in Usenet"

        /**
         * A release played before, to open again without a search. Reuse Last Link keeps the
         * release, never the engine's session link, which ends with its session. The providers
         * and the indexer key are today's, so no credential is kept with it, and its binge group
         * is its upload's. Null when the sources are not ready or the link's indexer is gone.
         */
        fun restoredStream(
            nzbUrl: String,
            name: String,
            title: String?,
            size: Long?,
            publishedAt: Long?,
            config: UsenetSourceConfiguration,
            series: Boolean,
            groupName: String = BUILT_IN_USENET_GROUP_NAME,
            groupId: String = BUILT_IN_USENET_GROUP_ID,
        ): StreamItem? {
            if (!config.ready) return null
            val servers = config.servers().ifEmpty { return null }
            val download = UsenetKeptLinks.withApiKeys(nzbUrl, config) ?: return null
            return StreamItem(
                name = name,
                title = title,
                description = title,
                sourceName = groupName,
                addonName = groupName,
                addonId = groupId,
                behaviorHints = StreamBehaviorHints(bingeGroup = bingeGroup(title, series), videoSize = size?.takeIf { it > 0 }),
                nzbUrl = download,
                nzbServers = servers,
                publishedAt = publishedAt,
            )
        }

        private fun label(name: String, color: String) =
            StreamBadge(name = name, tagColor = color, textColor = "#FFFFFF", tagStyle = USENET_BADGE_STYLE_LABEL)

        private val UNAVAILABLE_BADGE = label(USENET_BADGE_UNAVAILABLE, "#6C757D")
        private val AVAILABLE_BADGE = label(USENET_BADGE_AVAILABLE, "#198754")
        private val SEASON_PACK_BADGE = label(USENET_BADGE_SEASON_PACK, "#6F42C1")
        private const val SIBLING_FAILED_COLOR = "#FD7E14"
        private const val SIBLING_PLAYED_COLOR = "#0AA2C0"

        fun siblingBadge(outcome: SiblingOutcome): StreamBadge =
            label("E%02d %s".format(Locale.ROOT, outcome.episode, if (outcome.failed) "FAILED" else "PLAYED"),
                if (outcome.failed) SIBLING_FAILED_COLOR else SIBLING_PLAYED_COLOR)

        /** Available releases first when chosen, unplayable ones last; the sort applies within each. */
        private fun outcomeRank(failed: Boolean, available: Boolean, availableFirst: Boolean) = when {
            failed -> 2
            available && availableFirst -> 0
            else -> 1
        }

        /**
         * Labels results already listed, cached or shown, by how their releases last went, and orders
         * the built-in group as a fresh search would: unplayable releases last, available ones first
         * when [availableFirst]. An untried release says how its upload's other episode went; one
         * whose other episode was blank goes last too. Other groups are unchanged.
         */
        fun arrangeOutcomes(groups: List<AddonStreamGroup>, groupId: String, unplayable: (StreamItem) -> Boolean,
            available: (StreamItem) -> Boolean = { false }, availableFirst: Boolean = false,
            sibling: (StreamItem) -> SiblingOutcome? = { null }): List<AddonStreamGroup> =
            groups.map { group ->
                if (group.addonId != groupId) return@map group
                val marked = group.streams.map { stream ->
                    if (!stream.isUsenet) return@map stream to 1
                    val failed = unplayable(stream)
                    val good = !failed && available(stream)
                    val other = if (failed || good) null else sibling(stream)
                    val others = stream.badges.filterNot {
                        it.name == USENET_BADGE_UNAVAILABLE || it.name == USENET_BADGE_AVAILABLE ||
                            USENET_SIBLING_BADGE.matches(it.name)
                    }
                    val badges = when {
                        failed -> listOf(UNAVAILABLE_BADGE) + others
                        good -> listOf(AVAILABLE_BADGE) + others
                        other != null -> listOf(siblingBadge(other)) + others
                        else -> others
                    }
                    (if (badges == stream.badges) stream else stream.copy(badges = badges)) to
                        outcomeRank(failed || other?.blank == true, good, availableFirst)
                }
                val streams = marked.sortedBy { it.second }.map { it.first }
                if (streams == group.streams) group else group.copy(streams = streams)
            }
    }
}
