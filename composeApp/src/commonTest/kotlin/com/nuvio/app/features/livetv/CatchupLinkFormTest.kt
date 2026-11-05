package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.data.CatchupUrlBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatchupLinkFormTest {
    private val hls = "http://h.example/timeshift/u/p/120/2026-11-05:20-00/55.m3u8"
    private val ts = "http://h.example/timeshift/u/p/120/2026-11-05:20-00/55.ts"
    private val other = "http://h.example/timeshift/u/p/120/2026-11-06:01-00/55.ts"
    private val short = "http://h.example/timeshift/u/p/60/2026-11-05:20-00/55.ts"
    private val php = "http://h.example/streaming/timeshift.php?username=u&password=p&stream=55&start=2026-11-05:20-00&duration=120"

    @Test
    fun wholeMinuteLinksAreRecognised() {
        assertTrue(CatchupUrlBuilder.wholeMinutes(hls))
        assertTrue(CatchupUrlBuilder.wholeMinutes(php))
        assertFalse(CatchupUrlBuilder.wholeMinutes("http://h.example/live/u/p/55.ts"))
    }

    @Test
    fun linkFormNamesTheKind() {
        assertEquals("hls", CatchupUrlBuilder.linkForm(hls, hls))
        assertEquals("ts", CatchupUrlBuilder.linkForm(ts, hls))
        assertEquals("ts+clock", CatchupUrlBuilder.linkForm(other, hls))
        assertEquals("ts+short", CatchupUrlBuilder.linkForm(short, hls))
        assertEquals("php", CatchupUrlBuilder.linkForm(php, hls))
    }

    @Test
    fun rememberedKindGoesFirst() {
        val rest = listOf(ts, short, php)
        assertEquals(listOf(hls) + rest, CatchupUrlBuilder.preferForm(hls, rest, null))
        assertEquals(listOf(php, hls, ts, short), CatchupUrlBuilder.preferForm(hls, rest, "php"))
        assertEquals(listOf(ts, hls, short, php), CatchupUrlBuilder.preferForm(hls, rest, "ts"))
    }

    @Test
    fun hlsIsNotPutBackWhenTheFirstLinkIsTs() {
        assertEquals(listOf(ts, php), CatchupUrlBuilder.preferForm(ts, listOf(php), "hls"))
    }
}
