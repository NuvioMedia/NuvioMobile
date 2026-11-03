package com.nuvio.app.features.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelegramByteRangeParserTest {

    @Test
    fun fullRequestMapsToEntireFile() {
        assertEquals(
            TelegramByteRange(0L, 999L),
            TelegramByteRangeParser.parse(null, 1_000L),
        )
    }

    @Test
    fun boundedRangeIsClampedToEndOfFile() {
        assertEquals(
            TelegramByteRange(100L, 999L),
            TelegramByteRangeParser.parse("bytes=100-1200", 1_000L),
        )
    }

    @Test
    fun openEndedRangeExtendsToEndOfFile() {
        assertEquals(
            TelegramByteRange(500L, 999L),
            TelegramByteRangeParser.parse("bytes=500-", 1_000L),
        )
    }

    @Test
    fun suffixRangeSelectsLastBytes() {
        assertEquals(
            TelegramByteRange(900L, 999L),
            TelegramByteRangeParser.parse("bytes=-100", 1_000L),
        )
    }

    @Test
    fun oversizedSuffixSelectsWholeFile() {
        assertEquals(
            TelegramByteRange(0L, 999L),
            TelegramByteRangeParser.parse("bytes=-5000", 1_000L),
        )
    }

    @Test
    fun malformedAndMultipleRangesAreRejected() {
        assertNull(TelegramByteRangeParser.parse("items=1-2", 1_000L))
        assertNull(TelegramByteRangeParser.parse("bytes=1-2,4-5", 1_000L))
        assertNull(TelegramByteRangeParser.parse("bytes=abc-def", 1_000L))
        assertNull(TelegramByteRangeParser.parse("bytes=-0", 1_000L))
    }

    @Test
    fun unsatisfiableRangesAreRejected() {
        assertNull(TelegramByteRangeParser.parse("bytes=1000-", 1_000L))
        assertNull(TelegramByteRangeParser.parse("bytes=20-10", 1_000L))
        assertNull(TelegramByteRangeParser.parse("bytes=0-0", 0L))
    }
}
