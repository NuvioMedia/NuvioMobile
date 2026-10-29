package com.nuvio.app

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.LocalScreenActive
import com.nuvio.app.core.ui.ScreenActivityEffect
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RootTabHostTest {
    @get:Rule
    val compose = createComposeRule()

    private val selected = mutableStateOf(AppScreenTab.Home)
    private val active = mutableStateOf(true)
    private val profileId = mutableStateOf(1)
    private val parentOwner = TestLifecycleOwner()
    private val mounts = mutableMapOf<AppScreenTab, Int>()
    private val disposals = mutableMapOf<AppScreenTab, Int>()
    private val tokens = mutableMapOf<AppScreenTab, Any>()
    private val owners = mutableMapOf<AppScreenTab, LifecycleOwner>()
    private val measures = mutableMapOf<AppScreenTab, Int>()

    @Test
    fun switchingAllTabsRetainsVisitedContentWithoutMountingUnvisitedTabs() {
        setContent()
        compose.runOnIdle { assertEquals(setOf(AppScreenTab.Home), mounts.keys) }
        val homeToken = tokens.getValue(AppScreenTab.Home)

        repeat(2) {
            for (tab in listOf(AppScreenTab.Search, AppScreenTab.Library, AppScreenTab.Settings, AppScreenTab.Home)) {
                select(tab)
                compose.onNodeWithTag(tab.name).assertIsDisplayed()
                AppScreenTab.entries.filter { it != tab }.forEach { hidden ->
                    compose.onNodeWithTag(hidden.name).assertDoesNotExist()
                }
            }
        }

        compose.runOnIdle {
            assertEquals(AppScreenTab.entries.associateWith { 1 }, mounts)
            assertTrue(disposals.isEmpty())
            assertSame(homeToken, tokens.getValue(AppScreenTab.Home))
        }
    }

    @Test
    fun inactiveTabsAreNotMeasuredAgain() {
        setContent()
        val homeMeasures = measures.getValue(AppScreenTab.Home)
        select(AppScreenTab.Search)

        compose.runOnIdle {
            assertEquals(homeMeasures, measures.getValue(AppScreenTab.Home))
            assertTrue(measures.getValue(AppScreenTab.Search) > 0)
        }
    }

    @Test
    fun activityEffectsFollowSelectionWithoutRecomposingTheirParentContent() {
        val compositions = mutableMapOf<AppScreenTab, Int>()
        val runningEffects = mutableSetOf<AppScreenTab>()
        setContent { tab ->
            SideEffect { compositions[tab] = compositions.getOrDefault(tab, 0) + 1 }
            ScreenActivityEffect { active ->
                if (!active) return@ScreenActivityEffect
                runningEffects += tab
                try {
                    awaitCancellation()
                } finally {
                    runningEffects -= tab
                }
            }
        }

        for (tab in listOf(AppScreenTab.Search, AppScreenTab.Library, AppScreenTab.Settings, AppScreenTab.Home)) {
            select(tab)
            compose.runOnIdle { assertEquals(setOf(tab), runningEffects) }
        }

        compose.runOnIdle { assertEquals(AppScreenTab.entries.associateWith { 1 }, compositions) }
    }

    @Test
    fun onlyTheSelectedTabCollectsAndChildLifecyclesFollowTheParent() {
        val source = MutableStateFlow(0)
        val observed = mutableMapOf<AppScreenTab, Int>()
        setContent { tab ->
            val value = source.collectAsStateWithLifecycle().value
            SideEffect { observed[tab] = value }
        }
        select(AppScreenTab.Search)
        compose.runOnIdle {
            assertEquals(Lifecycle.State.CREATED, owners.getValue(AppScreenTab.Home).lifecycle.currentState)
            assertEquals(Lifecycle.State.RESUMED, owners.getValue(AppScreenTab.Search).lifecycle.currentState)
            assertEquals(1, source.subscriptionCount.value)
            source.value = 1
        }
        compose.waitUntil(timeoutMillis = 5_000) { observed[AppScreenTab.Search] == 1 }
        compose.runOnIdle {
            assertEquals(0, observed[AppScreenTab.Home])
            assertEquals(1, observed[AppScreenTab.Search])
            parentOwner.lifecycle.currentState = Lifecycle.State.CREATED
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, source.subscriptionCount.value)
            assertEquals(Lifecycle.State.CREATED, owners.getValue(AppScreenTab.Search).lifecycle.currentState)
            parentOwner.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, source.subscriptionCount.value) }
    }

    @Test
    fun changingProfileDisposesVisitedContentAndItsLifecycles() {
        setContent()
        select(AppScreenTab.Search)
        val previousOwners = owners.toMap()
        val previousSearch = tokens.getValue(AppScreenTab.Search)
        compose.runOnIdle { profileId.value = 2 }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(mapOf(AppScreenTab.Home to 1, AppScreenTab.Search to 1), disposals)
            assertEquals(2, mounts[AppScreenTab.Search])
            assertNotSame(previousSearch, tokens.getValue(AppScreenTab.Search))
            previousOwners.values.forEach { owner ->
                assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
            }
        }
        select(AppScreenTab.Home)
        compose.runOnIdle { assertEquals(2, mounts[AppScreenTab.Home]) }
    }

    @Test
    fun previouslyVisitedTabsDoNotInterceptTheSelectedTabsBackHandler() {
        lateinit var dispatcher: OnBackPressedDispatcher
        val handled = mutableListOf<AppScreenTab>()
        setContent { tab ->
            dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            BackHandler { handled += tab }
        }
        for (tab in listOf(AppScreenTab.Search, AppScreenTab.Library, AppScreenTab.Settings, AppScreenTab.Home)) {
            select(tab)
            compose.runOnIdle { dispatcher.onBackPressed() }
        }

        compose.runOnIdle {
            assertEquals(
                listOf(AppScreenTab.Search, AppScreenTab.Library, AppScreenTab.Settings, AppScreenTab.Home),
                handled,
            )
        }
    }

    @Test
    fun switchingClearsOutgoingFocusAndHiddenTabsCannotTakeFocus() {
        val requesters = AppScreenTab.entries.associateWith { FocusRequester() }
        val focused = mutableMapOf<AppScreenTab, Boolean>()
        setContent { tab ->
            Box(
                Modifier.fillMaxSize()
                    .focusRequester(requesters.getValue(tab))
                    .onFocusChanged { focused[tab] = it.isFocused }
                    .focusable(),
            )
        }
        compose.runOnIdle { requesters.getValue(AppScreenTab.Home).requestFocus() }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(focused.getValue(AppScreenTab.Home)) }
        select(AppScreenTab.Search)

        compose.runOnIdle {
            assertFalse(focused.getValue(AppScreenTab.Home))
            requesters.getValue(AppScreenTab.Home).requestFocus()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(focused.getValue(AppScreenTab.Home)) }
        compose.runOnIdle { requesters.getValue(AppScreenTab.Search).requestFocus() }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(focused.getValue(AppScreenTab.Search)) }
    }

    @Test
    fun inactiveHostKeepsItsSelectedLayoutButBlocksInputAndAccessibility() {
        var clicks = 0
        var screenActive = true
        setContent {
            screenActive = LocalScreenActive.current
            Box(Modifier.fillMaxSize().clickable { clicks++ })
        }
        compose.onNodeWithTag("host").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(1, clicks)
            active.value = false
        }
        compose.waitForIdle()
        compose.onNodeWithTag("host").assertIsDisplayed().performTouchInput { click(center) }
        compose.onNodeWithTag(AppScreenTab.Home.name).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, clicks)
            assertFalse(screenActive)
            assertEquals(Lifecycle.State.CREATED, owners.getValue(AppScreenTab.Home).lifecycle.currentState)
            assertTrue(disposals.isEmpty())
        }
    }

    private fun select(tab: AppScreenTab) {
        compose.runOnIdle { selected.value = tab }
        compose.waitForIdle()
    }

    private fun setContent(extra: @Composable (AppScreenTab) -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides parentOwner) {
                RootTabHost(
                    selectedTab = selected.value,
                    modifier = Modifier.size(160.dp).testTag("host"),
                    active = active.value,
                    profileId = profileId.value,
                ) { tab ->
                    val token = remember {
                        mounts[tab] = mounts.getOrDefault(tab, 0) + 1
                        Any()
                    }
                    val owner = LocalLifecycleOwner.current
                    DisposableEffect(Unit) {
                        onDispose { disposals[tab] = disposals.getOrDefault(tab, 0) + 1 }
                    }
                    SideEffect {
                        tokens[tab] = token
                        owners[tab] = owner
                    }
                    Layout(
                        modifier = Modifier.fillMaxSize().testTag(tab.name),
                        content = { extra(tab) },
                    ) { measurables, constraints ->
                        measures[tab] = measures.getOrDefault(tab, 0) + 1
                        val children = measurables.map { it.measure(constraints) }
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            children.forEach { it.placeRelative(0, 0) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private class TestLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
    }
}
