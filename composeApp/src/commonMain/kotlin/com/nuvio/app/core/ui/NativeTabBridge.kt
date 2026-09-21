package com.nuvio.app.core.ui

import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.AvatarCatalogItem
import com.nuvio.app.features.profiles.MAX_PROFILES
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.PinVerifyResult
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.profileAvatarImageUrl
import com.nuvio.app.features.profiles.showAlreadyActiveProfileToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal enum class NativeNavigationTab {
    Home,
    Search,
    LiveTv,
    Library,
    Settings,
    ;

    companion object {
        fun fromName(name: String): NativeNavigationTab =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Home
    }
}

internal object NativeTabBridge {
    private val _requestedTabs = MutableSharedFlow<NativeNavigationTab>(extraBufferCapacity = 1)
    val requestedTabs: SharedFlow<NativeNavigationTab> = _requestedTabs.asSharedFlow()

    fun requestTab(tabName: String) {
        _requestedTabs.tryEmit(NativeNavigationTab.fromName(tabName))
    }

    fun publishSelectedTab(tab: NativeNavigationTab) {
        publishNativeSelectedTab(tab.name)
    }

    fun publishTabBarVisible(visible: Boolean) {
        publishNativeTabBarVisible(visible && isLiquidGlassNativeTabBarSupported())
    }

    fun publishLiquidGlassEnabled(enabled: Boolean) {
        publishLiquidGlassNativeTabBarEnabled(enabled && isLiquidGlassNativeTabBarSupported())
    }

    fun publishAccentColor(hexColor: String) {
        publishNativeTabAccentColor(hexColor)
    }

    fun publishTabTitles(
        home: String,
        search: String,
        liveTv: String,
        library: String,
        profile: String,
    ) {
        publishNativeTabTitles(home, search, liveTv, library, profile)
    }

    fun publishProfileTabIcon(
        name: String?,
        avatarColorHex: String?,
        avatarImageUrl: String?,
        avatarBackgroundColorHex: String?,
    ) {
        publishNativeProfileTabIcon(
            name = name,
            avatarColorHex = avatarColorHex,
            avatarImageUrl = avatarImageUrl,
            avatarBackgroundColorHex = avatarBackgroundColorHex,
        )
    }
}

data class NativeProfileOption(
    val profileIndex: Int,
    val name: String,
    val avatarColorHex: String,
    val avatarImageUrl: String?,
    val avatarBackgroundColorHex: String?,
    val pinEnabled: Boolean,
    val active: Boolean,
)

data class NativeProfileSwitcherState(
    val profiles: List<NativeProfileOption>,
    val isLoaded: Boolean,
    val canAddProfile: Boolean,
)

class NativeProfileSwitcherController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val profileSelections = Channel<Int>(Channel.BUFFERED)
    private val manageProfileRequests = Channel<Unit>(Channel.BUFFERED)
    private var observationJob: Job? = null

    internal val selectedProfileIndices = profileSelections.receiveAsFlow()
    internal val requestedManageProfiles = manageProfileRequests.receiveAsFlow()

    fun currentState(): NativeProfileSwitcherState = nativeState(
        profilesLoaded = ProfileRepository.state.value.isLoaded,
        profiles = ProfileRepository.state.value.profiles,
        activeProfileIndex = ProfileRepository.state.value.activeProfile?.profileIndex,
        avatarsById = AvatarRepository.avatars.value.associateBy { it.id },
    )

    fun observeState(callback: (NativeProfileSwitcherState) -> Unit) {
        observationJob?.cancel()
        observationJob = scope.launch {
            combine(ProfileRepository.state, AvatarRepository.avatars) { state, avatars ->
                nativeState(
                    profilesLoaded = state.isLoaded,
                    profiles = state.profiles,
                    activeProfileIndex = state.activeProfile?.profileIndex,
                    avatarsById = avatars.associateBy { it.id },
                )
            }.collect { callback(it) }
        }
    }

    fun stopObserving() {
        observationJob?.cancel()
        observationJob = null
    }

    private fun nativeState(
        profilesLoaded: Boolean,
        profiles: List<NuvioProfile>,
        activeProfileIndex: Int?,
        avatarsById: Map<String, AvatarCatalogItem>,
    ): NativeProfileSwitcherState {
        val options = profiles.map { profile ->
            val avatar = profile.avatarId?.let(avatarsById::get)
            NativeProfileOption(
                profileIndex = profile.profileIndex,
                name = profile.name,
                avatarColorHex = profile.avatarColorHex,
                avatarImageUrl = profileAvatarImageUrl(profile, avatar),
                avatarBackgroundColorHex = avatar?.bgColor,
                pinEnabled = profile.pinEnabled,
                active = profile.profileIndex == activeProfileIndex,
            )
        }
        return NativeProfileSwitcherState(
            profiles = options,
            isLoaded = profilesLoaded,
            canAddProfile = profiles.size < MAX_PROFILES,
        )
    }

    fun chooseProfile(
        profileIndex: Int,
        pin: String?,
        completion: (PinVerifyResult) -> Unit,
    ) {
        scope.launch {
            val activeProfile = ProfileRepository.state.value.activeProfile
            if (activeProfile != null && profileIndex == activeProfile.profileIndex) {
                showAlreadyActiveProfileToast(activeProfile)
                completion(PinVerifyResult(unlocked = true))
                return@launch
            }
            val profile = ProfileRepository.state.value.profiles
                .firstOrNull { it.profileIndex == profileIndex }
            if (profile == null) {
                completion(PinVerifyResult(message = null))
                return@launch
            }
            val result = if (profile.pinEnabled) {
                ProfileRepository.verifyPin(profileIndex, pin.orEmpty())
            } else {
                PinVerifyResult(unlocked = true)
            }
            if (result.unlocked && profileIndex != ProfileRepository.state.value.activeProfile?.profileIndex) {
                profileSelections.trySend(profileIndex)
            }
            completion(result)
        }
    }

    fun requestManageProfiles() {
        manageProfileRequests.trySend(Unit)
    }
}

fun nativeTabSelect(tabName: String) {
    NativeTabBridge.requestTab(tabName)
}

internal expect fun isLiquidGlassNativeTabBarSupported(): Boolean

internal expect fun publishLiquidGlassNativeTabBarEnabled(enabled: Boolean)

internal expect fun publishNativeTabBarVisible(visible: Boolean)

internal expect fun publishNativeSelectedTab(tabName: String)

internal expect fun publishNativeTabAccentColor(hexColor: String)

internal expect fun publishNativeTabTitles(
    home: String,
    search: String,
    liveTv: String,
    library: String,
    profile: String,
)

internal expect fun publishNativeProfileTabIcon(
    name: String?,
    avatarColorHex: String?,
    avatarImageUrl: String?,
    avatarBackgroundColorHex: String?,
)
