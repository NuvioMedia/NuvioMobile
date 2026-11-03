package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QualityFirstAutoPlayPolicyTest {

    @Test
    fun `available qualities are unique and sorted highest first`() {
        val streams = listOf(
            stream("A", "https://a/1", "Episode 1080p"),
            stream("B", "https://b/1", "Episode 2160p"),
            stream("C", "https://c/1", "Episode 1080p WEB-DL"),
            stream("D", "https://d/1", "Episode 720p"),
        )

        assertEquals(
            listOf(
                StreamAutoPlayPreferredQuality.P2160,
                StreamAutoPlayPreferredQuality.P1080,
                StreamAutoPlayPreferredQuality.P720,
            ),
            QualityFirstAutoPlayPolicy.availableQualities(streams, debridEnabled = true),
        )
    }

    @Test
    fun `selected quality only includes exact resolution tier`() {
        val streams = listOf(
            stream("1080", "https://a/1080", "1080p"),
            stream("2160", "https://b/2160", "2160p"),
            stream("1440", "https://c/1440", "2K 1440p"),
            stream("720", "https://d/720", "720p"),
        )

        assertEquals(
            listOf("1440"),
            QualityFirstAutoPlayPolicy.rankCandidates(
                streams,
                StreamAutoPlayPreferredQuality.P1440,
                debridEnabled = true,
            ).map { it.addonName },
        )
    }

    @Test
    fun `selected quality candidates precede only lower quality fallbacks`() {
        val streams = listOf(
            stream("4k", "https://a/2160", "2160p"),
            stream("720-first", "https://b/720", "720p"),
            stream("1080-second", "https://c/1080-2", "1080p WEB-DL"),
            stream("1080-first", "https://d/1080-1", "1080p BluRay"),
            stream("480", "https://e/480", "480p"),
        )

        assertEquals(
            listOf("1080-first", "1080-second", "720-first", "480"),
            QualityFirstAutoPlayPolicy.rankCandidatesWithFallback(
                streams = streams,
                requestedQuality = StreamAutoPlayPreferredQuality.P1080,
                debridEnabled = true,
            ).map { it.addonName },
        )
    }

    @Test
    fun `selected quality fallback honours provider order within each tier`() {
        val streams = listOf(
            stream("second", "https://a/1080", "1080p"),
            stream("first", "https://b/1080", "1080p"),
            stream("lower", "https://c/720", "720p"),
        )

        assertEquals(
            listOf("first", "second", "lower"),
            QualityFirstAutoPlayPolicy.rankCandidatesWithFallback(
                streams = streams,
                requestedQuality = StreamAutoPlayPreferredQuality.P1080,
                debridEnabled = true,
                preferredSourceOrder = listOf("first", "second"),
            ).map { it.addonName },
        )
    }

    @Test
    fun `best available sorts by quality regardless of provider order`() {
        val streams = listOf(
            stream("slowFirst", "https://a/720", "720p"),
            stream("providerB", "https://b/2160", "2160p"),
            stream("providerC", "https://c/1080", "1080p"),
        )

        assertEquals(
            listOf("providerB", "providerC", "slowFirst"),
            QualityFirstAutoPlayPolicy.rankCandidates(
                streams,
                StreamAutoPlayPreferredQuality.ANY,
                debridEnabled = true,
            ).map { it.addonName },
        )
    }

    @Test
    fun `unknown quality is excluded from explicit quality selection`() {
        val streams = listOf(
            stream("unknown", "https://a/unknown", "Episode"),
            stream("720", "https://b/720", "Episode 720p"),
        )

        val ranked = QualityFirstAutoPlayPolicy.rankCandidates(
            streams,
            StreamAutoPlayPreferredQuality.P1080,
            debridEnabled = true,
        )
        assertEquals(emptyList(), ranked.map { it.addonName })
        assertTrue(ranked.all { it.playableDirectUrl != null })
    }

    @Test
    fun `ambiguous HD label is not counted as confirmed 720p`() {
        val streams = listOf(
            stream("ambiguous", "https://a/hd", "HD"),
            stream("known", "https://b/720", "720p"),
        )

        assertEquals(
            listOf(StreamAutoPlayPreferredQuality.P720),
            QualityFirstAutoPlayPolicy.availableQualities(streams, debridEnabled = true),
        )
        assertEquals(
            listOf("known"),
            QualityFirstAutoPlayPolicy.rankCandidates(
                streams,
                StreamAutoPlayPreferredQuality.P720,
                debridEnabled = true,
            ).map { it.addonName },
        )
    }

    @Test
    fun `duplicate direct URLs are removed`() {
        val streams = listOf(
            stream("A", "https://same/video.mp4", "1080p"),
            stream("B", "https://same/video.mp4", "1080p"),
            stream("C", "https://other/video.mp4", "720p"),
        )

        assertEquals(
            listOf("A", "C"),
            QualityFirstAutoPlayPolicy.rankCandidates(
                streams,
                StreamAutoPlayPreferredQuality.ANY,
                debridEnabled = true,
            ).map { it.addonName },
        )
    }

    @Test
    fun `best quality metadata wins when addons report the same direct URL`() {
        val lowerQuality = stream("A", "https://same/video.mp4", "Movie 720p")
        val higherQuality = stream("B", "https://same/video.mp4", "Movie 1080p")

        val ranked = QualityFirstAutoPlayPolicy.rankCandidates(
            listOf(lowerQuality, higherQuality),
            StreamAutoPlayPreferredQuality.ANY,
            debridEnabled = true,
        )

        assertEquals(listOf("B"), ranked.map { it.addonName })
        assertEquals(1080, QualityFirstAutoPlayPolicy.detectQualityHeight(ranked.single()))
    }

    @Test
    fun `same direct URL with different request headers remains distinct`() {
        val first = StreamItem(
            name = "Source A",
            url = "https://same/video.m3u8",
            addonName = "A",
            addonId = "addon:a",
            behaviorHints = StreamBehaviorHints(
                proxyHeaders = StreamProxyHeaders(request = mapOf("Referer" to "https://a.example")),
            ),
        )
        val second = first.copy(
            addonName = "B",
            addonId = "addon:b",
            behaviorHints = StreamBehaviorHints(
                proxyHeaders = StreamProxyHeaders(request = mapOf("Referer" to "https://b.example")),
            ),
        )

        val ranked = QualityFirstAutoPlayPolicy.rankCandidates(
            listOf(first, second),
            StreamAutoPlayPreferredQuality.ANY,
            debridEnabled = true,
        )

        assertEquals(listOf("A", "B"), ranked.map { it.addonName })
    }

    @Test
    fun `filename quality takes precedence over conflicting description metadata`() {
        val stream = StreamItem(
            name = "Movie 1080p BluRay",
            url = "https://media.example/movie.mkv",
            addonName = "provider",
            addonId = "addon:provider",
            behaviorHints = StreamBehaviorHints(filename = "Movie.1080p.BluRay.mkv"),
            description = "4K version also available",
        )

        assertEquals(1080, QualityFirstAutoPlayPolicy.detectQualityHeight(stream))
    }

    @Test
    fun `torrent candidates are excluded from fallback when p2p is disabled`() {
        val direct = stream("direct", "https://media.example/direct", "1080p")
        val torrent = StreamItem(
            name = "torrent",
            url = "https://media.example/torrent",
            infoHash = "0123456789abcdef0123456789abcdef01234567",
            addonName = "torrent",
            addonId = "addon:torrent",
            behaviorHints = StreamBehaviorHints(filename = "Movie.1080p.mkv"),
        )

        assertEquals(
            listOf("direct"),
            QualityFirstAutoPlayPolicy.rankCandidatesWithFallback(
                streams = listOf(torrent, direct),
                requestedQuality = StreamAutoPlayPreferredQuality.P1080,
                debridEnabled = true,
                p2pEnabled = false,
            ).map { it.addonName },
        )
        assertEquals(
            listOf("torrent", "direct"),
            QualityFirstAutoPlayPolicy.rankCandidatesWithFallback(
                streams = listOf(torrent, direct),
                requestedQuality = StreamAutoPlayPreferredQuality.P1080,
                debridEnabled = true,
                p2pEnabled = true,
            ).map { it.addonName },
        )
    }

    @Test
    fun `refreshing fallback keeps current stream and appends only untried new identities`() {
        val current = stream("current", "https://a/current", "Movie 1080p")
        val failed = stream("failed", "https://b/failed", "Movie 1080p")
        val discovered = stream("new", "https://c/new", "Movie 720p")
        val duplicateUrl = stream("duplicate", "https://c/new", "Movie 720p")

        val refreshed = QualityFirstAutoPlayPolicy.refreshedFallbackCandidates(
            current = current,
            discovered = listOf(current, failed, discovered, duplicateUrl),
            attemptedKeys = setOf(QualityFirstAutoPlayPolicy.candidateIdentityKey(failed)),
        )

        assertEquals(listOf("current", "new"), refreshed.map { it.addonName })
    }

    @Test
    fun `newly discovered candidate can restart an exhausted pending queue`() {
        val failed = stream("failed", "https://a/failed", "Movie 1080p")
        val discovered = stream("late", "https://b/late", "Movie 720p")

        val refreshed = QualityFirstAutoPlayPolicy.refreshedFallbackCandidates(
            current = null,
            discovered = listOf(failed, discovered),
            attemptedKeys = setOf(QualityFirstAutoPlayPolicy.candidateIdentityKey(failed)),
        )

        assertEquals(listOf("late"), refreshed.map { it.addonName })
    }

    @Test
    fun `external and YouTube streams keep distinct fallback identities`() {
        val externalA = StreamItem(
            name = "Open externally",
            externalUrl = "https://external.example/a",
            addonName = "provider",
            addonId = "addon:provider",
        )
        val externalB = externalA.copy(externalUrl = "https://external.example/b")
        val youtubeA = StreamItem(
            name = "YouTube",
            ytId = "video-a",
            addonName = "provider",
            addonId = "addon:provider",
        )
        val youtubeB = youtubeA.copy(ytId = "video-b")

        assertEquals(
            listOf("https://external.example/a", "https://external.example/b"),
            QualityFirstAutoPlayPolicy.rankCandidates(
                listOf(externalA, externalB),
                StreamAutoPlayPreferredQuality.ANY,
                debridEnabled = true,
            ).map { it.externalOpenUrl },
        )
        assertEquals(
            listOf("video-a", "video-b"),
            QualityFirstAutoPlayPolicy.rankCandidates(
                listOf(youtubeA, youtubeB),
                StreamAutoPlayPreferredQuality.ANY,
                debridEnabled = true,
            ).map { it.youTubeIdToResolve },
        )
    }

    @Test
    fun `debrid streams with different media ids are not deduplicated`() {
        fun debrid(mediaId: String) = StreamItem(
            name = "Cached source",
            addonName = "provider",
            addonId = "addon:provider",
            clientResolve = StreamClientResolve(
                type = "debrid",
                service = "realdebrid",
                mediaId = mediaId,
                isCached = true,
            ),
        )

        val ranked = QualityFirstAutoPlayPolicy.rankCandidates(
            listOf(debrid("media-a"), debrid("media-b")),
            StreamAutoPlayPreferredQuality.ANY,
            debridEnabled = true,
        )

        assertEquals(listOf("media-a", "media-b"), ranked.map { it.clientResolve?.mediaId })
    }

    private fun stream(addon: String, url: String?, name: String) = StreamItem(
        name = name,
        url = url,
        addonName = addon,
        addonId = "addon:${addon.lowercase()}",
    )
}
