package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object SimklAuthRepository : TrackingAuthProvider {
    private val log = Logger.withTag("SimklAuth")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val authorizationMutex = Mutex()
    private val tokenRefreshMutex = Mutex()

    private val _uiState = MutableStateFlow(SimklAuthUiState())
    val uiState: StateFlow<SimklAuthUiState> = _uiState.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    override val descriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.SIMKL,
        displayName = "Simkl",
        capabilities = setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE,
            TrackingCapability.SCROBBLE,
        ),
    )

    private var hasLoaded = false
    private var currentProfileId: Int = ProfileRepository.activeProfileId
    private var profileGeneration = 0L
    private var storedState = SimklStoredAuthState()
    private var accessToken: String? = null
    private var refreshToken: String? = null

    init {
        TrackingProviderRegistry.register(this)
    }

    override fun ensureLoaded() {
        val activeProfileId = ProfileRepository.activeProfileId
        if (!hasLoaded || currentProfileId != activeProfileId) {
            loadFromDisk(activeProfileId)
        }
    }

    override fun onProfileChanged() {
        loadFromDisk(ProfileRepository.activeProfileId)
    }

    override fun clearLocalState() {
        profileGeneration += 1L
        hasLoaded = false
        currentProfileId = 1
        storedState = SimklStoredAuthState()
        accessToken = null
        refreshToken = null
        publish()
    }

    override fun removeStoredProfile(profileId: Int) {
        SimklAuthStorage.removeProfile(profileId)
    }

    fun snapshot(): SimklAuthUiState {
        ensureLoaded()
        return uiState.value
    }

    fun hasRequiredCredentials(): Boolean = SimklConfig.CLIENT_ID.isNotBlank()

    fun onConnectRequested(): String? {
        ensureLoaded()
        if (!hasRequiredCredentials()) {
            publish(error = SimklAuthError.MISSING_CLIENT_ID)
            return null
        }

        val profileId = currentProfileId
        val material = generateSimklPkceMaterial()
        SimklAuthStorage.saveCodeVerifier(profileId, material.verifier)
        storedState = storedState.copy(
            pendingAuthorizationState = material.state,
            pendingAuthorizationStartedAtEpochMs = SimklPlatformClock.nowEpochMs(),
        )
        persistMetadata(profileId)
        publish(error = null)
        return authorizationUrl(material)
    }

    fun pendingAuthorizationUrl(): String? {
        ensureLoaded()
        val state = storedState.pendingAuthorizationState?.takeIf(String::isNotBlank) ?: return null
        val verifier = SimklAuthStorage.loadCodeVerifier(currentProfileId)?.takeIf(String::isNotBlank) ?: run {
            clearPendingAuthorization(currentProfileId)
            persistMetadata(currentProfileId)
            publish(error = SimklAuthError.AUTHORIZATION_EXPIRED)
            return null
        }
        if (isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
        ) {
            clearPendingAuthorization(currentProfileId)
            persistMetadata(currentProfileId)
            publish(error = SimklAuthError.AUTHORIZATION_EXPIRED)
            return null
        }
        return authorizationUrl(
            SimklPkceMaterial(
                verifier = verifier,
                challenge = SimklPkceCrypto.sha256(verifier.encodeToByteArray()).base64UrlWithoutPadding(),
                state = state,
            ),
        )
    }

    fun onCancelAuthorization() {
        ensureLoaded()
        clearPendingAuthorization(currentProfileId)
        persistMetadata(currentProfileId)
        publish(error = null)
    }

    override fun handleAuthCallback(url: String): Boolean {
        ensureLoaded()
        return when (val callback = parseSimklAuthCallback(url, SimklConfig.REDIRECT_URI)) {
            SimklAuthCallback.NotSimkl -> false
            SimklAuthCallback.Invalid -> {
                clearPendingAuthorization(currentProfileId)
                persistMetadata(currentProfileId)
                publish(error = SimklAuthError.INVALID_CALLBACK)
                true
            }
            is SimklAuthCallback.AuthorizationCode -> {
                val profileId = currentProfileId
                val generation = profileGeneration
                scope.launch { completeAuthorization(callback, profileId, generation) }
                true
            }
        }
    }

    fun onDisconnectRequested() {
        ensureLoaded()
        val profileId = currentProfileId
        accessToken = null
        refreshToken = null
        SimklAuthStorage.saveAccessToken(profileId, null)
        SimklAuthStorage.saveRefreshToken(profileId, null)
        clearPendingAuthorization(profileId)
        storedState = SimklStoredAuthState()
        persistMetadata(profileId)
        SimklSyncRepository.clearLocalState()
        publish(error = null)
    }

    /**
     * Returns a usable access token. AUTH V2 access tokens last seven days; refresh
     * proactively within one day of expiry and retain the stored grant on transient
     * failures so a network outage is never misreported as a logout.
     */
    internal suspend fun authorizedAccessToken(): String? {
        ensureLoaded()
        val currentToken = accessToken?.takeIf(String::isNotBlank)
        val refresh = refreshToken?.takeIf(String::isNotBlank)
        val now = SimklPlatformClock.nowEpochMs()

        // A missing/corrupt access-token entry must not strand a still-valid refresh
        // grant. Recover the short-lived token before declaring the account disconnected.
        if (currentToken == null) {
            if (refresh == null) return null
            return refreshAccessToken(
                force = true,
                profileId = currentProfileId,
                expectedProfileGeneration = profileGeneration,
                observedAccessToken = null,
            )
        }

        val expiresAt = storedState.tokenExpiresAtEpochMs ?: return currentToken
        if (refresh == null) {
            if (now >= expiresAt) {
                invalidateCredentials(SimklAuthError.AUTHORIZATION_EXPIRED)
                return null
            }
            return currentToken
        }

        if (storedState.refreshTokenExpiresAtEpochMs?.let { now >= it } == true) {
            invalidateCredentials(SimklAuthError.AUTHORIZATION_EXPIRED)
            return null
        }

        if (now >= expiresAt - ACCESS_TOKEN_REFRESH_SKEW_MS) {
            return refreshAccessToken(
                force = now >= expiresAt,
                profileId = currentProfileId,
                expectedProfileGeneration = profileGeneration,
                observedAccessToken = currentToken,
            ) ?: currentToken.takeIf { now < expiresAt }
        }

        return currentToken
    }

    /** A normal API 401 can be an expired access token; refresh once and retry the call. */
    internal suspend fun refreshAfterUnauthorized(usedAccessToken: String?): String? {
        ensureLoaded()
        val current = accessToken?.takeIf(String::isNotBlank) ?: return null
        // Another request may already have refreshed the shared grant.
        if (!usedAccessToken.isNullOrBlank() && current != usedAccessToken) return current
        return refreshAccessToken(
            force = true,
            profileId = currentProfileId,
            expectedProfileGeneration = profileGeneration,
            observedAccessToken = usedAccessToken,
        )
    }

    suspend fun refreshUserSettings(): String? {
        ensureLoaded()
        val expectedProfileId = currentProfileId
        val expectedProfileGeneration = profileGeneration
        authorizedAccessToken() ?: return null
        if (!isCurrentProfile(expectedProfileId, expectedProfileGeneration)) return null
        val fetched = fetchAndStoreUserSettings(
            expectedProfileId = expectedProfileId,
            expectedProfileGeneration = expectedProfileGeneration,
        )
        if (!fetched || !isCurrentProfile(expectedProfileId, expectedProfileGeneration)) return null
        return storedState.username
    }

    internal suspend fun synchronizeUserSettings(activityWatermark: String?) {
        ensureLoaded()
        val expectedProfileId = currentProfileId
        val expectedProfileGeneration = profileGeneration
        authorizedAccessToken() ?: return
        if (!isCurrentProfile(expectedProfileId, expectedProfileGeneration)) return
        when (simklSettingsRefreshAction(storedState, activityWatermark)) {
            SimklSettingsRefreshAction.NONE -> Unit
            SimklSettingsRefreshAction.RECORD_WATERMARK -> {
                if (!isCurrentProfile(expectedProfileId, expectedProfileGeneration)) return
                storedState = storedState.copy(settingsActivityWatermark = activityWatermark)
                persistMetadata(expectedProfileId)
            }
            SimklSettingsRefreshAction.FETCH -> {
                fetchAndStoreUserSettings(
                    activityWatermark = activityWatermark,
                    expectedProfileId = expectedProfileId,
                    expectedProfileGeneration = expectedProfileGeneration,
                )
            }
        }
    }

    private suspend fun completeAuthorization(
        callback: SimklAuthCallback.AuthorizationCode,
        profileId: Int,
        expectedProfileGeneration: Long,
    ) = authorizationMutex.withLock {
        if (!isCurrentProfile(profileId, expectedProfileGeneration)) return@withLock
        publish(isLoading = true, error = null)
        val expectedState = storedState.pendingAuthorizationState
        val verifier = SimklAuthStorage.loadCodeVerifier(profileId)
        val isExpired = isSimklAuthorizationExpired(
            startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
            nowEpochMs = SimklPlatformClock.nowEpochMs(),
        )
        if (expectedState.isNullOrBlank() || verifier.isNullOrBlank() || isExpired) {
            clearPendingAuthorization(profileId)
            persistMetadata(profileId)
            publish(isLoading = false, error = SimklAuthError.AUTHORIZATION_EXPIRED)
            return@withLock
        }
        if (!constantTimeEquals(callback.state, expectedState)) {
            clearPendingAuthorization(profileId)
            persistMetadata(profileId)
            publish(isLoading = false, error = SimklAuthError.INVALID_CALLBACK_STATE)
            return@withLock
        }

        val request = SimklTokenRequest(
            code = callback.code,
            clientId = SimklConfig.CLIENT_ID,
            codeVerifier = verifier,
            redirectUri = SimklConfig.REDIRECT_URI,
        )
        val response = try {
            SimklApi.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = "/oauth2/token",
                    body = json.encodeToString(request),
                    requiresAuthentication = false,
                    retryPolicy = SimklRetryPolicy.NEVER,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!isCurrentProfile(profileId, expectedProfileGeneration)) return@withLock
            log.w { "Simkl token exchange failed: ${error.message}" }
            clearPendingAuthorization(profileId)
            persistMetadata(profileId)
            publish(isLoading = false, error = SimklAuthError.TOKEN_EXCHANGE_FAILED)
            return@withLock
        }

        if (!isCurrentProfile(profileId, expectedProfileGeneration)) return@withLock
        val token = runCatching { json.decodeFromString<SimklTokenResponse>(response.body) }
            .getOrNull()
            ?.takeIf { it.isValid() }
        if (token == null) {
            clearPendingAuthorization(profileId)
            persistMetadata(profileId)
            publish(isLoading = false, error = SimklAuthError.INVALID_TOKEN_RESPONSE)
            return@withLock
        }

        accessToken = token.accessToken
        refreshToken = token.refreshToken
        SimklAuthStorage.saveAccessToken(profileId, token.accessToken)
        SimklAuthStorage.saveRefreshToken(profileId, token.refreshToken)
        clearPendingAuthorization(profileId)
        val now = SimklPlatformClock.nowEpochMs()
        storedState = storedState.copy(
            tokenExpiresAtEpochMs = now + requireNotNull(token.expiresIn) * 1_000L,
            refreshTokenExpiresAtEpochMs = now + REFRESH_TOKEN_LIFETIME_MS,
        )
        persistMetadata(profileId)
        publish(isLoading = false, error = null)
        fetchAndStoreUserSettings(expectedProfileId = profileId, expectedProfileGeneration = expectedProfileGeneration)
        if (isCurrentProfile(profileId, expectedProfileGeneration)) {
            SimklSyncRepository.refreshAsync(
                intent = TrackingRefreshIntent.INVALIDATED,
                origin = SimklRefreshOrigin.AUTHORIZATION,
            )
        }
    }

    private suspend fun refreshAccessToken(
        force: Boolean,
        profileId: Int,
        expectedProfileGeneration: Long,
        observedAccessToken: String?,
    ): String? = tokenRefreshMutex.withLock {
        if (!isCurrentProfile(profileId, expectedProfileGeneration)) return@withLock null
        val currentAccessToken = accessToken?.takeIf(String::isNotBlank)
        if (!observedAccessToken.isNullOrBlank() &&
            !currentAccessToken.isNullOrBlank() &&
            currentAccessToken != observedAccessToken
        ) {
            return@withLock currentAccessToken
        }

        val token = refreshToken?.takeIf(String::isNotBlank) ?: return@withLock null
        val now = SimklPlatformClock.nowEpochMs()
        if (storedState.refreshTokenExpiresAtEpochMs?.let { now >= it } == true) {
            invalidateCredentials(SimklAuthError.AUTHORIZATION_EXPIRED)
            return@withLock null
        }
        val accessExpiry = storedState.tokenExpiresAtEpochMs
        if (!force && accessExpiry != null && now < accessExpiry - ACCESS_TOKEN_REFRESH_SKEW_MS) {
            return@withLock currentAccessToken
        }

        val body = listOf(
            "grant_type" to "refresh_token",
            "client_id" to SimklConfig.CLIENT_ID,
            "refresh_token" to token,
        ).joinToString("&") { (key, value) ->
            "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
        }
        val response = try {
            httpRequestRaw(
                method = "POST",
                url = "$SIMKL_API_BASE_URL/oauth2/token",
                body = body,
                headers = simklRequestHeaders().toMutableMap().apply {
                    put("Content-Type", "application/x-www-form-urlencoded")
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Simkl token refresh transport failure: ${error.message}" }
            return@withLock null
        }

        if (!isCurrentProfile(profileId, expectedProfileGeneration) || refreshToken != token) {
            return@withLock null
        }

        when (simklTokenRefreshResponseAction(
            status = response.status,
            oauthErrorCode = runCatching {
                json.decodeFromString<SimklOAuthError>(response.body).error
            }.getOrNull(),
        )) {
            SimklTokenRefreshResponseAction.INVALIDATE -> {
                log.w { "Simkl refresh grant was rejected; reconnect is required" }
                invalidateCredentials(SimklAuthError.AUTHORIZATION_REVOKED)
                return@withLock null
            }
            SimklTokenRefreshResponseAction.TRANSIENT_FAILURE -> {
                log.w { "Simkl token refresh failed with HTTP ${response.status}; retaining credentials" }
                return@withLock null
            }
            SimklTokenRefreshResponseAction.ACCEPT -> Unit
        }

        val refreshed = runCatching { json.decodeFromString<SimklTokenResponse>(response.body) }
            .getOrNull()
            ?.takeIf { it.accessToken.isNotBlank() && (it.expiresIn ?: 0L) > 0L }
        if (refreshed == null) {
            log.w { "Simkl token refresh returned an invalid response; retaining credentials" }
            return@withLock null
        }

        val renewedRefreshToken = refreshed.refreshToken?.takeIf(String::isNotBlank) ?: token
        val refreshedAt = SimklPlatformClock.nowEpochMs()
        accessToken = refreshed.accessToken
        refreshToken = renewedRefreshToken
        SimklAuthStorage.saveAccessToken(profileId, refreshed.accessToken)
        SimklAuthStorage.saveRefreshToken(profileId, renewedRefreshToken)
        storedState = storedState.copy(
            tokenExpiresAtEpochMs = refreshedAt + requireNotNull(refreshed.expiresIn) * 1_000L,
            refreshTokenExpiresAtEpochMs = refreshedAt + REFRESH_TOKEN_LIFETIME_MS,
        )
        persistMetadata(profileId)
        publish(error = null)
        refreshed.accessToken
    }

    private suspend fun fetchAndStoreUserSettings(
        activityWatermark: String? = null,
        expectedProfileId: Int = currentProfileId,
        expectedProfileGeneration: Long = profileGeneration,
    ): Boolean {
        val response = try {
            SimklApi.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = "/users/settings",
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Failed to fetch Simkl user settings: ${error.message}" }
            return false
        }
        if (!isCurrentProfile(expectedProfileId, expectedProfileGeneration)) return false
        val settings = runCatching { json.decodeFromString<SimklUserSettingsResponse>(response.body) }
            .getOrNull() ?: return false
        storedState = storedState.copy(
            username = settings.user?.name,
            accountId = settings.account?.id,
            hasFetchedUserSettings = true,
            settingsActivityWatermark = activityWatermark ?: storedState.settingsActivityWatermark,
        )
        persistMetadata(expectedProfileId)
        publish(error = null)
        return true
    }

    private fun loadFromDisk(profileId: Int) {
        currentProfileId = profileId
        profileGeneration += 1L
        val generation = profileGeneration
        hasLoaded = true
        storedState = SimklAuthStorage.loadMetadataPayload(profileId)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { payload ->
                runCatching { json.decodeFromString<SimklStoredAuthState>(payload) }
                    .onFailure { error -> log.w { "Failed to parse Simkl auth metadata: ${error.message}" } }
                    .getOrNull()
            }
            ?: SimklStoredAuthState()
        accessToken = SimklAuthStorage.loadAccessToken(profileId)?.takeIf(String::isNotBlank)
        refreshToken = SimklAuthStorage.loadRefreshToken(profileId)?.takeIf(String::isNotBlank)

        val now = SimklPlatformClock.nowEpochMs()
        val refreshExpired = refreshToken != null &&
            storedState.refreshTokenExpiresAtEpochMs?.let { now >= it } == true
        val accessExpiredWithoutRefresh = accessToken != null &&
            refreshToken.isNullOrBlank() &&
            storedState.tokenExpiresAtEpochMs?.let { now >= it } == true
        when {
            refreshExpired -> {
                invalidateCredentials(SimklAuthError.AUTHORIZATION_EXPIRED)
            }
            accessExpiredWithoutRefresh -> {
                accessToken = null
                SimklAuthStorage.saveAccessToken(profileId, null)
                storedState = storedState.copy(tokenExpiresAtEpochMs = null)
                persistMetadata(profileId)
                publish(error = SimklAuthError.AUTHORIZATION_EXPIRED)
            }
            storedState.hasPendingAuthorization && isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = now,
            ) -> {
                clearPendingAuthorization(profileId)
                persistMetadata(profileId)
                publish(error = null)
            }
            else -> publish(error = null)
        }
        if (generation != profileGeneration) return
    }

    /**
     * Expired/revoked credentials should not erase the user's local sync snapshot.
     * Only an explicit user disconnect or account wipe clears local Simkl history.
     */
    private fun invalidateCredentials(error: SimklAuthError) {
        accessToken = null
        refreshToken = null
        SimklAuthStorage.saveAccessToken(currentProfileId, null)
        SimklAuthStorage.saveRefreshToken(currentProfileId, null)
        clearPendingAuthorization(currentProfileId)
        storedState = storedState.copy(
            tokenExpiresAtEpochMs = null,
            refreshTokenExpiresAtEpochMs = null,
            pendingAuthorizationState = null,
            pendingAuthorizationStartedAtEpochMs = null,
        )
        persistMetadata(currentProfileId)
        publish(isLoading = false, error = error)
    }

    private fun clearPendingAuthorization(profileId: Int) {
        SimklAuthStorage.saveCodeVerifier(profileId, null)
        storedState = storedState.copy(
            pendingAuthorizationState = null,
            pendingAuthorizationStartedAtEpochMs = null,
        )
    }

    private fun persistMetadata(profileId: Int = currentProfileId) {
        SimklAuthStorage.saveMetadataPayload(profileId, json.encodeToString(storedState))
    }

    private fun isCurrentProfile(profileId: Int, generation: Long): Boolean =
        currentProfileId == profileId &&
            profileGeneration == generation &&
            ProfileRepository.activeProfileId == profileId

    private fun publish(
        isLoading: Boolean = _uiState.value.isLoading,
        error: SimklAuthError? = _uiState.value.error,
    ) {
        val authenticated = !accessToken.isNullOrBlank() || !refreshToken.isNullOrBlank()
        _isAuthenticated.value = authenticated
        _uiState.value = SimklAuthUiState(
            mode = when {
                authenticated -> SimklConnectionMode.CONNECTED
                storedState.hasPendingAuthorization -> SimklConnectionMode.AWAITING_APPROVAL
                else -> SimklConnectionMode.DISCONNECTED
            },
            credentialsConfigured = hasRequiredCredentials(),
            isLoading = isLoading,
            username = storedState.username,
            accountId = storedState.accountId,
            tokenExpiresAtEpochMs = storedState.tokenExpiresAtEpochMs,
            pendingAuthorizationStartedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
            error = error,
        )
    }

    private fun authorizationUrl(material: SimklPkceMaterial): String =
        buildSimklAuthorizationUrl(
            clientId = SimklConfig.CLIENT_ID,
            redirectUri = SimklConfig.REDIRECT_URI,
            appName = SimklConfig.APP_NAME,
            appVersion = simklAppVersion,
            material = material,
        )

    private const val ACCESS_TOKEN_REFRESH_SKEW_MS = 24L * 60L * 60L * 1_000L
    private const val REFRESH_TOKEN_LIFETIME_MS = 180L * 24L * 60L * 60L * 1_000L
}

