package com.nuvio.app.features.usenet

import com.nuvio.app.features.usenet.StreamRankingEditing.moved
import com.nuvio.app.features.usenet.StreamRankingEditing.replaced
import com.nuvio.app.features.usenet.StreamRankingEditing.without
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StreamRankingEditingTest {
    private val plain = StreamRankingProfile(name = "Plain", sort = listOf(StreamRankingSort(StreamRankingKey.SIZE)))

    @Test
    fun aNewTierPutsQualityTierFirstWhenItIsMissing() {
        val edited = StreamRankingEditing.addTier(plain, "Tier")
        assertEquals(listOf(StreamRankingKey.TIER, StreamRankingKey.SIZE), edited.sort.map { it.key })
        assertEquals(listOf(StreamRankingTier("Tier")), edited.tiers)
        // A second tier keeps the priorities as they are.
        assertEquals(edited.sort, StreamRankingEditing.addTier(edited, "Tier 2").sort)
    }

    @Test
    fun aNewRuleAddsRuleScoreSecondAndStartsOnReleaseGroup() {
        val edited = StreamRankingEditing.addRule(StreamRankingEditing.addTier(plain, "Tier"), "Rule")
        assertEquals(listOf(StreamRankingKey.TIER, StreamRankingKey.SCORE, StreamRankingKey.SIZE), edited.sort.map { it.key })
        val rule = edited.rules.single()
        assertEquals(StreamRankingAction.BOOST, rule.action)
        assertEquals(StreamRankingField.GROUP, rule.conditions.single().field)
        assertEquals(StreamRankingOperator.IS, rule.conditions.single().operator)
    }

    @Test
    fun switchingARuleToAddScoreAddsRuleScoreWhenMissing() {
        val profile = plain.copy(rules = listOf(StreamRankingRule(action = StreamRankingAction.EXCLUDE)))
        val excluded = StreamRankingEditing.setRuleAction(profile, 0, StreamRankingAction.REQUIRE)
        assertEquals(listOf(StreamRankingKey.SIZE), excluded.sort.map { it.key })
        val boosted = StreamRankingEditing.setRuleAction(profile, 0, StreamRankingAction.BOOST)
        assertEquals(listOf(StreamRankingKey.SIZE, StreamRankingKey.SCORE), boosted.sort.map { it.key })
        assertEquals(StreamRankingAction.BOOST, boosted.rules.single().action)
    }

    @Test
    fun limitsMatchTheProfileValidation() {
        val full = plain.copy(
            tiers = List(StreamRankingEditing.MAX_TIERS) { StreamRankingTier("T$it") },
            rules = List(StreamRankingEditing.MAX_RULES) { StreamRankingRule() },
        )
        assertSame(full, StreamRankingEditing.addTier(full, "One more"))
        assertSame(full, StreamRankingEditing.addRule(full, "One more"))
        val rule = StreamRankingRule(conditions = List(StreamRankingEditing.MAX_CONDITIONS) { StreamRankingCondition(value = "x") })
        assertSame(rule, StreamRankingEditing.addCondition(rule))
    }

    @Test
    fun aNewConditionFieldGetsAnOperatorAndValueThatFitIt() {
        val text = StreamRankingCondition(StreamRankingField.TITLE, StreamRankingOperator.MATCHES, "x")
        assertEquals(StreamRankingCondition(StreamRankingField.SIZE_GB, StreamRankingOperator.AT_LEAST, "0"),
            StreamRankingEditing.withField(text, StreamRankingField.SIZE_GB))
        assertEquals(StreamRankingCondition(StreamRankingField.GROUP, StreamRankingOperator.IS, ""),
            StreamRankingEditing.withField(text, StreamRankingField.GROUP))
        assertSame(text, StreamRankingEditing.withField(text, StreamRankingField.TITLE))
        assertTrue(StreamRankingOperator.AT_MOST in StreamRankingEditing.operatorsFor(StreamRankingField.AGE_DAYS))
        assertTrue(StreamRankingOperator.MATCHES !in StreamRankingEditing.operatorsFor(StreamRankingField.RESOLUTION))
        assertTrue(StreamRankingOperator.AT_LEAST !in StreamRankingEditing.operatorsFor(StreamRankingField.GROUP))
    }

    @Test
    fun listEditsKeepTheirBounds() {
        val list = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), list.moved(1, -1))
        assertEquals(list, list.moved(0, -1))
        assertEquals(list, list.moved(2, 1))
        assertEquals(listOf("a", "x", "c"), list.replaced(1, "x"))
        assertEquals(listOf("a", "c"), list.without(1))
        assertEquals(list, list.without(5))
    }

    @Test
    fun onlyUnusedKeysCanBeAdded() {
        val unused = StreamRankingEditing.unusedKeys(plain)
        assertTrue(StreamRankingKey.SIZE !in unused)
        assertEquals(StreamRankingKey.entries.size - 1, unused.size)
        assertSame(plain, StreamRankingEditing.addPriority(plain, StreamRankingKey.SIZE))
        assertEquals(StreamRankingKey.AGE, StreamRankingEditing.addPriority(plain, StreamRankingKey.AGE).sort.last().key)
    }

    @Test
    fun editingStartsFromTheSortingInUse() {
        val custom = plain.copy(name = "Mine")
        assertEquals(custom, StreamRankingEditing.startingDraft(StreamRankingSettings(StreamRankingPreset.BALANCED, custom), UsenetSort.QUALITY))
        assertEquals(
            StreamRankingPresets.profile(StreamRankingPreset.SMALLER_FILES).copy(name = StreamRankingEditing.NEW_PROFILE_NAME),
            StreamRankingEditing.startingDraft(StreamRankingSettings(StreamRankingPreset.SMALLER_FILES), UsenetSort.QUALITY),
        )
        val original = StreamRankingEditing.startingDraft(StreamRankingSettings(), UsenetSort.NEWEST)
        assertEquals(StreamRankingEditing.NEW_PROFILE_NAME, original.name)
        assertEquals(StreamRankingSort(StreamRankingKey.AGE), original.sort.first())
        assertEquals(0, original.topPerBucket)
        original.validate()
    }

    @Test
    fun exportedProfilesImportUnchanged() {
        val profile = StreamRankingPresets.profile(StreamRankingPreset.BEST_QUALITY).copy(name = "Living room")
        val text = StreamRankingEditing.exportJson(profile)
        assertTrue(text.contains("\"format\": \"nuvio.stream-ranking\""))
        assertEquals(profile, StreamRankingEditing.importJson(text))
    }

    @Test
    fun aFileExportedByNuvioTvImports() {
        val tvExport = """
            {"format":"nuvio.stream-ranking","version":1,"profile":{"version":1,"name":"From TV",
             "tiers":[{"name":"4K remux","resolutions":[2160],"qualities":["REMUX"]}],
             "sort":[{"key":"TIER","descending":true},{"key":"SIZE","descending":false}],
             "preferredLanguages":["en"],"preferredMovieSizeGb":30.0,"preferredEpisodeSizeGb":5.0,"topPerBucket":3,
             "rules":[{"name":"Group","enabled":true,"action":"BOOST","score":300,"matchAny":false,
               "conditions":[{"field":"GROUP","operator":"IS","value":"FraMeSToR"}],"minimumRemaining":0}]}}
        """.trimIndent()
        val profile = StreamRankingEditing.importJson(tvExport)
        assertEquals("From TV", profile.name)
        assertEquals(StreamReleaseQuality.REMUX, profile.tiers.single().qualities.single())
        assertEquals(300, profile.rules.single().score)
    }

    @Test
    fun importRejectsOtherFilesAndInvalidProfiles() {
        assertFailsWith<IllegalArgumentException> { StreamRankingEditing.importJson("not json") }
        assertFailsWith<IllegalArgumentException> { StreamRankingEditing.importJson("""{"format":"other","version":1,"profile":{}}""") }
        assertFailsWith<IllegalArgumentException> {
            StreamRankingEditing.importJson("""{"format":"nuvio.stream-ranking","version":2,"profile":{}}""")
        }
        // A rule without conditions fails the profile validation.
        assertFailsWith<IllegalArgumentException> {
            StreamRankingEditing.importJson("""{"format":"nuvio.stream-ranking","version":1,"profile":{"rules":[{"name":"Empty"}]}}""")
        }
        assertFailsWith<IllegalArgumentException> { StreamRankingEditing.importJson("x".repeat(60_001)) }
    }

    @Test
    fun previewLinesReadNameSizeAgeAndIndexer() {
        val samples = StreamRankingEditing.parseSamples("Film.2160p.WEB-DL-FLUX | 10 | 3 | Indexer A\n\nFilm.1080p-X")
        assertEquals(RankingSample("Film.2160p.WEB-DL-FLUX", 10.0, 3.0, "Indexer A"), samples[0])
        assertEquals(RankingSample("Film.1080p-X"), samples[1])
        assertEquals(6, StreamRankingEditing.parseSamples(StreamRankingEditing.defaultSamples).size)
        assertFailsWith<IllegalArgumentException> { StreamRankingEditing.parseSamples("Film | big") }
        assertFailsWith<IllegalArgumentException> { StreamRankingEditing.parseSamples("  \n ") }
        assertFailsWith<IllegalArgumentException> { StreamRankingEditing.parseSamples(List(51) { "Film" }.joinToString("\n")) }
    }

    @Test
    fun languagesAreReadAsLowercaseCodesInOrder() {
        assertEquals(listOf("en", "ja"), StreamRankingEditing.parseLanguages(" EN, ja, "))
        assertEquals(emptyList(), StreamRankingEditing.parseLanguages(""))
    }
}
