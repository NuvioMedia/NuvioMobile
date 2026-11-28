package com.nuvio.app.features.usenet

import com.nuvio.app.features.usenet.UsenetStartupReport.NzbLookup
import com.nuvio.app.features.usenet.UsenetStartupReport.NzbWrite
import com.nuvio.app.features.usenet.UsenetStartupReport.Prefetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsenetStartupReportTest {
    // The shape the sidecar keeps: its own marks and the engine's diagnostics, as on NuvioTV.
    private val report = """
        {"fastMkvStartup":true,"fastNzbFetch":true,"recordedAtMs":1763580000000,
         "marksMs":{"resolve_lock_acquired":0,"sidecar_reused":1,"engine_ready":1,"session_request":1,
                    "session_response":957,"resolved":957,"prepare":1010,"player_ready":5200,
                    "decoder_init_duration":42,"first_frame":5986},
         "engine":{"marksMs":{"provider_setup_started":4.4,"pool_created":6.5,"nzb_cache_lookup_started":6.6,
                              "nzb_cache_lookup_finished":12.5,"nzb_loaded":12.5,"first_nntp_request":24.0,
                              "first_nntp_metadata":843.1,"content_selected":941.0,"warmup_started":941.5,
                              "head_article_ready":3402.7,"tail_article_ready":3473.9,"first_media_bytes":2630.2},
                   "durationsMs":{"nzb_body_read":120.5,"nzb_parse_work":30.25,"nzb_cache_io":4},
                   "nzbCache":{"lookup":"hit","write":"skipped","bytes":2097152},
                   "ranges":[{"startMs":1000,"firstByteMs":1500},{"startMs":2000,"firstByteMs":2100},{"startMs":3000,"firstByteMs":-1}],
                   "store":{"cacheHits":12,"sharedJoins":3,"downloads":40,"cancellations":2},
                   "archiveDiscoveryMs":0,"articleSlabsBytes":16777216}}
    """.trimIndent()

    @Test
    fun aReportReadsAsTheTvCardsShowIt() {
        val parsed = UsenetStartupReport.parse(report)!!
        assertEquals(1763580000000, parsed.recordedAtMs)
        assertTrue(parsed.fastMkvStartup)
        assertEquals(5986.0, parsed.totalMs)
        assertEquals(1.0, parsed.engineBootMs)
        assertTrue(parsed.engineReused)
        assertEquals(956.0, parsed.sessionMs)
        assertEquals(53.0, parsed.handoffMs)
        assertEquals(4976.0, parsed.playerMs)
        assertEquals(42.0, parsed.decoderSetupMs)
        assertEquals(Prefetch.HEAD_AND_TAIL_READY, parsed.prefetch)
        assertEquals(6.0, parsed.nzbLoadMs)
        assertEquals(941.0 - 12.5, parsed.selectionMs)
        assertEquals(0.0, parsed.archiveDiscoveryMs)
        // The longest wait of the requests that got their first byte.
        assertEquals(500.0, parsed.longestRequestWaitMs)
        assertEquals(2630.2, parsed.firstMediaBytesMs)
        assertEquals(120.5, parsed.nzbBodyReadMs)
        assertEquals(NzbLookup.HIT, parsed.nzbLookup)
        assertNull(parsed.nzbWrite)
        assertEquals(2097152, parsed.nzbBytes)
        assertEquals(12, parsed.articleCacheHits)
        assertEquals(2, parsed.cancelledDownloads)
        assertEquals(16777216.0, parsed.articleBufferBytes)
        // Not measured: no NZB download happened.
        assertNull(parsed.nzbHeadersMs)
        assertNull(parsed.nzbIndexMs)
    }

    @Test
    fun anEmptyOrUnreadableReportIsNone() {
        assertNull(UsenetStartupReport.parse(""))
        assertNull(UsenetStartupReport.parse("not json"))
        assertNull(UsenetStartupReport.parse("[1,2]"))
    }

    @Test
    fun missingMarksAreNotMeasured() {
        val parsed = UsenetStartupReport.parse("""{"marksMs":{"resolve_lock_acquired":0,"first_frame":900}}""")!!
        assertEquals(900.0, parsed.totalMs)
        assertNull(parsed.handoffMs)
        assertNull(parsed.decoderSetupMs)
        assertFalse(parsed.engineReused)
        assertNull(parsed.nzbLookup)
        assertNull(parsed.recordedAtMs)
        // Fast MKV startup off and no warm-up: the prefetch row says Off.
        assertEquals(Prefetch.OFF, parsed.prefetch)
    }

    @Test
    fun prefetchAndCacheStatesFollowTheEngine() {
        fun parse(engine: String) = UsenetStartupReport.parse("""{"fastMkvStartup":true,"engine":$engine}""")!!
        assertEquals(Prefetch.SKIPPED_FEW_CONNECTIONS, parse("""{"marksMs":{"warmup_skipped_connection_limit":1}}""").prefetch)
        assertEquals(Prefetch.PARTIAL, parse("""{"marksMs":{"warmup_started":1,"head_article_ready":5}}""").prefetch)
        assertEquals(Prefetch.NOT_APPLICABLE, parse("""{"marksMs":{}}""").prefetch)
        assertEquals(NzbLookup.EXPIRED, parse("""{"nzbCache":{"lookup":"miss","reason":"expired"}}""").nzbLookup)
        assertEquals(NzbLookup.MISS, parse("""{"nzbCache":{"lookup":"miss"}}""").nzbLookup)
        val saved = parse("""{"nzbCache":{"lookup":"miss","write":"saved","bytes":1024}}""")
        assertEquals(NzbWrite.SAVED, saved.nzbWrite)
        assertEquals(1024, saved.nzbBytes)
        // The size shows only for an NZB that came from or went to the cache.
        assertNull(parse("""{"nzbCache":{"lookup":"miss","write":"busy","bytes":1024}}""").nzbBytes)
    }

    @Test
    fun aNegativeSpanIsNotShown() {
        val parsed = UsenetStartupReport.parse("""{"marksMs":{"resolved":900,"prepare":100}}""")!!
        assertNull(parsed.handoffMs)
    }

    @Test
    fun durationsShowRoundedMilliseconds() {
        assertEquals("957 ms", com.nuvio.app.features.settings.ms(956.6))
        assertEquals("—", com.nuvio.app.features.settings.ms(null))
        assertEquals("—", com.nuvio.app.features.settings.ms(-1.0))
    }
}
