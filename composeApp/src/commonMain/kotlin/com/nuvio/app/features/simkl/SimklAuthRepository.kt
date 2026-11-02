package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingRefreshIntent
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
import com.nuvio.app.features.addons.httpRequestRaw
import kotlin.concurrent.Volatile

object SimklAuthRepository : TrackingAuthProvider {
    private val log = Logger.withTag("SimklAuth")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val authorizationMutex = Mutex()
    private val refreshMutex = Mutex()
    internal var tokenEngine: SimklHttpEngine = SimklHttpEngine { method, url, headers, body ->
        httpRequestRaw(method = method, url = url, headers = headers, body = body)
    }

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
    private var storedState = SimklStoredAuthState()

    /** Swapped as one reference so readers never see a new access token with a stale refresh token. */
    @Volatile
    private var tokens: SimklTokenSet? = null

    init {
        TrackingProviderRegistry.register(this)
    }

    override fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    override fun onProfileChanged() {
        loadFromDisk()
    }

    override fun clearLocalState() {
        hasLoaded = false
        storedState = SimklStoredAuthState()
        tokens = null
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

        val material = generateSimklPkceMaterial()
        SimklAuthStorage.saveCodeVerifier(material.verifier)
        storedState = storedState.copy(
            pendingAuthorizationState = material.state,
            pendingAuthorizationStartedAtEpochMs = SimklPlatformClock.nowEpochMs(),
        )
        persistMetadata()
        publish(error = null)
        return authorizationUrl(material)
    }

