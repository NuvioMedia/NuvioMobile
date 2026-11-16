package com.nuvio.app.core.format

import android.app.Application
import java.util.TimeZone
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en-rUS")
class ReleaseDateDisplayTest {
    private lateinit var originalTimeZone: TimeZone

    @BeforeTest
    fun setTimeZone() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @AfterTest
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun formatsIsoDate() {
        assertEquals("February 1, 2025", formatReleaseDateForDisplay("2025-02-01"))
    }

    @Test
    fun stripsTimePortion() {
        assertEquals("January 15, 2024", formatReleaseDateForDisplay("2024-01-15T12:30:00Z"))
    }

    @Test
    fun leavesYearOnlyUnchanged() {
        assertEquals("2024", formatReleaseDateForDisplay("2024"))
    }

    @Test
    fun leavesNonIsoUnchanged() {
        assertEquals("TBA", formatReleaseDateForDisplay("TBA"))
    }

    @Test
    fun extractsYearFromIso() {
        assertEquals(2025, extractReleaseYearForDisplay("2025-03-15"))
    }

    @Test
    fun extractsYearFromYearOnly() {
        assertEquals(2024, extractReleaseYearForDisplay("2024"))
    }

    @Test
    fun formatsIsoDateWithoutYear() {
        assertEquals("February 1", formatReleaseDateWithoutYear("2025-02-01"))
    }

    @Test
    fun formatReleaseDateWithoutYearStripsTimePortion() {
        assertEquals("January 15", formatReleaseDateWithoutYear("2024-01-15T12:30:00Z"))
    }

    @Test
    fun formatReleaseDateWithoutYearLeavesYearOnlyUnchanged() {
        assertEquals("2024", formatReleaseDateWithoutYear("2024"))
    }

    @Test
    fun formatReleaseDateWithoutYearLeavesNonIsoUnchanged() {
        assertEquals("TBA", formatReleaseDateWithoutYear("TBA"))
    }

    @Test
    fun usesBritishDateOrder() {
        assertEquals("1 February 2025", formatReleaseDate("2025-02-01", true, "en-GB"))
    }

    @Test
    fun translatesFrenchMonthAndUsesFrenchDateOrder() {
        assertEquals("1 février 2025", formatReleaseDate("2025-02-01", true, "fr-FR"))
    }

    @Test
    fun usesJapaneseDateOrder() {
        assertEquals("2025年2月1日", formatReleaseDate("2025-02-01", true, "ja-JP"))
    }

    @Test
    fun localizesDateWithoutYear() {
        assertEquals("1 February", formatReleaseDate("2025-02-01", false, "en-GB"))
        assertEquals("1 février", formatReleaseDate("2025-02-01", false, "fr-FR"))
        assertEquals("2月1日", formatReleaseDate("2025-02-01", false, "ja-JP"))
    }

    @Test
    fun preservesInvalidCalendarDates() {
        assertEquals("2025-02-30", formatReleaseDateForDisplay("2025-02-30"))
    }

    @Test
    fun convertsTimestampToLocalCalendarDateBeforeFormatting() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        assertEquals("January 31, 2025", formatReleaseDate("2025-02-01T01:00:00Z", true, "en-US"))
    }
}
