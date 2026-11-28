package com.nuvio.app.features.usenet

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class StreamRankingEngineTest {
    private fun candidate(title: String, sizeGb: Double? = null, key: String = title, reliability: Int = 1) =
        StreamRankingCandidate(title, StreamRankingFacts.parse(title).copy(sizeBytes = sizeGb?.times(StreamRankingEngine.GIB)?.toLong()), key, reliability)
    private fun sorted(preset: StreamRankingPreset, vararg values: StreamRankingCandidate<String>) =
        StreamRankingEngine(StreamRankingPresets.profile(preset)).rank(values.toList()).map { it.candidate.value }
    private fun condition(field: StreamRankingField, value: String, op: StreamRankingOperator = StreamRankingOperator.IS) =
        StreamRankingCondition(field, op, value)

    @Test fun `every preset is valid and survives native JSON roundtrip`() {
        for (preset in listOf(StreamRankingPreset.BALANCED, StreamRankingPreset.BEST_QUALITY, StreamRankingPreset.SMALLER_FILES, StreamRankingPreset.HD_SDR)) {
            val profile = StreamRankingPresets.profile(preset)
            profile.validate()
            val settings = StreamRankingSettings(preset, profile)
            assertEquals(settings, Json.decodeFromString<StreamRankingSettings>(Json.encodeToString(settings)))
        }
        assertNull(StreamRankingSettings().profile())
        assertThrows(IllegalArgumentException::class.java) { StreamRankingSettings(StreamRankingPreset.CUSTOM).validate() }
    }

    @Test fun `release facts recognize source codec group HDR audio and language`() {
        val facts = StreamRankingFacts.parse("Film.2025.2160p.UHD.BluRay.REMUX.HEVC.DV.HDR10+.TrueHD.Atmos.English-FraMeSToR.mkv")
        assertEquals(2160, facts.resolution)
        assertEquals(StreamReleaseQuality.REMUX, facts.quality)
        assertEquals("HEVC", facts.codec)
        assertEquals("FraMeSToR", facts.group)
        assertTrue(facts.visual.containsAll(listOf("HDR_DV", "HDR10_PLUS")))
        assertFalse("DV_ONLY" in facts.visual)
        assertTrue(facts.audio.containsAll(listOf("TRUEHD", "ATMOS")))
        assertEquals(setOf("en"), facts.languages)
        assertEquals("D-Z0N3", StreamRankingFacts.parse("Film.1080p.BluRay.x264-D-Z0N3").group)
        assertEquals("SubsPlease", StreamRankingFacts.parse("[SubsPlease] Show - 01 (1080p) [AABB1122]").group)
        assertEquals("NTb", StreamRankingFacts.parse("Film.WEB-DL-NTb").group)
        assertTrue("DV_ONLY" in StreamRankingFacts.parse("Film.2160p.WEB-DL.DV-FLUX").visual)
        assertEquals(StreamReleaseQuality.UNKNOWN, StreamRankingFacts.parse("obfuscated.release").quality)
        // A group or extension ends the name: a line break after it is not the end.
        assertEquals("", StreamRankingFacts.parse("Film.WEB-DL-NTb\n").group)
        assertEquals("", StreamRankingFacts.parse("Film.WEB-DL.mkv\n").group)
    }

    @Test fun `best quality prefers 1080p remux above 4K web and 4K remux above both`() {
        val web = candidate("Film.2160p.WEB-DL-FLUX", 15.0)
        val fhd = candidate("Film.1080p.BluRay.REMUX-CiNEPHiLES", 25.0)
        val uhd = candidate("Film.2160p.BluRay.REMUX-FraMeSToR", 70.0)
        assertEquals(listOf(uhd.value, fhd.value, web.value), sorted(StreamRankingPreset.BEST_QUALITY, web, fhd, uhd))
    }

    @Test fun `balanced size preferences keep large alternatives below and never remove unknowns`() {
        val large = candidate("Film.2160p.BluRay.REMUX-FraMeSToR", 70.0)
        val web = candidate("Film.2160p.WEB-DL-FLUX", 15.0)
        val unknown = candidate("Unlabelled release")
        val ranked = sorted(StreamRankingPreset.BALANCED, large, unknown, web)
        assertEquals(web.value, ranked.first())
        assertTrue(large.value in ranked)
        assertTrue(unknown.value in ranked)
    }

    @Test fun `smaller files prefers efficient 1080p WEB and keeps remux alternatives`() {
        val remux = candidate("Film.2160p.BluRay.REMUX-FraMeSToR", 70.0)
        val uhd = candidate("Film.2160p.WEB-DL-FLUX", 8.0)
        val avc = candidate("Film.1080p.WEB-DL.x264-NTb", 4.0)
        val hevc = candidate("Film.1080p.WEB-DL.x265-NTb", 3.0)
        assertEquals(listOf(hevc.value, avc.value, uhd.value, remux.value), sorted(StreamRankingPreset.SMALLER_FILES, remux, uhd, avc, hevc))
    }

    @Test fun `release group scores are source aware and do not blacklist unknown groups`() {
        val namedWeb = candidate("Film.2160p.WEB-DL-fLuX", 10.0)
        val unknown = candidate("Film.2160p.WEB-DL-Other", 12.0)
        val namedRemux = candidate("Film.2160p.BluRay.REMUX-FLUX", 50.0)
        val results = StreamRankingEngine(StreamRankingPresets.profile(StreamRankingPreset.BEST_QUALITY))
            .rank(listOf(unknown, namedWeb, namedRemux))
        assertEquals(300, results.single { it.candidate == namedWeb }.score)
        assertEquals(0, results.single { it.candidate == namedRemux }.score)
        assertEquals(3, results.size)
    }

    @Test fun `scoring accumulates all matches and can move across quality tiers when requested`() {
        val profile = StreamRankingPresets.profile(StreamRankingPreset.BEST_QUALITY).copy(topPerBucket = 0,
            sort = listOf(StreamRankingSort(StreamRankingKey.SCORE), StreamRankingSort(StreamRankingKey.TIER)),
            rules = listOf(
                StreamRankingRule("group", score = 500, conditions = listOf(condition(StreamRankingField.GROUP, "Preferred"))),
                StreamRankingRule("codec", score = 100, conditions = listOf(condition(StreamRankingField.CODEC, "HEVC")))
            ))
        val preferred = candidate("Film.1080p.WEB-DL.x265-Preferred")
        val results = StreamRankingEngine(profile).rank(listOf(candidate("Film.2160p.BluRay.REMUX-Other"), preferred))
        assertEquals(preferred, results.first().candidate)
        assertEquals(600, results.first().score)
        assertEquals(listOf("group", "codec"), results.first().matches)
    }

    @Test fun `filters can retain scarce alternatives while strict requirements remain strict`() {
        val hd = candidate("Film.1080p.WEB-DL-A")
        val sd = candidate("Film.480p.WEBRip-B")
        val filter = StreamRankingRule("Prefer HD when enough remain", action = StreamRankingAction.EXCLUDE, minimumRemaining = 2,
            conditions = listOf(condition(StreamRankingField.RESOLUTION, "720", StreamRankingOperator.AT_MOST)))
        val profile = StreamRankingProfile(rules = listOf(filter), topPerBucket = 0)
        assertEquals(2, StreamRankingEngine(profile).rank(listOf(hd, sd)).size)
        assertEquals(listOf(hd), StreamRankingEngine(profile.copy(rules = listOf(filter.copy(minimumRemaining = 0)))).rank(listOf(hd, sd)).map { it.candidate })
        val require = filter.copy(action = StreamRankingAction.REQUIRE, minimumRemaining = 0,
            conditions = listOf(condition(StreamRankingField.LANGUAGE, "en")))
        assertTrue(StreamRankingEngine(profile.copy(rules = listOf(require))).rank(listOf(hd, sd)).isEmpty())
    }

    @Test fun `conditions combine media type title regex and numeric metadata without matching missing numbers`() {
        val rule = StreamRankingRule("Small episodes", score = 200, conditions = listOf(
            condition(StreamRankingField.MEDIA_TYPE, "series"), condition(StreamRankingField.TITLE, "S[0-9]+E[0-9]+", StreamRankingOperator.MATCHES),
            condition(StreamRankingField.SIZE_GB, "2", StreamRankingOperator.AT_MOST)))
        val profile = StreamRankingProfile(rules = listOf(rule), topPerBucket = 0)
        val movie = candidate("Show.S01E02.1080p.WEB-DL-NTb", 1.0)
        val episode = movie.copy(facts = movie.facts.copy(mediaType = "series"))
        val unknown = episode.copy(facts = episode.facts.copy(sizeBytes = null))
        val engine = StreamRankingEngine(profile)
        assertEquals(0, engine.rank(listOf(movie)).single().score)
        assertEquals(200, engine.rank(listOf(episode)).single().score)
        assertEquals(0, engine.rank(listOf(unknown)).single().score)
        assertEquals(200, StreamRankingEngine(profile.copy(rules = listOf(rule.copy(matchAny = true)))).rank(listOf(movie)).single().score)
    }

    @Test fun `known failures stay last even with huge scores and variety limits`() {
        val a = candidate("Film.1080p.WEB-DL-A", reliability = 1)
        val b = candidate("Film.1080p.WEB-DL-B", reliability = 1)
        val failed = candidate("Film.2160p.BluRay.REMUX-Preferred", reliability = 2)
        val profile = StreamRankingProfile(topPerBucket = 1, rules = listOf(StreamRankingRule("boost", score = 10000,
            conditions = listOf(condition(StreamRankingField.GROUP, "Preferred")))))
        val results = StreamRankingEngine(profile).rank(listOf(failed, b, a))
        assertEquals(failed, results.last().candidate)
        assertEquals(3, results.size)
        assertTrue(results.single { it.candidate == b }.overflow)
    }

    @Test fun `diversity retains all extra releases and ordering is independent of arrival`() {
        val candidates = (1..6).map { candidate("Film.2160p.WEB-DL-G$it", it.toDouble()) } + candidate("Film.1080p.WEB-DL-Other", 1.0)
        val engine = StreamRankingEngine(StreamRankingPresets.profile(StreamRankingPreset.BEST_QUALITY).copy(topPerBucket = 2))
        val forward = engine.rank(candidates)
        assertEquals(forward, engine.rank(candidates.reversed()))
        assertEquals(candidates.size, forward.size)
        assertEquals(4, forward.count { it.overflow })
        assertEquals(1080, forward[2].candidate.facts.resolution)
    }

    @Test fun `unknown sizes stay last for either size direction`() {
        val missing = candidate("Missing")
        val small = candidate("Small", 1.0)
        val large = candidate("Large", 10.0)
        for (desc in listOf(false, true)) {
            val result = StreamRankingEngine(StreamRankingProfile(sort = listOf(StreamRankingSort(StreamRankingKey.SIZE, desc)), topPerBucket = 0))
                .rank(listOf(missing, large, small))
            assertEquals(missing, result.last().candidate)
            assertEquals(if (desc) large else small, result.first().candidate)
        }
    }

    @Test fun `preferred languages are optional and follow the configured priority`() {
        val english = candidate("Film.1080p.WEB-DL.English-G")
        val japanese = candidate("Film.1080p.WEB-DL.Japanese-G")
        val unknown = candidate("Film.1080p.WEB-DL-G")
        val profile = StreamRankingProfile(preferredLanguages = listOf("ja", "en"), topPerBucket = 0,
            sort = listOf(StreamRankingSort(StreamRankingKey.LANGUAGE)))
        assertEquals(listOf(japanese, english, unknown), StreamRankingEngine(profile).rank(listOf(unknown, english, japanese)).map { it.candidate })
    }

    @Test fun `unsafe regex and unsupported operators are rejected before activation`() {
        for (regex in listOf("(?<=x)y", "(x)\\1", "[")) {
            assertThrows(IllegalArgumentException::class.java) {
                StreamRankingProfile(rules = listOf(StreamRankingRule(conditions = listOf(condition(StreamRankingField.TITLE, regex, StreamRankingOperator.MATCHES))))).validate()
            }
        }
        assertThrows(IllegalArgumentException::class.java) { condition(StreamRankingField.SIZE_GB, "NaN", StreamRankingOperator.AT_LEAST).validate() }
        assertThrows(IllegalArgumentException::class.java) { condition(StreamRankingField.TITLE, "3", StreamRankingOperator.AT_LEAST).validate() }
        assertThrows(IllegalArgumentException::class.java) { StreamRankingProfile(sort = listOf(StreamRankingSort(StreamRankingKey.SIZE), StreamRankingSort(StreamRankingKey.SIZE))).validate() }
        assertThrows(IllegalArgumentException::class.java) { StreamRankingProfile(rules = List(81) { StreamRankingRule() }).validate() }
    }

    @Test(timeout = 2000) fun `nested quantifiers use bounded matching on long input`() {
        val profile = StreamRankingProfile(rules = listOf(StreamRankingRule(conditions = listOf(
            condition(StreamRankingField.TITLE, "(a+)+b", StreamRankingOperator.MATCHES)))))
        val facts = candidate("a".repeat(2048))
        assertEquals(0, StreamRankingEngine(profile).rank(listOf(facts)).single().score)
    }
    @Test fun `known failed releases do not make a conditional filter discard the last eligible alternative`() {
        val rare = candidate("Film.1080p.WEB-DL-YTS")
        val failed = (1..5).map { candidate("Film.$it.1080p.WEB-DL-NTb", reliability = 2) }
        val engine = StreamRankingEngine(StreamRankingPresets.profile(StreamRankingPreset.BALANCED))
        assertTrue(engine.rank(listOf(rare) + failed).any { it.candidate == rare })
    }

    @Test fun `presets retain scarce compressed releases but prefer alternatives when enough remain`() {
        val compressed = candidate("Film.1080p.WEB-DL-YTS")
        val alternatives = (1..5).map { candidate("Film.$it.1080p.WEB-DL-NTb") }
        val engine = StreamRankingEngine(StreamRankingPresets.profile(StreamRankingPreset.BEST_QUALITY))
        assertEquals(compressed, engine.rank(alternatives.take(4) + compressed).last().candidate)
        assertFalse(engine.rank(alternatives + compressed).any { it.candidate == compressed })
    }

    @Test fun `explicit language metadata works without a filename language tag`() {
        val facts = StreamRankingFacts.parse("Film.1080p.WEB-DL-NTb").copy(knownLanguages = setOf("en"))
        assertEquals(setOf("en"), facts.languages)
        assertEquals(setOf("en"), facts.copy(sizeBytes = 123L).languages)
    }

    @Test fun `1080p SDR preset promotes unlabelled HD and keeps HDR and UHD alternatives`() {
        val hdr = candidate("Film.1080p.WEB-DL.HDR.HEVC-FLUX", 6.0)
        val dv = candidate("Film.1080p.WEB-DL.DV.HEVC-NTb", 5.0)
        val uhd = candidate("Film.2160p.BluRay.REMUX.HDR-FraMeSToR", 60.0)
        val sdr = candidate("Film.1080p.WEB-DL.x264.AAC-Other", 4.0)
        val efficient = candidate("Film.1080p.WEB-DL.x265-Other", 4.0)
        val low = candidate("Film.720p.WEB-DL.x264-Other", 1.0)
        val unknown = candidate("Unlabelled release")
        val result = sorted(StreamRankingPreset.HD_SDR, hdr, dv, uhd, efficient, unknown, low, sdr)
        assertEquals(sdr.value, result.first())
        assertTrue(result.indexOf(low.value) < result.indexOf(hdr.value))
        assertTrue(result.containsAll(listOf(hdr.value, dv.value, uhd.value, unknown.value)))
    }

    @Test fun `quick language preference precedes quality while preserving original and custom sorting`() {
        val english = candidate("Film.2160p.BluRay.REMUX.English-FraMeSToR", 60.0)
        val dutch = candidate("Film.1080p.WEB-DL.Dutch-Other", 2.0)
        val unknown = candidate("Film.1080p.WEB-DL-NTb", 3.0)
        val settings = StreamRankingSettings(StreamRankingPreset.BEST_QUALITY, preferredLanguage = "nl")
        settings.validate()
        assertEquals(settings, Json.decodeFromString<StreamRankingSettings>(Json.encodeToString(settings)))
        val result = StreamRankingEngine(settings.profile()!!).rank(listOf(english, unknown, dutch))
        assertEquals(dutch, result.first().candidate)
        assertEquals(3, result.size)
        val custom = StreamRankingProfile(preferredLanguages = listOf("ja"))
        assertEquals(custom, settings.copy(preset = StreamRankingPreset.CUSTOM, custom = custom).profile())
        assertNull(settings.copy(preset = StreamRankingPreset.ORIGINAL).profile())
        assertEquals(StreamRankingPresets.profile(StreamRankingPreset.BEST_QUALITY), settings.copy(preferredLanguage = "").profile())
        assertThrows(IllegalArgumentException::class.java) { settings.copy(preferredLanguage = "invalid").validate() }
    }

    @Test fun `plain WEB releases have a quality tier without confusing WEBRip or file extensions`() {
        assertEquals(StreamReleaseQuality.WEB_DL, StreamRankingFacts.parse("Film.1080p.AMZN.WEB.H264-GROUP").quality)
        assertEquals(StreamReleaseQuality.WEBRIP, StreamRankingFacts.parse("Film.1080p.WEB-Rip-GROUP").quality)
        assertEquals(StreamReleaseQuality.HDTV, StreamRankingFacts.parse("Web.Of.Lies.1080p.HDTV-GROUP").quality)
        val transport = StreamRankingFacts.parse("Film.1080p.WEB-DL-GROUP.ts")
        assertEquals(StreamReleaseQuality.WEB_DL, transport.quality)
        assertEquals("GROUP", transport.group)
        assertEquals("Film.1080p.WEB-DL-GROUP.ts", transport.title)
        assertTrue("NO_HDR_LABEL" in transport.visual)
        assertFalse("NO_HDR_LABEL" in StreamRankingFacts.parse("Film.1080p.WEB-DL.DV-GROUP").visual)
    }

}
