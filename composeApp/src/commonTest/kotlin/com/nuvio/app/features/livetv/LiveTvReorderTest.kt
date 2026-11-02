package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.data.LiveTvCodec
import com.nuvio.app.features.livetv.data.LiveTvKeys
import com.nuvio.app.features.livetv.data.LiveTvPreferencesStore
import com.nuvio.app.features.livetv.data.PrefValue
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvSettings
import com.nuvio.app.features.livetv.ui.LiveTvViewState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Drag-to-reorder and show/hide write the TV app's keys in the TV app's shapes. */
class LiveTvReorderTest {
    private class TestStore : LiveTvPreferencesStore() {
        init { installPersistence(load = { null }, save = {}) }
    }

    private fun channel(key: String, name: String, group: String, number: Int) =
        LiveChannel(key, "pl", "Playlist", name, null, null, null, group, group, number, "http://h/$key.ts")

    @Test
    fun channelOrderIsAMapOfGroupToKeys() {
        val store = TestStore()
        store.setChannelOrder("News", listOf("c", "a", "b"))
        store.setChannelOrder("Sports", listOf("y", "x"))
        val raw = (store.rawData.value[LiveTvKeys.channelOrder] as PrefValue.Str).value
        assertEquals(mapOf("News" to listOf("c", "a", "b"), "Sports" to listOf("y", "x")), LiveTvCodec.decodeListMap(raw))
        assertEquals("""{"News":["c","a","b"],"Sports":["y","x"]}""", raw)
        assertEquals(listOf("c", "a", "b"), store.currentUserState().channelOrder["News"])
        store.resetChannelOrder()
        assertTrue(store.currentUserState().channelOrder.isEmpty())
    }

    @Test
    fun groupOrderIsAListAndSwitchesToYourOrder() {
        val store = TestStore()
        store.updateSettings { it.copy(groupSort = "name") }
        store.setGroupOrder(listOf("Sports", "News", "custom:abc"))
        assertEquals("""["Sports","News","custom:abc"]""", (store.rawData.value[LiveTvKeys.groupOrder] as PrefValue.Str).value)
        assertEquals(listOf("Sports", "News", "custom:abc"), store.currentUserState().groupOrder)
        assertEquals("custom", store.currentSettings().groupSort)
        store.moveGroup("News", -1, store.currentUserState().groupOrder)
        assertEquals(listOf("News", "Sports", "custom:abc"), store.currentUserState().groupOrder)
    }

    @Test
    fun hiddenChannelsAndGroupsAreStringSets() {
        val store = TestStore()
        store.setChannelHidden("a", true)
        store.setChannelHidden("b", true)
        store.setChannelHidden("a", false)
        assertEquals(setOf("b"), (store.rawData.value[LiveTvKeys.hiddenChannels] as PrefValue.StringSet).value)
        store.setGroupHidden("Shop", true)
        assertEquals(setOf("Shop"), store.currentUserState().hiddenGroups)
        store.clearHiddenChannels()
        assertTrue(store.currentUserState().hiddenChannels.isEmpty())
    }

    @Test
    fun theListFollowsTheSavedOrderAndHidesWhatYouHid() {
        val store = TestStore()
        val channels = listOf(channel("a", "A", "News", 1), channel("b", "B", "News", 2), channel("c", "C", "News", 3), channel("s", "S", "Sports", 4))
        store.setChannelOrder("News", listOf("c", "a"))
        store.setChannelHidden("b", true)
        store.setGroupOrder(listOf("Sports", "News"))
        val state = LiveTvViewState.build(channels, store.currentUserState(), store.currentSettings(), hasSources = true)
        assertEquals(listOf("Sports", "News"), state.sections.map { it.group.id })
        assertEquals(listOf("c", "a"), state.sections.first { it.group.id == "News" }.channels.map { it.key })
        // Channels never placed follow the placed ones, in the sort order.
        val unhidden = LiveTvViewState.build(channels, store.currentUserState().copy(hiddenChannels = emptySet()), LiveTvSettings(groupSort = "custom"), hasSources = true)
        assertEquals(listOf("c", "a", "b"), unhidden.sections.first { it.group.id == "News" }.channels.map { it.key })
    }
}
