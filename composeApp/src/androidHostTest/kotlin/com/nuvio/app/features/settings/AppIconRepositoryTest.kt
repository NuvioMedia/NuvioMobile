package com.nuvio.app.features.settings

import android.app.Application
import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], application = Application::class)
class AppIconRepositoryTest {
    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("app_icon_settings", Context.MODE_PRIVATE).edit().clear().commit()
        AppIconPlatform.initialize(context)
        resetRepository()
    }

    @Test
    fun selectedIconsRestoreWhenThePlatformReportsOriginal() = runTest {
        for (icon in AppIconOption.entries.drop(1) + AppIconOption.ORIGINAL) {
            AppIconRepository.select(icon)
            assertEquals(icon.key, AppIconPlatform.loadSelectedIconKey())
            assertTrue(AppIconPlatform.activateIcon(null))
            assertNull(AppIconPlatform.currentIconName())
            reloadRepository()
            assertEquals(icon, AppIconRepository.state.value.selected)
        }
    }

    @Test
    fun returningToOriginalOverridesAnOlderPlatformIcon() = runTest {
        AppIconRepository.select(AppIconOption.COPPER)
        AppIconRepository.select(AppIconOption.ORIGINAL)
        assertTrue(AppIconPlatform.activateIcon(AppIconOption.COPPER.platformName))
        reloadRepository()
        assertEquals(AppIconOption.ORIGINAL.key, AppIconPlatform.loadSelectedIconKey())
        assertEquals(AppIconOption.ORIGINAL, AppIconRepository.state.value.selected)
    }

    @Test
    fun existingPlatformIconIsSavedOnFirstLoad() = runTest {
        assertTrue(AppIconPlatform.activateIcon(AppIconOption.COPPER.platformName))
        AppIconRepository.ensureLoaded()
        assertEquals(AppIconOption.COPPER, AppIconRepository.state.value.selected)
        assertEquals(AppIconOption.COPPER.key, AppIconPlatform.loadSelectedIconKey())
    }

    @Test
    fun invalidSavedIconFallsBackToThePlatform() = runTest {
        AppIconPlatform.saveSelectedIconKey("unknown")
        assertTrue(AppIconPlatform.activateIcon(AppIconOption.EMERALD.platformName))
        AppIconRepository.ensureLoaded()
        assertEquals(AppIconOption.EMERALD, AppIconRepository.state.value.selected)
        assertEquals(AppIconOption.EMERALD.key, AppIconPlatform.loadSelectedIconKey())
    }

    @Test
    fun failedChangeKeepsTheSavedIcon() = runTest {
        AppIconRepository.select(AppIconOption.COPPER)
        val contextField = AppIconPlatform::class.java.getDeclaredField("context").apply {
            isAccessible = true
        }
        contextField.set(AppIconPlatform, null)
        try {
            AppIconRepository.select(AppIconOption.EMERALD)
            assertTrue(AppIconRepository.state.value.changeFailed)
            assertEquals(AppIconOption.COPPER, AppIconRepository.state.value.selected)
            assertNull(AppIconRepository.state.value.pending)
        } finally {
            AppIconPlatform.initialize(RuntimeEnvironment.getApplication())
        }
        reloadRepository()
        assertEquals(AppIconOption.COPPER.key, AppIconPlatform.loadSelectedIconKey())
        assertEquals(AppIconOption.COPPER, AppIconRepository.state.value.selected)
        assertFalse(AppIconRepository.state.value.changeFailed)
    }

    private fun reloadRepository() {
        AppIconPlatform.initialize(RuntimeEnvironment.getApplication())
        resetRepository()
        AppIconRepository.ensureLoaded()
    }

    private fun resetRepository() {
        AppIconRepository::class.java.getDeclaredField("hasLoaded").apply {
            isAccessible = true
            setBoolean(AppIconRepository, false)
        }
    }
}
