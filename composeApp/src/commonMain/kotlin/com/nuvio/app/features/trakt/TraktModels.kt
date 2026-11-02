package com.nuvio.app.features.trakt

import kotlinx.serialization.Serializable

@Serializable
data class TraktAuthState(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val tokenType: String? = null,
    val createdAt: Long? = null,
    val expiresIn: Int? = null,
    val username: String? = null,
    val userSlug: String? = null,
    val deviceCode: String? = null,
    val deviceUserCode: String? = null,
    val deviceVerificationUrl: String? = null,
    val deviceCodeExpiresAtMillis: Long? = null,
    val devicePollIntervalSeconds: Int? = null,
) {
    val isAuthenticated: Boolean
        get() = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()

    val hasPendingDeviceCode: Boolean
        get() = !deviceCode.isNullOrBlank()
}

enum class TraktConnectionMode {
    DISCONNECTED,
    AWAITING_APPROVAL,
    CONNECTED,
}

data class TraktAuthUiState(
    val mode: TraktConnectionMode = TraktConnectionMode.DISCONNECTED,
    val credentialsConfigured: Boolean = true,
    val isLoading: Boolean = false,
    val username: String? = null,
    val tokenExpiresAtMillis: Long? = null,
    val deviceUserCode: String? = null,
    val deviceVerificationUrl: String? = null,
    val deviceCodeExpiresAtMillis: Long? = null,
    val pollIntervalSeconds: Int = TRAKT_DEFAULT_POLL_INTERVAL_SECONDS,
    val isPolling: Boolean = false,
    val authFlow: TraktAuthFlow = TraktAuthFlow.DEVICE_CODE,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

internal const val TRAKT_DEFAULT_POLL_INTERVAL_SECONDS = 5
const val TRAKT_ACTIVATION_URL = "https://trakt.tv/activate"

enum class TraktBrandAsset {
    Glyph,
    Wordmark,
}

@Serializable
enum class TraktListType {
    WATCHLIST,
    PERSONAL,
}

@Serializable
enum class TraktListPrivacy(val apiValue: String) {
    PRIVATE("private"),
    LINK("link"),
    FRIENDS("friends"),
    PUBLIC("public");

    companion object {
        fun fromApi(value: String?): TraktListPrivacy =
            entries.firstOrNull { it.apiValue.equals(value, ignoreCase = true) } ?: PRIVATE
    }
}

@Serializable
data class TraktListTab(
    val key: String,
    val title: String,
    val type: TraktListType,
    val traktListId: Long? = null,
    val slug: String? = null,
    val description: String? = null,
    val privacy: TraktListPrivacy? = null,
    val sortBy: String? = null,
    val sortHow: String? = null,
)

data class TraktMembershipSnapshot(
    val listMembership: Map<String, Boolean> = emptyMap(),
)

data class TraktMembershipChanges(
    val desiredMembership: Map<String, Boolean>,
)
