package com.nuvio.app.features.library.sync

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryLocalState
import com.nuvio.app.features.library.LibraryStoragePayloadCodec
import com.nuvio.app.features.library.toMetaPreview
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SupabaseLibrarySyncAdapterTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `library push includes the saved logo`() {
        val item = LibraryItem(
            id = "tt123",
            type = "movie",
            name = "Title",
            logo = "https://example.com/logo.png",
            savedAtEpochMs = 1L,
        )

        val payload = json.parseToJsonElement(json.encodeToString(item.toSyncItem())).jsonObject

        assertEquals(item.logo, payload.getValue("logo").jsonPrimitive.content)
        assertEquals(item, json.decodeFromString<LibrarySyncItem>(payload.toString()).toLibraryItem())
    }

    @Test
    fun `snapshot logo survives local storage and reload`() {
        val remote = json.decodeFromString<LibrarySyncItem>(
            """{"content_id":"tt123","content_type":"movie","logo":"https://example.com/logo.png"}""",
        ).toLibraryItem()
        val state = loadedState()
        val synced = assertNotNull(
            state.applyServerItems(state.snapshot(), listOf(remote), cursorEventId = 1L),
        ).snapshot
        val stored = LibraryStoragePayloadCodec.decode(LibraryStoragePayloadCodec.encode(synced))
        val reloaded = loadedState(stored.items)

        assertEquals(remote.logo, reloaded.snapshot().items.single().toMetaPreview().logo)
    }

    @Test
    fun `delta logo survives local storage and reload`() {
        val event = json.decodeFromString<LibraryDeltaSyncItem>(
            """{"event_id":2,"operation":"upsert","content_id":"tt123","content_type":"movie","logo":"https://example.com/new-logo.png"}""",
        )
        val state = loadedState()
        val synced = assertNotNull(
            state.applyDeltaEvents(
                token = state.snapshot().token,
                events = listOf(LibraryDeltaEvent(event.eventId, event.operation, event.toLibraryItem())),
            ),
        )
        val stored = LibraryStoragePayloadCodec.decode(LibraryStoragePayloadCodec.encode(synced))
        val reloaded = loadedState(stored.items)

        assertEquals(event.logo, reloaded.snapshot().items.single().toMetaPreview().logo)
        assertEquals(event.eventId, stored.deltaCursorEventId)
    }

    @Test
    fun `older snapshot and delta payloads without logos still decode`() {
        val snapshot = json.decodeFromString<LibrarySyncItem>(
            """{"content_id":"tt123","content_type":"movie"}""",
        )
        val delta = json.decodeFromString<LibraryDeltaSyncItem>(
            """{"event_id":1,"operation":"upsert","content_id":"tt123","content_type":"movie"}""",
        )

        assertNull(snapshot.toLibraryItem().logo)
        assertNull(delta.toLibraryItem().logo)
    }

    @Test
    fun `nullable server logos retain the title fallback`() {
        val item = json.decodeFromString<LibrarySyncItem>(
            """{"content_id":"tt123","content_type":"movie","name":"Title","logo":null}""",
        ).toLibraryItem().toMetaPreview()

        assertNull(item.logo)
        assertEquals("Title", item.name)
    }

    private fun loadedState(items: List<LibraryItem> = emptyList()): LibraryLocalState {
        val state = LibraryLocalState()
        val token = state.beginProfileLoad(profileId = 1).snapshot.token
        state.completeProfileLoad(token = token, activeProfileId = 1, items = items)
        return state
    }
}