internal enum class SimklTokenRefreshResponseAction {
    ACCEPT,
    INVALIDATE,
    TRANSIENT_FAILURE,
}

internal fun simklTokenRefreshResponseAction(
    status: Int,
    oauthErrorCode: String? = null,
): SimklTokenRefreshResponseAction = when {
    status in 200..299 -> SimklTokenRefreshResponseAction.ACCEPT
    status == 400 && oauthErrorCode.equals("invalid_grant", ignoreCase = true) ->
        SimklTokenRefreshResponseAction.INVALIDATE
    else -> SimklTokenRefreshResponseAction.TRANSIENT_FAILURE
}

@Serializable
private data class SimklTokenRequest(
    val code: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("code_verifier") val codeVerifier: String,
    @SerialName("redirect_uri") val redirectUri: String,
    val scope: String = "media:read media:write",
    @SerialName("grant_type") val grantType: String = "authorization_code",
)

@Serializable
private data class SimklTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    val scope: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
) {
    fun isValid(): Boolean =
        accessToken.isNotBlank() &&
            !refreshToken.isNullOrBlank() &&
            (expiresIn ?: 0L) > 0L
}

@Serializable
private data class SimklOAuthError(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

@Serializable
private data class SimklUserSettingsResponse(
    val user: SimklUser? = null,
    val account: SimklAccount? = null,
)

@Serializable
private data class SimklUser(
    val name: String? = null,
)

@Serializable
private data class SimklAccount(
    val id: Long? = null,
)
