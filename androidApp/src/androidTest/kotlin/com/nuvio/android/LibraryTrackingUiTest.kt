package com.nuvio.android

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.TrackingListPickerDialog
import com.nuvio.app.features.library.LibraryListDialogMode
import com.nuvio.app.features.library.LibraryListDialogState
import com.nuvio.app.features.library.LibraryListManagementController
import com.nuvio.app.features.library.LibraryListManagementDialog
import com.nuvio.app.features.library.LibraryManagementContext
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.tracking.LibraryListPrivacy
import com.nuvio.app.features.tracking.TrackingLibraryTab
import com.nuvio.app.features.tracking.TrackingLibraryTabKind
import com.nuvio.app.features.tracking.TrackingListManagementCapabilities
import com.nuvio.app.features.tracking.TrackingListManager
import com.nuvio.app.features.tracking.TrackingProviderId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryTrackingUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = LibraryManagementContext(1, LibrarySourceMode.MDBLIST, 1)
    private val capabilities = TrackingListManagementCapabilities(listOf(LibraryListPrivacy.PRIVATE, LibraryListPrivacy.PUBLIC))
    private val movies = TrackingLibraryTab("mdblist:list:42", "Movies", TrackingProviderId.MDBLIST, TrackingLibraryTabKind.PERSONAL)

    @Test
    fun createRenameVisibilityAndDeleteUseSharedController() {
        val calls = mutableListOf<String>()
        val manager = object : TrackingListManager {
            override val capabilities = this@LibraryTrackingUiTest.capabilities
            override suspend fun createList(name: String, description: String?, privacy: LibraryListPrivacy) { calls += "create:$name:$privacy" }
            override suspend fun updateList(key: String, name: String, description: String?, privacy: LibraryListPrivacy) { calls += "update:$key:$name:$privacy" }
            override suspend fun deleteList(key: String) { calls += "delete:$key" }
        }
        val controller = LibraryListManagementController({ context }, { manager })
        controller.open()
        compose.setContent {
            NuvioTheme {
                val state by controller.state.collectAsState()
                state?.let {
                    LibraryListManagementDialog(it, listOf(movies), capabilities, controller::create, controller::edit,
                        controller::requestDelete, controller::setName, controller::setDescription, controller::setPrivacy,
                        { runBlocking { controller.submit() } }, controller::open, controller::dismiss)
                }
            }
        }
        compose.onNodeWithText("Manage Lists").assertIsDisplayed()
        screenshot("library-manage.png")
        compose.onNodeWithText("Create List").performClick()
        compose.onNodeWithText("Create List").assertIsDisplayed()
        compose.onNodeWithText("Description").assertDoesNotExist()
        compose.onNodeWithText("Move Up").assertDoesNotExist()
        compose.onNodeWithText("Create").assertIsNotEnabled()
        screenshot("library-create.png")
        compose.onNodeWithText("Name").performTextInput("Weekend")
        compose.onNodeWithText("Public").performClick()
        compose.onNodeWithText("Create").performClick()
        compose.onNodeWithText("Movies").performClick()
        compose.onNodeWithText("Edit List").assertIsDisplayed()
        screenshot("library-edit.png")
        compose.onNodeWithText("Name").performTextReplacement("Movies renamed")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Movies").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete this list?").assertIsDisplayed()
        compose.onNodeWithText("This removes “Movies” and all its list items from MDBList.").assertIsDisplayed()
        screenshot("library-delete.png")
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle {
            assertEquals(listOf("create:Weekend:PUBLIC", "update:mdblist:list:42:Movies renamed:PRIVATE", "delete:mdblist:list:42"), calls)
        }
    }

    @Test
    fun pendingMutationDisablesEditorAndShowsTvStatusCopy() {
        compose.setContent {
            NuvioTheme {
                LibraryListManagementDialog(LibraryListDialogState(context, 1, LibraryListDialogMode.EDIT, name = "Weekend", isPending = true),
                    emptyList(), capabilities, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Saving…").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Name").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Close").assertIsNotEnabled()
        compose.onNodeWithText("Manage Lists").assertIsNotEnabled()
        compose.onNodeWithText("Description").assertDoesNotExist()
    }

    @Test
    fun sameNamedMembershipDestinationsIdentifyProviderAndDispatchCorrectKey() {
        val membership = mutableStateOf(mapOf<String, Boolean>())
        var saved = false
        val tabs = listOf(movies, movies.copy(key = "trakt:list:42", providerId = TrackingProviderId.TRAKT))
        compose.setContent {
            NuvioTheme {
                TrackingListPickerDialog(true, "Save to lists", tabs, membership.value, false, null,
                    { key -> membership.value = membership.value + (key to (membership.value[key] != true)) }, { saved = true }, {})
            }
        }
        compose.onNodeWithText("MDBList · Movies").assertIsDisplayed().performClick()
        compose.onNodeWithText("Trakt · Movies").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(mapOf("mdblist:list:42" to true), membership.value)
            assertEquals(true, saved)
        }
    }

    @Test
    fun errorKeepsEditorAvailableWithoutExposingBackendText() {
        compose.setContent {
            NuvioTheme {
                LibraryListManagementDialog(LibraryListDialogState(context, 1, LibraryListDialogMode.EDIT,
                    name = "Weekend", error = IllegalStateException("internal backend body")),
                    emptyList(), capabilities, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Failed to save list").assertIsDisplayed()
        compose.onNodeWithText("internal backend body").assertDoesNotExist()
        compose.onNodeWithText("Weekend").assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("libraryScreenshots") != "true") return
        compose.waitForIdle()
        android.os.SystemClock.sleep(350)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

}
