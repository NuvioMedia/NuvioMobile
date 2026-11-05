package com.nuvio.app.features.player.skip

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.cw_airs_date
import nuvio.composeapp.generated.resources.cw_airs_in_days
import nuvio.composeapp.generated.resources.cw_airs_today
import nuvio.composeapp.generated.resources.cw_airs_tomorrow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerNextEpisodeRulesTest {

    private val fixedToday = DateComponents(year = 2026, month = 8, day = 30)

    @Test
    fun hasEpisodeAiredReturnsTrueForPastAndToday() {
        assertTrue(PlayerNextEpisodeRules.hasEpisodeAired("2026-08-01"))
        assertTrue(PlayerNextEpisodeRules.hasEpisodeAired("2026-08-29"))
        assertTrue(PlayerNextEpisodeRules.hasEpisodeAired(null))
        assertTrue(PlayerNextEpisodeRules.hasEpisodeAired(""))
        assertTrue(PlayerNextEpisodeRules.hasEpisodeAired("TBA"))
    }

    @Test
    fun formatUnairedEpisodeMessageFallsBackToTbaWhenMissingOrUnparseable() {
        val nullResult = PlayerNextEpisodeRules.formatUnairedEpisodeMessageSync(
            released = null,
            airsPrefix = "Airs",
            tbaLabel = "TBA",
            todayComponents = fixedToday,
        )
        assertEquals("Airs TBA", nullResult)

        val emptyResult = PlayerNextEpisodeRules.formatUnairedEpisodeMessageSync(
            released = "   ",
            airsPrefix = "Airs",
            tbaLabel = "TBA",
            todayComponents = fixedToday,
        )
        assertEquals("Airs TBA", emptyResult)

        val tbaResult = PlayerNextEpisodeRules.formatUnairedEpisodeMessageSync(
            released = "TBA",
            airsPrefix = "Airs",
            tbaLabel = "TBA",
            todayComponents = fixedToday,
        )
        assertEquals("Airs TBA", tbaResult)

        val invalidResult = PlayerNextEpisodeRules.formatUnairedEpisodeMessageSync(
            released = "invalid-date",
            airsPrefix = "Airs",
            tbaLabel = "TBA",
            todayComponents = fixedToday,
        )
        assertEquals("Airs TBA", invalidResult)
    }

    @Test
    fun formatUnairedEpisodeMessageFormatsToday() {
        val result = PlayerNextEpisodeRules.formatUnairedEpisodeMessageSync(
            released = "2026-08-30",
            airsPrefix = "Airs",
            tbaLabel = "TBA",
            todayComponents = fixedToday,
        )
        assertEquals("Airs Today", result)
    }

    @Test
    fun formatUnairedEpisodeMessageFormatsTomorrow() {
        val result = PlayerNextEpisodeRules.formatUnairedEpisodeMessageSync(
            released = "2026-08-31",
            airsPrefix = "Airs",
            tbaLabel = "TBA",
            todayComponents = fixedToday,
        )
        assertEquals("Airs Tomorrow", result)
    }

    @Test
    fun formatUnairedEpisodeMessageFormatsRelativeDays() {
        val result2Days = PlayerNextEpisodeRules.formatUnairedEpisodeMessageSync(
            released = "2026-09-01",
            airsPrefix = "Airs",
            tbaLabel = "TBA",
            todayComponents = fixedToday,
        )
        assertEquals("Airs in 2 days", result2Days)

        val result3Days = PlayerNextEpisodeRules.formatUnairedEpisodeMessageSync(
            released = "2026-09-02",
            airsPrefix = "Airs",
            tbaLabel = "TBA",
            todayComponents = fixedToday,
        )
        assertEquals("Airs in 3 days", result3Days)

        val result6Days = PlayerNextEpisodeRules.formatUnairedEpisodeMessageSync(
            released = "2026-09-05T00:00:00.000Z",
            airsPrefix = "Airs",
            tbaLabel = "TBA",
            todayComponents = fixedToday,
        )
        assertEquals("Airs 5 Sep 2026", result6Days)
    }

    @Test
    fun formatUnairedEpisodeMessageFormatsDayFirstForFartherDates() {
        val result = PlayerNextEpisodeRules.formatUnairedEpisodeMessageSync(
            released = "2026-09-15T00:00:00.000Z",
            airsPrefix = "Airs",
            tbaLabel = "TBA",
            todayComponents = fixedToday,
        )
        assertEquals("Airs 15 Sep 2026", result)

        val resultPlain = PlayerNextEpisodeRules.formatUnairedEpisodeMessageSync(
            released = "2026-09-15",
            airsPrefix = "Airs",
            tbaLabel = "TBA",
            todayComponents = fixedToday,
        )
        assertEquals("Airs 15 Sep 2026", resultPlain)
    }

    @Test
    fun formatUnairedEpisodeMessageUsesLocalizedProvidersWhenSupplied() {
        val localizedResult = PlayerNextEpisodeRules.formatUnairedEpisodeMessageSync(
            released = "2026-09-15T00:00:00.000Z",
            airsPrefix = "Diffusé",
            tbaLabel = "TBA",
            todayComponents = fixedToday,
            getStringResource = { res, args ->
                when (res) {
                    Res.string.cw_airs_date -> "Diffusé le ${args[0]}"
                    else -> "unknown"
                }
            },
        )
        assertEquals("Diffusé le 15 Sep 2026", localizedResult)

        val localizedToday = PlayerNextEpisodeRules.formatUnairedEpisodeMessageSync(
            released = "2026-08-30",
            airsPrefix = "Diffusé",
            tbaLabel = "TBA",
            todayComponents = fixedToday,
            getStringResource = { res, _ ->
                when (res) {
                    Res.string.cw_airs_today -> "Diffusé aujourd'hui"
                    else -> "unknown"
                }
            },
        )
        assertEquals("Diffusé aujourd'hui", localizedToday)
    }

    @Test
    fun formatUnairedEpisodeMessageUsesPluralProviderWhenSupplied() {
        val localizedPlural = PlayerNextEpisodeRules.formatUnairedEpisodeMessageSync(
            released = "2026-09-02",
            airsPrefix = "Airs",
            tbaLabel = "TBA",
            todayComponents = fixedToday,
            getPluralResource = { res, count, args ->
                when (res) {
                    Res.plurals.cw_airs_in_days -> "Air date in ${args[0]} days"
                    else -> "unknown"
                }
            },
        )
        assertEquals("Air date in 3 days", localizedPlural)
    }

    @Test
    fun formatUnairedEpisodeMessageSupportsArabicRtlLocalization() {
        val arabicResult = PlayerNextEpisodeRules.formatUnairedEpisodeMessageSync(
            released = "2026-09-02",
            airsPrefix = "موعد العرض",
            tbaLabel = "سيُعلن لاحقًا",
            todayComponents = fixedToday,
            getStringResource = { res, args ->
                when (res) {
                    Res.string.cw_airs_date -> "يُعرض في ${args[0]}"
                    else -> "unknown"
                }
            },
            getPluralResource = { res, count, args ->
                when (res) {
                    Res.plurals.cw_airs_in_days -> "يُعرض خلال $count أيام"
                    else -> "unknown"
                }
            },
        )
        assertEquals("يُعرض خلال 3 أيام", arabicResult)

        val arabicFarther = PlayerNextEpisodeRules.formatUnairedEpisodeMessageSync(
            released = "2026-09-05T00:00:00.000Z",
            airsPrefix = "موعد العرض",
            tbaLabel = "سيُعلن لاحقًا",
            todayComponents = fixedToday,
            getStringResource = { res, args ->
                when (res) {
                    Res.string.cw_airs_date -> "يُعرض في ${args[0]}"
                    else -> "unknown"
                }
            },
        )
        assertEquals("يُعرض في 5 Sep 2026", arabicFarther)

        val arabicTba = PlayerNextEpisodeRules.formatUnairedEpisodeMessageSync(
            released = null,
            airsPrefix = "موعد العرض",
            tbaLabel = "سيُعلن لاحقًا",
            todayComponents = fixedToday,
        )
        assertEquals("موعد العرض سيُعلن لاحقًا", arabicTba)
    }

    @Test
    fun formatUnairedEpisodeMessageHandlesYearEndTransition() {
        val newYearEve = DateComponents(year = 2026, month = 12, day = 31)
        val resultIn2Days = PlayerNextEpisodeRules.formatUnairedEpisodeMessageSync(
            released = "2027-01-02",
            airsPrefix = "Airs",
            tbaLabel = "TBA",
            todayComponents = newYearEve,
        )
        assertEquals("Airs in 2 days", resultIn2Days)

        val resultNextYear = PlayerNextEpisodeRules.formatUnairedEpisodeMessageSync(
            released = "2027-01-15",
            airsPrefix = "Airs",
            tbaLabel = "TBA",
            todayComponents = newYearEve,
        )
        assertEquals("Airs 15 Jan 2027", resultNextYear)
    }

    @Test
    fun formatUnairedEpisodeMessageHandlesBlankAirsPrefixGracefully() {
        val result = PlayerNextEpisodeRules.formatUnairedEpisodeMessageSync(
            released = "invalid",
            airsPrefix = "",
            tbaLabel = "",
            todayComponents = fixedToday,
        )
        assertEquals("Airs TBA", result)
    }
}
