package com.nuvio.app.features.profiles

import com.nuvio.app.core.ui.NuvioToastController
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.profile_already_active
import org.jetbrains.compose.resources.getString

internal fun routeProfileSelection(
    profile: NuvioProfile,
    isEditMode: Boolean,
    activeProfileIndex: Int? = null,
    onEditProfile: (NuvioProfile) -> Unit,
    onActiveProfileSelected: (NuvioProfile) -> Unit,
    onPinRequired: (NuvioProfile) -> Unit,
    onProfileSelected: (NuvioProfile) -> Unit,
) {
    when {
        isEditMode -> onEditProfile(profile)
        profile.profileIndex == activeProfileIndex -> onActiveProfileSelected(profile)
        profile.pinEnabled -> onPinRequired(profile)
        else -> onProfileSelected(profile)
    }
}

internal suspend fun showAlreadyActiveProfileToast(profile: NuvioProfile) {
    NuvioToastController.show(getString(Res.string.profile_already_active, profile.name))
}
