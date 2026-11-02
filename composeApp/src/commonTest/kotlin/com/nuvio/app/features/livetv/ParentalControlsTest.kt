package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvSettings
import com.nuvio.app.features.livetv.model.LiveUserState
import com.nuvio.app.features.livetv.parental.ParentalControls
import com.nuvio.app.features.livetv.ui.LiveTvViewState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParentalControlsTest {
    private fun channel(key: String, name: String, group: String) =
        LiveChannel(key, "pl", "Playlist", name, null, null, null, group, group, 1, "http://h/$key.ts")

    private val news = channel("news", "BBC News", "News")
    private val adult = channel("xxx1", "Hot Channel", "XXX Adults")
    private val sportsCh = channel("sky", "Sky Sports", "Sports")
    private val sexy = channel("sexy", "Sexy TV", "Entertainment")
    private val channels = listOf(news, adult, sportsCh, sexy)

    @Test
    fun adultNamesAreRecognised() {
        assertTrue(ParentalControls.isAdult("XXX Adults"))
        assertTrue(ParentalControls.isAdult("Adult"))
        assertTrue(ParentalControls.isAdult("18+ Movies"))
        assertTrue(ParentalControls.isAdult("Playboy TV"))
        assertFalse(ParentalControls.isAdult("Adulting 101")) // not a whole word
        assertFalse(ParentalControls.isAdult("Sports"))
        assertTrue(ParentalControls.isAdultChannel(sexy))
        assertFalse(ParentalControls.isAdultChannel(news))
    }

    @Test
    fun pinGatesLocksAndUnlockingIsPerGroup() {
        val off = LiveTvSettings(parentalPin = "")
        val on = LiveTvSettings(parentalPin = "1234")
        assertFalse(ParentalControls.enabled(off))
        assertTrue(ParentalControls.enabled(on))
        // Nothing locks without a PIN, even a group named XXX.
        assertFalse(ParentalControls.isLocked("XXX", "XXX", off, setOf("XXX")))
        assertTrue(ParentalControls.isLocked("News", "News", on, setOf("News")))
        assertTrue(ParentalControls.isLocked("XXX", "XXX", on, emptySet()))
        assertFalse(ParentalControls.isLocked("XXX", "XXX", on.copy(lockAdultContent = false), emptySet()))
        assertFalse(ParentalControls.unlock("News", "0000", on))
        assertTrue(ParentalControls.unlock("News", "1234", on))
        assertFalse(ParentalControls.isLocked("News", "News", on, setOf("News")))
        assertTrue(ParentalControls.isLocked("XXX", "XXX", on, emptySet()))
        ParentalControls.relock()
        assertTrue(ParentalControls.isLocked("News", "News", on, setOf("News")))
        assertTrue(ParentalControls.checkPin(" 1234 ", on))
    }

    @Test
    fun lockedGroupsKeepTheirPlaceButLoseTheirChannels() {
        val s = LiveTvSettings(parentalPin = "1234")
        val user = LiveUserState(lockedGroups = setOf("Sports"), recent = listOf("xxx1", "news", "sexy"))
        val state = LiveTvViewState.build(channels, user, s, hasSources = true, unlocked = emptySet())
        val byId = state.sections.associateBy { it.group.id }
        // Locked by choice, and locked by name: listed, empty, marked.
        assertTrue(byId.getValue("Sports").group.locked)
        assertTrue(byId.getValue("Sports").channels.isEmpty())
        assertTrue(byId.getValue("XXX Adults").group.locked)
        assertTrue(byId.getValue("XXX Adults").channels.isEmpty())
        assertFalse(byId.getValue("News").group.locked)
        assertEquals(setOf("Sports", "XXX Adults"), state.lockedGroupIds)
        // Locked channels are out of search; adult channels too while adult content is locked.
        assertEquals(listOf("news"), state.visible.map { it.key })
        // Recently watched never shows adult channels.
        assertEquals(listOf("news"), byId.getValue("__recent__").channels.map { it.key })
        // Unlocked with the PIN: back in place.
        val open = LiveTvViewState.build(channels, user, s, hasSources = true, unlocked = setOf("Sports"))
        assertEquals(listOf("sky"), open.sections.first { it.group.id == "Sports" }.channels.map { it.key })
        assertFalse(open.sections.first { it.group.id == "Sports" }.group.locked)
        // No PIN: nothing is locked at all.
        val none = LiveTvViewState.build(channels, user, LiveTvSettings(), hasSources = true)
        assertTrue(none.lockedGroupIds.isEmpty())
        assertEquals(4, none.visible.size)
    }
}
