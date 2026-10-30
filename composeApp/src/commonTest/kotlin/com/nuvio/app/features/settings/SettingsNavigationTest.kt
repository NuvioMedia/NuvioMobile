package com.nuvio.app.features.settings

import com.nuvio.app.features.player.AndroidPlaybackEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsNavigationTest {
    @Test
    fun layoutChildrenReturnToLayout() {
        listOf(
            SettingsPage.Homescreen,
            SettingsPage.MetaScreen,
            SettingsPage.ContinueWatching,
            SettingsPage.PosterCustomization,
            SettingsPage.Streams,
        ).forEach { page ->
            assertEquals(SettingsPage.Layout, page.previousPage())
            assertEquals(SettingsCategory.Look, page.category)
        }
        assertEquals(SettingsPage.Root, SettingsPage.Layout.previousPage())
    }

    @Test
    fun playbackChildrenReturnToPlaybackInTheWatchGroup() {
        PlaybackSection.entries.forEach { section ->
            assertEquals(SettingsPage.Playback, section.page.previousPage())
            assertEquals(SettingsCategory.Watch, section.page.category)
        }
        assertEquals(SettingsPage.Root, SettingsPage.Playback.previousPage())
    }

    @Test
    fun bufferOptionsFollowThePlatformAndPlaybackEngine() {
        AndroidPlaybackEngine.entries.forEach { engine ->
            val android = visiblePlaybackSections(ios = false, engine = engine, p2pVisible = false)
            val ios = visiblePlaybackSections(ios = true, engine = engine, p2pVisible = false)
            assertEquals(engine != AndroidPlaybackEngine.Libmpv, PlaybackSection.Buffer in android)
            assertFalse(PlaybackSection.Buffer in ios)
            assertFalse(PlaybackSection.P2p in android)
            assertFalse(PlaybackSection.P2p in ios)
            assertTrue(PlaybackSection.Interface in android)
            assertTrue(PlaybackSection.Interface in ios)
        }
        assertTrue(PlaybackSection.P2p in visiblePlaybackSections(false, AndroidPlaybackEngine.Auto, true))
    }

    @Test
    fun oldTabletCategoriesRestoreToTheirNewGroups() {
        assertEquals(SettingsCategory.You, settingsCategoryForName("Account"))
        assertEquals(SettingsCategory.Look, settingsCategoryForName("General"))
        assertEquals(SettingsCategory.System, settingsCategoryForName("About"))
        assertEquals(SettingsCategory.System, settingsCategoryForName("Advanced"))
        assertEquals(SettingsCategory.Services, settingsCategoryForName("Services"))
        assertEquals(SettingsCategory.Look, settingsCategoryForName("unknown"))
    }
}
