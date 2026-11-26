package com.nuvio.app.features.audiosync

import com.nuvio.app.features.audiosync.asr.AsrTokens
import com.nuvio.app.features.autosync.AutoSyncSubtitleTextRetimer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyncModelTimeMappingTest {
    @Test
    fun syncModelMappingRetimesSrtAndHidesCutLines() {
        val body = """
            1
            00:00:10,000 --> 00:00:12,000
            Before the cut

            2
            00:00:20,000 --> 00:00:22,500
            After the cut
        """.trimIndent()
        val model = SubtitleSyncModel(
            listOf(
                SubtitleSyncSegment(fromMediaMs = 0L, scale = 1.0, shiftMs = 2_000.0),
                SubtitleSyncSegment(fromMediaMs = 15_000L, scale = 1.0, shiftMs = 5_000.0),
            ),
        )
        val retimed = assertNotNull(AutoSyncSubtitleTextRetimer.retime(body, SyncModelTimeMapping(model)))
        assertTrue("00:00:12,000 --> 00:00:14,000" in retimed, retimed)
        assertTrue("00:00:25,000 --> 00:00:27,500" in retimed, retimed)
        assertTrue("Before the cut" in retimed && "After the cut" in retimed)

        val cut = SubtitleSyncModel(
            listOf(
                SubtitleSyncSegment(fromMediaMs = 0L, scale = 1.0, shiftMs = 0.0),
                SubtitleSyncSegment(fromMediaMs = 15_000L, scale = 1.0, shiftMs = -10_000.0),
            ),
        )
        val mapping = SyncModelTimeMapping(cut)
        assertEquals(0L, mapping.mapStartMs(20_000L))
        assertEquals(1L, mapping.mapEndMs(21_000L))
        assertEquals(20_000L, mapping.mapStartMs(30_000L))
    }

    @Test
    fun tokensMergeIntoTimedWords() {
        val words = AsrTokens.merge(
            tokens = listOf("▁he", "llo", "▁wor", "ld"),
            timestamps = listOf(0.1, 0.2, 0.5, 0.6),
        )
        assertEquals(listOf(0.1 to "hello", 0.5 to "world"), words)
    }
}
