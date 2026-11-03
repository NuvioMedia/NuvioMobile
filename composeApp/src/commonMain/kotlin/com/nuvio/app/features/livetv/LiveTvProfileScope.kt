package com.nuvio.app.features.livetv

import com.nuvio.app.features.profiles.ProfileRepository

internal fun resolveLiveTvStorageProfileId(): Int {
    val activeProfile = ProfileRepository.state.value.activeProfile
    if (activeProfile == null) return ProfileRepository.activeProfileId

    return if (
        activeProfile.profileIndex != 1 &&
        (activeProfile.usesPrimaryAddons || activeProfile.usesPrimaryPlugins)
    ) {
        1
    } else {
        activeProfile.profileIndex
    }
}
