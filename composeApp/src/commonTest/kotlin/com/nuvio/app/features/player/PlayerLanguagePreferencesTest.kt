package com.nuvio.app.features.player

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.lang_chinese_traditional
import nuvio.composeapp.generated.resources.lang_english
import nuvio.composeapp.generated.resources.lang_malayalam
import nuvio.composeapp.generated.resources.lang_portuguese_brazil
import nuvio.composeapp.generated.resources.lang_spanish_latin_america
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerLanguagePreferencesTest {

    @Test
    fun everyLanguageOptionResolvesToItsOwnLabel() {
        AvailableLanguageOptions.forEach { option ->
            assertEquals(option.labelRes, languageLabelResForCode(option.code), option.code)
        }
    }

    @Test
    fun resolvesLabelsForAliasesNamesAndRegionalVariants() {
        assertEquals(Res.string.lang_english, languageLabelResForCode("eng"))
        assertEquals(Res.string.lang_english, languageLabelResForCode("English [Forced]"))
        assertEquals(Res.string.lang_portuguese_brazil, languageLabelResForCode("pob"))
        assertEquals(Res.string.lang_portuguese_brazil, languageLabelResForCode("Brazilian Portuguese"))
        assertEquals(Res.string.lang_chinese_traditional, languageLabelResForCode("zh_TW"))
        assertEquals(Res.string.lang_spanish_latin_america, languageLabelResForCode("Spanish (Latin America)"))
        assertEquals(Res.string.lang_malayalam, languageLabelResForCode("Malayalam"))
        assertEquals(Res.string.lang_malayalam, languageLabelResForCode("Malayalam SDH"))
        assertEquals(Res.string.lang_english, languageLabelResForCode("English  \t SDH"))
    }

    @Test
    fun returnsNoLabelForMissingOrUnknownCodes() {
        assertNull(languageLabelResForCode(null))
        assertNull(languageLabelResForCode(" "))
        assertNull(languageLabelResForCode("xx"))
    }

    @Test
    fun prefersTheLongestLanguageNameInATrackTitle() {
        assertEquals("vi", normalizeLanguageCode("Lao Vietnamese"))
        assertEquals("vi", normalizeLanguageCode("Vietnamese Lao"))
    }

    @Test
    fun breaksTiesBetweenEqualLengthNamesByDeclarationOrder() {
        assertEquals("nl", normalizeLanguageCode("Hindi Dutch"))
        assertEquals("nl", normalizeLanguageCode("Dutch Hindi"))
    }
}
