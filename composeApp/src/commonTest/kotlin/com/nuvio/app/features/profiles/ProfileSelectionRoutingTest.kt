package com.nuvio.app.features.profiles

import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileSelectionRoutingTest {
    @Test
    fun `unlocked profile emits one selection request`() {
        val profile = NuvioProfile(profileIndex = 2)
        var editRequests = 0
        var pinRequests = 0
        var selectionRequests = 0

        routeProfileSelection(
            profile = profile,
            isEditMode = false,
            activeProfileIndex = 1,
            onEditProfile = { editRequests += 1 },
            onActiveProfileSelected = { error("Another profile should not show an active profile toast") },
            onPinRequired = { pinRequests += 1 },
            onProfileSelected = { selectionRequests += 1 },
        )

        assertEquals(0, editRequests)
        assertEquals(0, pinRequests)
        assertEquals(1, selectionRequests)
    }

    @Test
    fun `locked profile requests verification without selecting`() {
        val profile = NuvioProfile(profileIndex = 2, pinEnabled = true)
        var pinRequests = 0
        var selectionRequests = 0

        routeProfileSelection(
            profile = profile,
            isEditMode = false,
            activeProfileIndex = 1,
            onEditProfile = {},
            onActiveProfileSelected = { error("Another profile should not show an active profile toast") },
            onPinRequired = { pinRequests += 1 },
            onProfileSelected = { selectionRequests += 1 },
        )

        assertEquals(1, pinRequests)
        assertEquals(0, selectionRequests)
    }

    @Test
    fun `edit mode routes only to profile editing`() {
        val profile = NuvioProfile(profileIndex = 2, pinEnabled = true)
        var editRequests = 0
        var pinRequests = 0
        var selectionRequests = 0

        routeProfileSelection(
            profile = profile,
            isEditMode = true,
            activeProfileIndex = profile.profileIndex,
            onEditProfile = { editRequests += 1 },
            onActiveProfileSelected = { error("Editing should not show an active profile toast") },
            onPinRequired = { pinRequests += 1 },
            onProfileSelected = { selectionRequests += 1 },
        )

        assertEquals(1, editRequests)
        assertEquals(0, pinRequests)
        assertEquals(0, selectionRequests)
    }

    @Test
    fun `active profile only requests a toast with its name`() {
        for (pinEnabled in listOf(false, true)) {
            val profile = NuvioProfile(profileIndex = 2, name = "Alex", pinEnabled = pinEnabled)
            val requests = mutableListOf<String>()

            routeProfileSelection(
                profile = profile,
                isEditMode = false,
                activeProfileIndex = profile.profileIndex,
                onEditProfile = { requests += "edit" },
                onActiveProfileSelected = { requests += "toast:${it.name}" },
                onPinRequired = { requests += "pin" },
                onProfileSelected = { requests += "select" },
            )

            assertEquals(listOf("toast:Alex"), requests)
        }
    }

    @Test
    fun `startup selection still allows entering a profile`() {
        for (pinEnabled in listOf(false, true)) {
            val profile = NuvioProfile(profileIndex = 1, pinEnabled = pinEnabled)
            val requests = mutableListOf<String>()

            routeProfileSelection(
                profile = profile,
                isEditMode = false,
                onEditProfile = { requests += "edit" },
                onActiveProfileSelected = { requests += "toast" },
                onPinRequired = { requests += "pin" },
                onProfileSelected = { requests += "select" },
            )

            assertEquals(listOf(if (pinEnabled) "pin" else "select"), requests)
        }
    }
}
