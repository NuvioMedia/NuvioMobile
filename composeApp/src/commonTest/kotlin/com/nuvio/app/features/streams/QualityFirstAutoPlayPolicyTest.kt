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
    fun `selected quality only includes exact resolution tier` {
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

    private fun stream(addon: String, url: String?, name: String) = StreamItem(
        name = name,
        url = url,
        addonName = addon,
        addonId = "addon:${addon.lowercase()}",
    )
}