    fun pendingAuthorizationUrl(): String? {
        ensureLoaded()
        val state = storedState.pendingAuthorizationState?.takeIf(String::isNotBlank) ?: return null
        val verifier = SimklAuthStorage.loadCodeVerifier()?.takeIf(String::isNotBlank) ?: run {
            clearPendingAuthorization()
            persistMetadata()
            publish(error = SimklAuthError.AUTHORIZATION_EXPIRED)
            return null
        }
        if (isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
        ) {
            clearPendingAuthorization()
            persistMetadata()
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
        clearPendingAuthorization()
        persistMetadata()
        publish(error = null)
    }

    override fun handleAuthCallback(url: String): Boolean {
        ensureLoaded()
        return when (val callback = parseSimklAuthCallback(url, SimklConfig.REDIRECT_URI)) {
            SimklAuthCallback.NotSimkl -> false
            SimklAuthCallback.Invalid -> {
                clearPendingAuthorization()
                persistMetadata()
                publish(error = SimklAuthError.INVALID_CALLBACK)
                true
            }
            is SimklAuthCallback.AuthorizationCode -> {
                scope.launch { completeAuthorization(callback) }
                true
            }
        }
    }

    fun onDisconnectRequested() {
        ensureLoaded()
        clearStoredTokens()
        clearPendingAuthorization()
        storedState = SimklStoredAuthState()
        persistMetadata()
        SimklSyncRepository.clearLocalState()
        publish(error = null)
    }

    /**
     * Returns a usable access token, refreshing it first when it is within the expiry skew.
     * Legacy tokens without a refresh token are used as-is until the API rejects them.
     */
    internal suspend fun authorizedAccessToken(): String? {
        ensureLoaded()
        val current = tokens ?: return null
        return when (simklTokenRefreshDecision(current, SimklPlatformClock.nowEpochMs())) {
            SimklTokenRefreshDecision.NO_TOKEN -> null
            SimklTokenRefreshDecision.USE_CURRENT -> current.accessToken
            SimklTokenRefreshDecision.REFRESH -> refreshTokenIfNeeded(force = false)
                // A transient refresh failure keeps the current token; a 401 will force another try.
                ?: tokens?.accessToken
        }
    }

    internal fun onUnauthorizedResponse() {
        invalidateCredentials(SimklAuthError.AUTHORIZATION_REVOKED)
    }

    /** Called by the API client once per request after a 401: force a refresh and replay. */
    internal suspend fun recoverFromUnauthorized(rejectedAccessToken: String): SimklUnauthorizedRecovery {
        ensureLoaded()
        val current = tokens ?: return SimklUnauthorizedRecovery.Abort
        if (current.accessToken != rejectedAccessToken) {
            // Another caller already refreshed (the old token died with that refresh); replay.
            return SimklUnauthorizedRecovery.Retry(current.accessToken)
        }
        if (!current.canRefresh) return SimklUnauthorizedRecovery.SignOut
        val refreshed = refreshTokenIfNeeded(force = true, rejectedAccessToken = rejectedAccessToken)
        // null: either invalid_grant (already signed out with a reconnect message) or a transient
        // failure, where the session is kept and only this request fails.
        return refreshed?.let(SimklUnauthorizedRecovery::Retry) ?: SimklUnauthorizedRecovery.Abort
    }

    /**
     * Refreshes under [refreshMutex]. Returns the new access token, or null when no refresh happened.
     * On invalid_grant the user is signed out with [SimklAuthError.RECONNECT_REQUIRED].
     */
    private suspend fun refreshTokenIfNeeded(
        force: Boolean,
        rejectedAccessToken: String? = null,
    ): String? = refreshMutex.withLock {
        val current = tokens ?: return@withLock null
        if (rejectedAccessToken != null && current.accessToken != rejectedAccessToken) {
            return@withLock current.accessToken
        }
        if (!force && simklTokenRefreshDecision(current, SimklPlatformClock.nowEpochMs()) !=
            SimklTokenRefreshDecision.REFRESH
        ) {
            return@withLock current.accessToken
        }
        val refreshToken = current.refreshToken?.takeIf(String::isNotBlank) ?: return@withLock null

        val result = postTokenRequest(
            simklRefreshTokenForm(
                clientId = SimklConfig.CLIENT_ID,
                refreshToken = refreshToken,
            ),
        )
        if (tokens !== current) {
            // Signed out or replaced while the request was in flight; do not resurrect credentials.
            return@withLock tokens?.accessToken
        }
        when (result) {
            is SimklTokenResult.Success -> {
                val refreshed = result.token.toTokenSet(
                    nowEpochMs = SimklPlatformClock.nowEpochMs(),
                    previousRefreshToken = refreshToken,
                )
                storeTokens(refreshed, grantedScope = result.token.scope)
                publish(error = null)
                refreshed.accessToken
            }
            is SimklTokenResult.InvalidGrant -> {
                log.w { "Simkl rejected the refresh token; signing out" }
                invalidateCredentials(SimklAuthError.RECONNECT_REQUIRED)
                null
            }
            is SimklTokenResult.Failed -> {
                log.w { "Simkl token refresh failed: status=${result.status} error=${result.error}" }
                null
            }
        }
    }

    private suspend fun postTokenRequest(form: Map<String, String>): SimklTokenResult {
        val response = try {
            tokenEngine.execute(
                method = "POST",
                url = SIMKL_TOKEN_URL,
                headers = simklTokenRequestHeaders(),
                body = encodeSimklForm(form),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Simkl token request transport failure: ${error.message}" }
            return SimklTokenResult.Failed(status = null, error = "transport_failure")
        }
        return parseSimklTokenResult(response.status, response.body, json)
    }

    private fun storeTokens(newTokens: SimklTokenSet, grantedScope: String?) {
        // In-memory swap first: after a refresh the previous access token is already dead server-side.
        tokens = newTokens
        storedState = storedState.copy(
            tokenExpiresAtEpochMs = newTokens.expiresAtEpochMs,
            grantedScope = grantedScope ?: storedState.grantedScope,
        )
        SimklAuthStorage.saveAccessToken(newTokens.accessToken)
        SimklAuthStorage.saveRefreshToken(newTokens.refreshToken)
        persistMetadata()
    }

    private fun clearStoredTokens() {
        tokens = null
        SimklAuthStorage.saveAccessToken(null)
        SimklAuthStorage.saveRefreshToken(null)
    }

    suspend fun refreshUserSettings(): String? {
        authorizedAccessToken() ?: return null
        return if (fetchAndStoreUserSettings()) storedState.username else null
    }

    internal suspend fun synchronizeUserSettings(activityWatermark: String?) {
        authorizedAccessToken() ?: return
        when (simklSettingsRefreshAction(storedState, activityWatermark)) {
            SimklSettingsRefreshAction.NONE -> Unit
            SimklSettingsRefreshAction.RECORD_WATERMARK -> {
                storedState = storedState.copy(settingsActivityWatermark = activityWatermark)
                persistMetadata()
            }
            SimklSettingsRefreshAction.FETCH -> {
                fetchAndStoreUserSettings(activityWatermark)
            }
        }
    }

    private suspend fun completeAuthorization(callback: SimklAuthCallback.AuthorizationCode) =
        authorizationMutex.withLock {
            publish(isLoading = true, error = null)
            val expectedState = storedState.pendingAuthorizationState
            val verifier = SimklAuthStorage.loadCodeVerifier()
            val isExpired = isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
            if (expectedState.isNullOrBlank() || verifier.isNullOrBlank() || isExpired) {
                clearPendingAuthorization()
                persistMetadata()
                publish(isLoading = false, error = SimklAuthError.AUTHORIZATION_EXPIRED)
                return@withLock
            }
            if (!constantTimeEquals(callback.state, expectedState)) {
                clearPendingAuthorization()
                persistMetadata()
                publish(isLoading = false, error = SimklAuthError.INVALID_CALLBACK_STATE)
                return@withLock
            }

            val result = postTokenRequest(
                simklAuthorizationCodeForm(
                    clientId = SimklConfig.CLIENT_ID,
                    code = callback.code,
                    codeVerifier = verifier,
                    redirectUri = SimklConfig.REDIRECT_URI,
                ),
            )
            val token = when (result) {
                is SimklTokenResult.Success -> result.token
                is SimklTokenResult.InvalidGrant -> {
                    log.w { "Simkl token exchange rejected: ${result.error}" }
                    clearPendingAuthorization()
                    persistMetadata()
                    publish(isLoading = false, error = SimklAuthError.TOKEN_EXCHANGE_FAILED)
                    return@withLock
                }
                is SimklTokenResult.Failed -> {
                    log.w { "Simkl token exchange failed: status=${result.status} error=${result.error}" }
                    clearPendingAuthorization()
                    persistMetadata()
                    publish(
                        isLoading = false,
                        error = if (result.error == "invalid_token_response") {
                            SimklAuthError.INVALID_TOKEN_RESPONSE
                        } else {
                            SimklAuthError.TOKEN_EXCHANGE_FAILED
                        },
                    )
                    return@withLock
                }
            }

            clearPendingAuthorization()
            refreshMutex.withLock {
                storeTokens(
                    token.toTokenSet(nowEpochMs = SimklPlatformClock.nowEpochMs()),
                    grantedScope = token.scope,
                )
            }
            val grantedScope = token.scope
            if (grantedScope != null && "media:write" !in grantedScope.split(' ')) {
                log.w { "Simkl granted scope '${token.scope}' without media:write; writes will be rejected" }
            }
            publish(isLoading = false, error = null)
            fetchAndStoreUserSettings()
            SimklSyncRepository.refreshAsync(
                intent = TrackingRefreshIntent.INVALIDATED,
                origin = SimklRefreshOrigin.AUTHORIZATION,
            )
        }

    private suspend fun fetchAndStoreUserSettings(activityWatermark: String? = null): Boolean {
        val response = try {
            SimklApi.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.GET,
                    path = "/users/settings",
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Failed to fetch Simkl user settings: ${error.message}" }
            return false
        }
        val settings = runCatching { json.decodeFromString<SimklUserSettingsResponse>(response.body) }
            .getOrNull() ?: return false
        storedState = storedState.copy(
            username = settings.user?.name,
            accountId = settings.account?.id,
            hasFetchedUserSettings = true,
            settingsActivityWatermark = activityWatermark ?: storedState.settingsActivityWatermark,
        )
        persistMetadata()
        publish(error = null)
        return true
    }

    private fun loadFromDisk() {
        hasLoaded = true
        storedState = SimklAuthStorage.loadMetadataPayload()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { payload ->
                runCatching { json.decodeFromString<SimklStoredAuthState>(payload) }
                    .onFailure { error -> log.w { "Failed to parse Simkl auth metadata: ${error.message}" } }
                    .getOrNull()
            }
            ?: SimklStoredAuthState()
        tokens = resolveSimklStoredTokens(
            accessToken = SimklAuthStorage.loadAccessToken(),
            refreshToken = SimklAuthStorage.loadRefreshToken(),
            state = storedState,
        )
        if (storedState.hasPendingAuthorization && isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
        ) {
            clearPendingAuthorization()
            persistMetadata()
        }
        publish(error = null)
    }

    private fun invalidateCredentials(error: SimklAuthError) {
        clearStoredTokens()
        clearPendingAuthorization()
        storedState = SimklStoredAuthState()
        persistMetadata()
        SimklSyncRepository.clearLocalState()
        publish(isLoading = false, error = error)
    }

    private fun clearPendingAuthorization() {
        SimklAuthStorage.saveCodeVerifier(null)
        storedState = storedState.copy(
            pendingAuthorizationState = null,
            pendingAuthorizationStartedAtEpochMs = null,
        )
    }

    private fun persistMetadata() {
        SimklAuthStorage.saveMetadataPayload(json.encodeToString(storedState))
    }

    private fun publish(
        isLoading: Boolean = _uiState.value.isLoading,
        error: SimklAuthError? = _uiState.value.error,
    ) {
        val authenticated = !tokens?.accessToken.isNullOrBlank()
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
            material = material,
        )
}

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
