package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamBadgeFilter
import com.nuvio.app.features.streams.StreamBadgeImport
import com.nuvio.app.features.streams.StreamBadgeMatcher
import com.nuvio.app.features.streams.StreamBadgeRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        val info = PlayerMediaInfo(audioCodec = "TrueHD", audioProfiles = listOf("Dolby TrueHD + Dolby Atmos"))
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

    @Test
    fun dtsHdMaNotTriggeredByGermanLabel() {
        // mpvMediaInfo maps the codec-profile; audioTokens checks for \b(ma|master audio)\b.
        // "German" should not false-positive to MA.
        val info = mpvMediaInfo { name ->
            when (name) {
                "current-tracks/audio/codec" -> "dts"
                "current-tracks/audio/codec-profile" -> "DTS-HD HRA"
                "audio-params/channel-count" -> "8"
                else -> null
            }
        }
        val text = playbackMatchText(info, listOf("ger", "German"))
        assertFalse(text.contains("DTS-HD MA"), "DTS-HD MA should not appear for German DTS-HD HRA")
        assertTrue(text.contains("DTS-HD"), "DTS-HD should still appear")
    }

    @Test
    fun dtsHdMaAndDtsXBothAppear() {
        val info = PlayerMediaInfo(
            audioCodec = "DTS",
            audioProfiles = listOf("DTS-HD MA", "DTS:X"),
            audioChannels = 8,
        )
        val text = playbackMatchText(info, emptyList())
        assertTrue(text.contains("DTS-HD MA"), "DTS-HD MA should appear")
        assertTrue(text.contains("DTS:X"), "DTS:X should appear")
    }

    @Test
    fun pcmVariantsMapToPcmViaMpv() {
        val info = mpvMediaInfo { name ->
            when (name) {
                "current-tracks/audio/codec" -> "pcm_s24le"
                else -> null
            }
        }
        assertEquals("PCM", info.audioCodec)
    }

    @Test
    fun groupExclusionSkipsFilenameLangWhenDecoderMatches() {
        val langGroup = "Language"
        val sourceGroup = "Source"
        val playerFilters = StreamBadgeMatcher.compile(
            StreamBadgeRules(
                imports = listOf(
                    StreamBadgeImport(
                        sourceUrl = "https://example.com/badges.json",
                        filters = listOf(
                            StreamBadgeFilter(name = "English", pattern = "(?i)\\benglish\\b", imageURL = "eng.png", groupId = langGroup),
                            StreamBadgeFilter(name = "Polish", pattern = "(?i)\\bpol\\b", imageURL = "pol.png", groupId = langGroup),
                            StreamBadgeFilter(name = "WebDL", pattern = "(?i)\\bweb[-.]?dl\\b", imageURL = "webdl.png", groupId = sourceGroup),
                        ),
                    ),
                ),
            ),
        )
        val playback = "DD+ 5.1 English"
        val filename = listOf("Movie.POL.ENG.WebDL.mkv")
        val badges = StreamBadgeMatcher.matchedPlayerBadges(playback, filename, playerFilters).map { it.name }
        assertTrue("English" in badges, "English should appear (decoder match)")
        assertFalse("Polish" in badges, "Polish should NOT appear (group covered by decoder)")
        assertTrue("WebDL" in badges, "WebDL should appear (different group)")
    }

    @Test
    fun refineAddsAtmosFromFilenameWhenDecoderHasEac3() {
        val decoder = "DD+ DDP EAC3 5.1"
        val refined = refinePlaybackText(decoder, listOf("Movie.2160p.DDP5.1.Atmos.mkv"))
        assertTrue(refined.contains("Atmos"), "Atmos should be added")
    }

    @Test
    fun refineAddsDtsXFromFilenameWhenDecoderHasDtsHd() {
        val decoder = "DTS DTS-HD 7.1"
        val refined = refinePlaybackText(decoder, listOf("Movie.2160p.DTS-X.mkv"))
        assertTrue(refined.contains("DTS:X"), "DTS:X should be added")
    }

    @Test
    fun refineDoesNotAddAtmosWithoutMatchingBaseCodec() {
        val decoder = "AAC 2.0"
        val refined = refinePlaybackText(decoder, listOf("Movie.Atmos.mkv"))
        assertFalse(refined.contains("Atmos"), "Atmos should NOT be added without EAC3/TrueHD")
    }

    @Test
    fun orderRestoredToPackFilterOrderWithSharedImageUrl() {
        val filters = StreamBadgeMatcher.compile(
            StreamBadgeRules(
                imports = listOf(
                    StreamBadgeImport(
                        sourceUrl = "https://example.com/badges.json",
                        filters = listOf(
                            StreamBadgeFilter(name = "Remux", pattern = "(?i)\\bremux\\b", imageURL = "remux.png"),
                            StreamBadgeFilter(name = "HEVC", pattern = "(?i)\\bhevc\\b", imageURL = "hevc.png"),
                            StreamBadgeFilter(name = "HEVC-alt", pattern = "(?i)\\bh\\.?265\\b", imageURL = "hevc.png"),
                        ),
                    ),
                ),
            ),
        )
        val badges = StreamBadgeMatcher.matchedPlayerBadges("HEVC 2160p", listOf("Movie.Remux.mkv"), filters)
        assertEquals("Remux", badges[0].name)
        assertEquals("HEVC", badges[1].name)
    }
}
