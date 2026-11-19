package com.nuvio.app.features.usenet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RankingPreviewerTest {
    @Test
    fun aPreviewSortsAsTheTvPhoneEditorDoes() {
        // The case NuvioTV's ranking endpoint test checks.
        val profile = StreamRankingPresets.profile(StreamRankingPreset.BEST_QUALITY).copy(name = "Living room")
        val preview = RankingPreviewer.preview(
            profile,
            listOf(RankingSample("Film.2160p.WEB-DL-FLUX", 10.0), RankingSample("Film.1080p.BluRay.REMUX-CiNEPHiLES", 20.0)),
            mediaType = "movie",
        )
        assertTrue(preview.results.first().title.contains("1080p"))
        assertEquals(300, preview.results.first().score)
        assertEquals("1080p remux", preview.results.first().tier)
        assertTrue(preview.excluded.isEmpty())
    }

    @Test
    fun filtersListWhatTheyLeaveOut() {
        val profile = StreamRankingProfile(
            name = "Only remux",
            rules = listOf(
                StreamRankingRule(
                    name = "Remux only",
                    action = StreamRankingAction.REQUIRE,
                    conditions = listOf(StreamRankingCondition(StreamRankingField.QUALITY, StreamRankingOperator.IS, "REMUX")),
                ),
            ),
        )
        val preview = RankingPreviewer.preview(
            profile,
            StreamRankingEditing.parseSamples(StreamRankingEditing.defaultSamples),
            mediaType = "movie",
        )
        assertEquals(2, preview.results.size)
        assertTrue(preview.results.all { "REMUX" in it.title })
        assertEquals(4, preview.excluded.size)
    }

    @Test
    fun indexerOrderAndSizesReachTheEngine() {
        val profile = StreamRankingProfile(name = "By indexer", sort = listOf(StreamRankingSort(StreamRankingKey.INDEXER)))
        val preview = RankingPreviewer.preview(
            profile,
            listOf(RankingSample("A.1080p-X", indexer = "Second"), RankingSample("B.1080p-Y", indexer = "First")),
            mediaType = "series",
            indexerOrder = mapOf("first" to 0, "second" to 1),
        )
        assertEquals(listOf("B.1080p-Y", "A.1080p-X"), preview.results.map { it.title })
    }

    @Test
    fun anInvalidProfileIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            RankingPreviewer.preview(StreamRankingProfile(version = 2), listOf(RankingSample("Film")), "movie")
        }
        assertFailsWith<IllegalArgumentException> {
            RankingPreviewer.preview(StreamRankingProfile(), listOf(RankingSample("Film")), "channel")
        }
    }
}
