package com.nuvio.app.features.livetv.parental

import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Parental controls: groups (and On Demand categories, under "vod:<uid>") that need the PIN.
 * Adult groups can be locked automatically by name. Once unlocked with the PIN, a group stays
 * open until the app is closed. The PIN and the locked groups live in Live TV's preferences
 * under the TV app's keys ("parental_pin", "lock_adult_content", "locked_groups").
 */
internal object ParentalControls {
    private val ADULT = Regex(
        """(?i)(\bxxx\b|\badults?\b|\bporn|\b18\s*\+|\+\s*18\b|\berotic|\bplayboy\b|\bhustler\b|\bbrazzers\b|\bredlight\b|\bsexy?\b|\bnsfw\b)"""
    )

    private val _unlocked = MutableStateFlow<Set<String>>(emptySet())
    /** Group ids unlocked with the PIN since the app started. */
    val unlocked: StateFlow<Set<String>> = _unlocked.asStateFlow()

    fun isAdult(name: String): Boolean = ADULT.containsMatchIn(name)

    /** Adult channels (by their name or their group's name) never show in Recently watched or search. */
    fun isAdultChannel(ch: LiveChannel): Boolean = isAdult(ch.group) || isAdult(ch.name)

    fun enabled(s: LiveTvSettings = LiveTvPreferences.currentSettings()): Boolean = s.parentalPin.length >= 4

    /** Whether [id] (named [name]) needs the PIN right now. */
    fun isLocked(id: String, name: String, s: LiveTvSettings, lockedIds: Set<String>): Boolean {
        if (!enabled(s) || id in _unlocked.value) return false
        return id in lockedIds || (s.lockAdultContent && isAdult(name))
    }

    /** Whether the channel's group needs the PIN right now (the current settings). */
    fun isLocked(ch: LiveChannel): Boolean =
        isLocked(ch.groupId, ch.group, LiveTvPreferences.currentSettings(), LiveTvPreferences.currentUserState().lockedGroups)

    /** Whether the group is locked by choice (not by the adult-name rule), for the settings list. */
    fun isLockedByChoice(id: String): Boolean = id in LiveTvPreferences.currentUserState().lockedGroups

    /** True (and the group stays open) if [pin] is right. */
    fun unlock(id: String, pin: String, s: LiveTvSettings = LiveTvPreferences.currentSettings()): Boolean {
        if (pin.trim() != s.parentalPin) return false
        _unlocked.value = _unlocked.value + id
        return true
    }

    /** Locks every unlocked group again (leaving the app, or the PIN changed). */
    fun relock() { _unlocked.value = emptySet() }

    fun checkPin(pin: String, s: LiveTvSettings = LiveTvPreferences.currentSettings()): Boolean = pin.trim() == s.parentalPin

    /** Sets (or, with "", removes) the PIN. Everything unlocked locks again. */
    fun setPin(pin: String) {
        LiveTvPreferences.updateSettings { it.copy(parentalPin = pin.trim()) }
        relock()
    }
}
