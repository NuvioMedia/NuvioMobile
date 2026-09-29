package com.nuvio.app.features.tvremote

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import com.google.zxing.client.android.Intents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w390dp-h844dp")
class TvScannerResultTest {
    @get:Rule val compose = createComposeRule()

    private class Registry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry get() = this
        var requestCode = 0
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            this.requestCode = requestCode
        }
    }

    @Test fun scanResultAfterUiRestorationReopensClosedSheetAndStartsPairingExactlyOnce() = runTest {
        val connection = PairingTestConnection()
        var connections = 0
        val repository = TvRemoteRepository(PairingTestStore(), backgroundScope, { connections++; connection })
        val sheet = MutableStateFlow(true)
        val registry = Registry()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                MaterialTheme { TvRemoteConnectionOverlay(repository, sheet) }
            }
        }
        compose.onNodeWithText("Scan TV code").performClick()
        assertNotEquals(0, registry.requestCode)
        // Process-local visibility is false after recreation while the camera is open.
        compose.runOnIdle { sheet.value = false }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            registry.dispatchResult(registry.requestCode, Activity.RESULT_OK, Intent()
                .putExtra(Intents.Scan.RESULT, pairingTestCode()).putExtra(Intents.Scan.RESULT_FORMAT, "QR_CODE"))
        }
        runCurrent()
        assertTrue(sheet.value)
        assertEquals(1, connections)
        assertEquals("b".repeat(43), connection.secret)
        compose.onNodeWithText("Pairing with Living room…").assertIsDisplayed()
    }

    @Test fun missingCameraPermissionIsVisibleAfterScannerCloses() = runTest {
        val repository = TvRemoteRepository(PairingTestStore(), backgroundScope, { error("Must not pair without a code") })
        val registry = Registry()
        val sheet = MutableStateFlow(true)
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                MaterialTheme { TvRemoteConnectionOverlay(repository, sheet) }
            }
        }
        compose.onNodeWithText("Scan TV code").performClick()
        compose.runOnIdle {
            registry.dispatchResult(registry.requestCode, Activity.RESULT_CANCELED, Intent()
                .putExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, true))
        }
        compose.onNodeWithText("Camera permission is required to scan the TV code.").assertIsDisplayed()
    }
}
