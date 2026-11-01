package com.nuvio.app.features.player

import kotlinx.coroutines.test.runTest
import nuvio.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SubtitleAppearancePlatformTest {
    @Test
    fun appearancePersistsAndRestoresFromSync() {
        val original = PlayerSettingsStorage.exportToSyncPayload()
        try {
            val appearance = SubtitleAppearance("mono", 18, true, 60, 4)
            PlayerSettingsStorage.saveSubtitleAppearance(appearance.encode())
            val payload = PlayerSettingsStorage.exportToSyncPayload()
            PlayerSettingsStorage.saveSubtitleAppearance(SubtitleAppearance().encode())
            PlayerSettingsStorage.replaceFromSyncPayload(payload)
            assertEquals(appearance, decodeSubtitleAppearance(PlayerSettingsStorage.loadSubtitleAppearance()))
        } finally {
            PlayerSettingsStorage.replaceFromSyncPayload(original)
        }
    }

    @OptIn(ExperimentalResourceApi::class)
    @Test
    fun importedFontSurvivesReloadAndDuplicateImport() = runTest {
        val bytes = Res.readBytes("font/jetbrains_sans_regular.ttf")
        val original = SubtitleFontStorage.read("fonts.json")
        val existingIds = SubtitleFonts.installed().map { it.id }
        val font = SubtitleFonts.import("Custom.ttf", bytes)
        try {
            assertEquals(font, SubtitleFonts.installed().single { it.id == font.id })
            assertEquals(font, SubtitleFonts.import("Renamed.ttf", bytes))
            assertNotNull(SubtitleFontStorage.fontFamily(font.id))
            SubtitleFonts.remove(font.id)
            assertNull(SubtitleFontStorage.read(font.id))
        } finally {
            if (font.id in existingIds) SubtitleFontStorage.write(font.id, bytes) else SubtitleFontStorage.remove(font.id)
            if (original == null) SubtitleFontStorage.remove("fonts.json") else SubtitleFontStorage.write("fonts.json", original)
        }
    }
}
