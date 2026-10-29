package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamBadgeFilter
import com.nuvio.app.features.streams.StreamBadgeImport
import com.nuvio.app.features.streams.StreamBadgeMatcher
import com.nuvio.app.features.streams.StreamBadgeRules
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackMatchTextTest {
    private val filters = StreamBadgeMatcher.compile(
        StreamBadgeRules(
            imports = listOf(
                StreamBadgeImport(
                    sourceUrl = "https://example.com/badges.json",
                    filters = listOf(
                        filter("4K", "(?i)\\b(2160p|4k)\\b"),
                        filter("WebDL", "(?i)\\bweb[-. ]?dl\\b"),
                        filter("Vision", "(?i)\\b(dv|dovi|dolby[ .]?vision)\\b"),
                        filter("HDR", "(?i)\\bhdr(10)?\\b"),
                        filter("10bit", "(?i)\\b10[ -]?bit\\b"),
                        filter("HEVC", "(?i)\\b(hevc|[xh][ .]?265)\\b"),
                        filter("Atmos", "(?i)\\batmos\\b"),
                        filter("English", "(?i)\\b(eng|english)\\b"),
                    ),
                ),
            ),
        ),
    )

    private fun filter(name: String, pattern: String) =
        StreamBadgeFilter(name = name, pattern = pattern, imageURL = "https://example.com/$name.png")

    private fun matchedNames(info: PlayerMediaInfo, languages: List<String> = emptyList()): List<String> =
        StreamBadgeMatcher.matchedBadges(listOf(playbackMatchText(info, languages)), filters).map { it.name }

    @Test
    fun matchesWhatIsPlaying() {
        val info = PlayerMediaInfo(
            videoCodec = "HEVC",
            videoWidth = 3840,
            videoHeight = 1600,
            audioCodec = "E-AC-3-JOC",
            audioChannels = 6,
            hdr = listOf("DV", "HDR10"),
            bitDepth = 10,
        )
        assertEquals(
            listOf("4K", "Vision", "HDR", "10bit", "HEVC", "Atmos", "English"),
            matchedNames(info, listOf("eng")),
        )
    }

    @Test
    fun skipsBadgesThePlaybackDoesNotHave() {
        val info = PlayerMediaInfo(videoCodec = "AVC", videoWidth = 1920, videoHeight = 1080, audioCodec = "AAC", audioChannels = 2)
        assertEquals(emptyList(), matchedNames(info))
    }

    @Test
    fun readsAtmosFromMpvAudioProfile() {
        val info = PlayerMediaInfo(audioCodec = "TrueHD", audioProfile = "Dolby TrueHD + Dolby Atmos")
        assertEquals(listOf("Atmos"), matchedNames(info))
    }

    @Test
    fun usesThePackChosenForThePlayer() {
        val streamPack = StreamBadgeImport(
            sourceUrl = "https://example.com/stream.json",
            filters = listOf(filter("Stream4K", "\u2063\u200b\u2063")),
        )
        val playerPack = StreamBadgeImport(
            sourceUrl = "https://example.com/player.json",
            filters = listOf(filter("Player4K", "(?i)\\b2160p\\b")),
        )
        val rules = StreamBadgeRules(imports = listOf(streamPack, playerPack)).normalized()
        val text = playbackMatchText(PlayerMediaInfo(videoWidth = 3840, videoHeight = 2160), emptyList())
        fun namesFor(source: String) =
            StreamBadgeMatcher.matchedBadges(listOf(text), StreamBadgeMatcher.compile(rules.forPlayer(source))).map { it.name }

        assertEquals(emptyList(), namesFor(""))
        assertEquals(listOf("Player4K"), namesFor("https://example.com/player.json"))
        assertEquals(emptyList(), namesFor("https://example.com/removed.json"))
    }

    @Test
    fun showsNothingBeforePlaybackInfoLoads() {
        assertEquals(emptyList(), matchedNames(PlayerMediaInfo()))
    }
}
