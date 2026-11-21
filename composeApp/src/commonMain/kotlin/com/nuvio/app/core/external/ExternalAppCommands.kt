package com.nuvio.app.core.external

import com.nuvio.app.AppScreenTab
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Commands from outside the Compose UI (the Android Auto screen's buttons and keyboard) into Nuvio's
 * navigation, plus a simple description of what's on screen so those buttons can adapt.
 */
object ExternalAppCommands {
    sealed interface Command {
        /** Go to the Search tab and search for [query]. */
        data class Search(val query: String) : Command

        /** Go back to the root of [tab]. */
        data class OpenTab(val tab: AppScreenTab) : Command

        /** Resume a Continue Watching item straight into playback. */
        data class Resume(val item: ContinueWatchingItem) : Command

        /** Open the Downloads page. */
        data object OpenDownloads : Command
    }

    private val _carProfileIndex = MutableStateFlow<Int?>(null)

    /** Profile the car window opens straight into (skipping "Who's watching"), or null. */
    val carProfileIndex: StateFlow<Int?> = _carProfileIndex.asStateFlow()

    private val _carLocked = MutableStateFlow(false)

    /** Car lock: no profile switching or Settings on the car screen. */
    val carLocked: StateFlow<Boolean> = _carLocked.asStateFlow()

    fun setCarProfile(profileIndex: Int?, locked: Boolean) {
        _carProfileIndex.value = profileIndex
        _carLocked.value = locked && profileIndex != null
    }

    /** Where the user is, coarsely. */
    enum class Screen { HOME, SEARCH, LIBRARY, SETTINGS, DETAIL, PLAYER }

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 8)
    val commands: SharedFlow<Command> = _commands.asSharedFlow()

    private val _pendingSearch = MutableStateFlow<String?>(null)

    /** A search the Search screen should run once it's showing; it clears it with [consumeSearch]. */
    val pendingSearch: StateFlow<String?> = _pendingSearch.asStateFlow()

    private val _screen = MutableStateFlow(Screen.HOME)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _continueWatching = MutableStateFlow<List<ContinueWatchingItem>>(emptyList())

    /** The first items of the home screen's Continue Watching row, newest first. */
    val continueWatching: StateFlow<List<ContinueWatchingItem>> = _continueWatching.asStateFlow()

    fun publishContinueWatching(items: List<ContinueWatchingItem>) {
        _continueWatching.value = items
    }

    fun send(command: Command): Boolean = _commands.tryEmit(command)

    fun setPendingSearch(query: String) {
        _pendingSearch.value = query
    }

    fun consumeSearch(query: String) {
        _pendingSearch.compareAndSet(query, null)
    }

    fun publishScreen(screen: Screen) {
        _screen.value = screen
    }
}
