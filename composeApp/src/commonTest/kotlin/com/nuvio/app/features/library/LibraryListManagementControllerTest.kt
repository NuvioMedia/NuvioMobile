package com.nuvio.app.features.library

import com.nuvio.app.features.tracking.LibraryListPrivacy
import com.nuvio.app.features.tracking.TrackingListManagementCapabilities
import com.nuvio.app.features.tracking.TrackingListManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryListManagementControllerTest {
    @Test
    fun duplicateSubmissionIsSuppressedAndOldCompletionKeepsNewEditor() = runTest {
        val scope = LibraryManagementContext(1, LibrarySourceMode.MDBLIST, 1)
        val manager = TestListManager()
        val waiting = CompletableDeferred<Unit>()
        manager.action = { waiting.await() }
        val controller = LibraryListManagementController({ scope }, { manager })
        controller.open()
        controller.create()
        controller.setName("First")
        val first = launch { controller.submit() }
        runCurrent()
        controller.submit()
        assertEquals(listOf("create:First:PRIVATE"), manager.calls)
        controller.open()
        controller.create()
        controller.setName("Second")
        waiting.complete(Unit)
        first.join()
        assertEquals("Second", controller.state.value?.name)
        assertEquals(LibraryListDialogMode.EDIT, controller.state.value?.mode)
    }

    @Test
    fun profileSourceOrAccountChangesRejectPendingDrafts() = runTest {
        val original = LibraryManagementContext(1, LibrarySourceMode.MDBLIST, 1)
        for (replacement in listOf(original.copy(profileId = 2), original.copy(source = LibrarySourceMode.TRAKT), original.copy(accountGeneration = 2))) {
            var current = original
            val manager = TestListManager()
            val controller = LibraryListManagementController({ current }, { manager })
            controller.create()
            controller.setName("Draft")
            current = replacement
            controller.submit()
            assertTrue(manager.calls.isEmpty())
            assertNull(controller.state.value)
        }
    }

    @Test
    fun failedSaveKeepsDraftAndCanBeRetried() = runTest {
        val context = LibraryManagementContext(1, LibrarySourceMode.MDBLIST, 1)
        val manager = TestListManager().apply { action = { error("Unavailable") } }
        val controller = LibraryListManagementController({ context }, { manager })
        controller.create()
        controller.setName("Movies")
        controller.setPrivacy(LibraryListPrivacy.PUBLIC)
        controller.submit()
        assertEquals("Unavailable", controller.state.value?.error?.message)
        assertEquals("Movies", controller.state.value?.name)
        manager.action = {}
        controller.submit()
        assertEquals(LibraryListDialogMode.MANAGE, controller.state.value?.mode)
        assertEquals(listOf("create:Movies:PUBLIC", "create:Movies:PUBLIC"), manager.calls)
    }

    private class TestListManager : TrackingListManager {
        override val capabilities = TrackingListManagementCapabilities(listOf(LibraryListPrivacy.PRIVATE, LibraryListPrivacy.PUBLIC))
        val calls = mutableListOf<String>()
        var action: suspend () -> Unit = {}
        override suspend fun createList(name: String, description: String?, privacy: LibraryListPrivacy) { calls += "create:$name:$privacy"; action() }
        override suspend fun updateList(key: String, name: String, description: String?, privacy: LibraryListPrivacy) { calls += "update:$key:$name"; action() }
        override suspend fun deleteList(key: String) { calls += "delete:$key"; action() }
    }
}
