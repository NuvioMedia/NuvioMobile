package com.nuvio.app.features.usenet

import java.util.Locale

/** Sorts sample release names with the same engine as real results, for the custom sorting editor. */
internal object RankingPreviewer {
    fun preview(
        profile: StreamRankingProfile,
        samples: List<RankingSample>,
        mediaType: String,
        indexerOrder: Map<String, Int> = emptyMap(),
        now: Long = System.currentTimeMillis(),
    ): RankingPreview {
        profile.validate()
        require(mediaType == "movie" || mediaType == "series") { "Choose movies or episodes" }
        val candidates = samples.mapIndexed { index, sample ->
            val facts = StreamRankingFacts.parse(sample.title).copy(
                sizeBytes = sample.sizeGb?.takeIf { it > 0 }?.times(StreamRankingEngine.GIB)?.toLong(),
                publishedAt = sample.ageDays?.let { now - (it * 86_400_000).toLong() },
                indexer = sample.indexer,
                mediaType = mediaType,
            )
            StreamRankingCandidate(
                value = index,
                facts = facts,
                key = index.toString(),
                indexerOrder = indexerOrder[sample.indexer.lowercase(Locale.ROOT)] ?: Int.MAX_VALUE,
            )
        }
        val results = StreamRankingEngines.get(profile).rank(candidates, now)
        val kept = results.map { it.candidate.value }.toSet()
        return RankingPreview(
            results = results.map { RankingPreviewRow(it.candidate.facts.title, it.score, it.tier, it.matches, it.overflow) },
            excluded = samples.filterIndexed { index, _ -> index !in kept }.map { it.title },
        )
    }
}
