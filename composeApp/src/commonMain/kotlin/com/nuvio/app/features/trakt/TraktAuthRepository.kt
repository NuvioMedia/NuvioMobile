package com.nuvio.app.features.trakt

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.addons.httpPostJsonWithHeaders
import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.simkl.SimklPkceMaterial
import com.nuvio.app.features.simkl.constantTimeEquals
import com.nuvio.app.features.simkl.generateSimklPkceMaterial
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.StringResource
import kotlinx.coroutines.runBlocking

object TraktAuthRepository : TrackingAuthProvider {
    private const val BASE_URL = "https://api.trakt.tv"
    private const val API_VERSION = "2"

    private val log = Logger.withTag("TraktAuth")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    // Request bodies leave out null fields so client_secret is omitted for apps that have none.
    private val requestJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshMutex = Mutex()
    private val deviceStartMutex = Mutex()

    private val _uiState = MutableStateFlow(TraktAuthUiState())
    val uiState: StateFlow<TraktAuthUiState> = _uiState.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    override val descriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.TRAKT,
        displayName = "Trakt",
        capabilities = setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE,
            TrackingCapability.SCROBBLE,
            TrackingCapability.COMMENTS,
            TrackingCapability.RECOMMENDATIONS,
        ),
    )

    init {
        TrackingProviderRegistry.register(this)
    }

    private var hasLoaded = false
    private var currentProfileId: Int = 1
    private var profileGeneration: Long = 0L
    private var authState = TraktAuthState()
    private var pollJob: Job? = null
    private var isPolling = false
    private var loopbackJob: Job? = null
    private var loopbackPending = false
    private var loopbackAuthorizeUrl: String? = null

    override fun ensureLoaded() {
        ensureLoaded(ProfileRepository.activeProfileId)
    }

    override fun onProfileChanged() {
        onProfileChanged(ProfileRepository.activeProfileId)
    }

    fun ensureLoaded(profileId: Int) {
        if (hasLoaded && currentProfileId == profileId) return
        loadFromDisk(profileId)
    }

    fun onProfileChanged(profileId: Int) {
        loadFromDisk(profileId)
    }

    override fun clearLocalState() {
        TraktWatchedShowSnapshotRepository.clear()
        stopPendingAuthorization()
        hasLoaded = false
        currentProfileId = 1
        profileGeneration += 1L
        authState = TraktAuthState()
        publish()
    }

    override fun removeStoredProfile(profileId: Int) {
        TraktAuthStorage.removeProfile(profileId)
    }

    fun snapshot(profileId: Int = ProfileRepository.activeProfileId): TraktAuthUiState {
        ensureLoaded(profileId)
        return _uiState.value
    }

    /** Trakt's PKCE apps have no client secret, so only the client ID is required. */
    fun hasRequiredCredentials(): Boolean = TraktConfig.CLIENT_ID.isNotBlank()

    fun authFlow(): TraktAuthFlow =
        selectTraktAuthFlow(TraktConfig.REDIRECT_URI, traktLoopbackReceiverSupported)

    /**
     * Starts Trakt sign-in. For the loopback flow this returns the authorize URL to open in
     * the browser; for the device-code flow it requests a code (shown by the UI) and returns null.
     */
    suspend fun onConnectRequested(profileId: Int = ProfileRepository.activeProfileId): String? {
        ensureLoaded(profileId)
        if (!hasRequiredCredentials()) {
            publish(errorMessage = localizedString(Res.string.trakt_missing_credentials))
            return null
        }
        if (authState.isAuthenticated) return null

        return when (authFlow()) {
            TraktAuthFlow.LOOPBACK_PKCE -> startLoopbackAuthorization(profileId)
            TraktAuthFlow.DEVICE_CODE -> {
                startDeviceAuthorization(profileId)
                null
            }
        }
    }

    /** The URL to reopen for a sign-in that is already in progress, or null when none is. */
    fun pendingAuthorizationUrl(profileId: Int = ProfileRepository.activeProfileId): String? {
        ensureLoaded(profileId)
        return when (authFlow()) {
            TraktAuthFlow.LOOPBACK_PKCE ->
                loopbackAuthorizeUrl?.takeIf { loopbackPending && loopbackJob?.isActive == true }
            TraktAuthFlow.DEVICE_CODE -> {
                if (!hasActiveDeviceCode()) return null
                startDevicePolling(profileId)
                authState.deviceVerificationUrl?.takeIf(String::isNotBlank) ?: TRAKT_ACTIVATION_URL
            }
        }
    }

    /** Resumes polling for a device code that was issued before the app was closed. */
    fun resumeDeviceFlowPolling(profileId: Int = ProfileRepository.activeProfileId) {
        ensureLoaded(profileId)
        if (authState.hasPendingDeviceCode) startDevicePolling(profileId)
    }

    fun onCancelAuthorization(profileId: Int = ProfileRepository.activeProfileId) {
        ensureLoaded(profileId)
        stopPendingAuthorization()
        clearDeviceFlow()
        persist(profileId)
        publish(isLoading = false, statusMessage = null, errorMessage = null)
    }

    fun onCancelDeviceFlow(profileId: Int = ProfileRepository.activeProfileId) {
        onCancelAuthorization(profileId)
    }

    fun onAuthLaunchFailed(reason: String) {
        publish(errorMessage = reason)
    }

    suspend fun authorizedHeaders(profileId: Int = currentProfileId): Map<String, String>? {
        ensureLoaded(profileId)
        if (!authState.isAuthenticated) return null

        val hasValidToken = refreshTokenIfNeeded(force = false, profileId = profileId)
        if (!hasValidToken) return null

        val accessToken = authState.accessToken?.trim().orEmpty()
        if (accessToken.isBlank()) return null

        return mapOf(
            "trakt-api-version" to API_VERSION,
            "trakt-api-key" to TraktConfig.CLIENT_ID,
            "Authorization" to "Bearer $accessToken",
        )
    }

    suspend fun refreshUserSettings(profileId: Int = currentProfileId): String? {
        ensureLoaded(profileId)
        val headers = authorizedHeaders(profileId) ?: return null
        val response = runCatching {
            httpGetTextWithHeaders(
                url = "$BASE_URL/users/settings",
                headers = headers,
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Failed to fetch Trakt user settings: ${error.message}" }
        }.getOrNull() ?: return null

        val parsed = runCatching {
            json.decodeFromString<TraktUserSettingsResponse>(response)
        }.getOrNull() ?: return null

        authState = authState.copy(
            username = parsed.user?.username,
            userSlug = parsed.user?.ids?.slug,
        )
        persist(profileId)
        publish()
        return authState.username
    }

    fun onDisconnectRequested(profileId: Int = currentProfileId) {
        ensureLoaded(profileId)
        scope.launch {
            disconnect(profileId)
        }
    }

    private fun hasActiveDeviceCode(): Boolean {
        if (!authState.hasPendingDeviceCode) return false
        val expiresAt = authState.deviceCodeExpiresAtMillis ?: return false
        return TraktPlatformClock.nowEpochMs() < expiresAt
    }

    private suspend fun startDeviceAuthorization(profileId: Int) {
        // Reuse a still-valid device code instead of requesting a new one: /oauth/device/code
        // is tightly rate-limited by Trakt, so reopening the sheet or tapping Connect twice
        // must not hit it again.
        if (hasActiveDeviceCode()) {
            publish(
                isLoading = false,
                statusMessage = localizedString(Res.string.trakt_device_enter_code_status),
                errorMessage = null,
            )
            startDevicePolling(profileId)
            return
        }
        // Guard against re-entry from rapid taps for the same reason.
        if (!deviceStartMutex.tryLock()) return
        try {
            val generation = profileGeneration
            publish(isLoading = true, statusMessage = null, errorMessage = null)

            var response = requestDeviceCode() ?: run {
                publish(isLoading = false, errorMessage = localizedString(Res.string.trakt_device_network_failed))
                return
            }
            if (response.status == 429) {
                val retryAfterSeconds = traktHeaderValue(response.headers, "Retry-After")?.trim()?.toLongOrNull()
                if (retryAfterSeconds != null && retryAfterSeconds in 1L..10L) {
                    delay(retryAfterSeconds * 1_000L)
                    response = requestDeviceCode() ?: run {
                        publish(isLoading = false, errorMessage = localizedString(Res.string.trakt_device_network_failed))
                        return
                    }
                }
            }
            if (generation != profileGeneration) {
                publish(isLoading = false)
                return
            }

            if (response.status == 429) {
                val retryAfterSeconds = traktHeaderValue(response.headers, "Retry-After")?.trim()?.toLongOrNull()
                publish(
                    isLoading = false,
                    errorMessage = localizedString(
                        Res.string.trakt_device_rate_limited_minutes,
                        traktRateLimitedMinutes(retryAfterSeconds),
                    ),
                )
                return
            }
            if (response.status !in 200..299) {
                publish(
                    isLoading = false,
                    errorMessage = localizedString(Res.string.trakt_device_start_failed_code, response.status),
                )
                return
            }

            val parsed = runCatching { json.decodeFromString<TraktDeviceCodeResponse>(response.body) }
                .getOrNull()
                ?.takeIf { it.deviceCode.isNotBlank() && it.userCode.isNotBlank() }
            if (parsed == null) {
                publish(isLoading = false, errorMessage = localizedString(Res.string.trakt_invalid_token_response))
                return
            }

            authState = authState.copy(
                deviceCode = parsed.deviceCode,
                deviceUserCode = parsed.userCode,
                deviceVerificationUrl = parsed.verificationUrl.takeIf(String::isNotBlank) ?: TRAKT_ACTIVATION_URL,
                deviceCodeExpiresAtMillis = TraktPlatformClock.nowEpochMs() + parsed.expiresIn * 1_000L,
                devicePollIntervalSeconds = parsed.interval.coerceAtLeast(1),
            )
            persist(profileId)
            publish(
                isLoading = false,
                statusMessage = localizedString(Res.string.trakt_device_enter_code_status),
                errorMessage = null,
            )
            startDevicePolling(profileId)
        } finally {
            deviceStartMutex.unlock()
        }
    }

    private suspend fun requestDeviceCode(): RawHttpResponse? {
        val body = requestJson.encodeToString(TraktDeviceCodeRequest(clientId = TraktConfig.CLIENT_ID))
        return runCatching {
            httpRequestRaw(
                method = "POST",
                url = "$BASE_URL/oauth/device/code",
                body = body,
                headers = oauthHeaders(),
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Failed to request Trakt device code: ${error.message}" }
        }.getOrNull()
    }

    private fun startDevicePolling(profileId: Int) {
        if (pollJob?.isActive == true) return
        if (!authState.hasPendingDeviceCode) return
        val generation = profileGeneration
        pollJob = scope.launch {
            isPolling = true
            publish()
            try {
                pollDeviceToken(profileId, generation)
            } finally {
                val ownJob = coroutineContext[Job]
                if (pollJob == null || pollJob === ownJob) {
                    isPolling = false
                    if (pollJob === ownJob) pollJob = null
                    if (generation == profileGeneration) publish()
                }
            }
        }
    }

    private suspend fun pollDeviceToken(profileId: Int, generation: Long) {
        while (generation == profileGeneration) {
            val deviceCode = authState.deviceCode?.takeIf(String::isNotBlank) ?: return
            val intervalSeconds = (authState.devicePollIntervalSeconds ?: TRAKT_DEFAULT_POLL_INTERVAL_SECONDS)
                .coerceAtLeast(1)
            delay(intervalSeconds * 1_000L)
            if (generation != profileGeneration || authState.deviceCode != deviceCode) return

            val expiresAt = authState.deviceCodeExpiresAtMillis
            if (expiresAt != null && TraktPlatformClock.nowEpochMs() >= expiresAt) {
                endDeviceFlow(profileId, localizedString(Res.string.trakt_device_code_expired))
                return
            }

            val body = requestJson.encodeToString(
                TraktDeviceTokenRequest(
                    code = deviceCode,
                    clientId = TraktConfig.CLIENT_ID,
                    clientSecret = TraktConfig.CLIENT_SECRET.takeIf(String::isNotBlank),
                ),
            )
            val response = runCatching {
                httpRequestRaw(
                    method = "POST",
                    url = "$BASE_URL/oauth/device/token",
                    body = body,
                    headers = oauthHeaders(),
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w { "Trakt device token poll transport failure: ${error.message}" }
            }.getOrNull()
            if (generation != profileGeneration || authState.deviceCode != deviceCode) return

            if (response == null) {
                // Usually a brief network drop while the user is in the browser; the code's
                // expiry bounds how long this can go on.
                publish(statusMessage = localizedString(Res.string.trakt_device_network_retrying))
                continue
            }

            when (val outcome = traktDeviceTokenPollOutcome(response.status, intervalSeconds)) {
                TraktDeviceTokenPollOutcome.Approved -> {
                    val parsed = runCatching { json.decodeFromString<TraktTokenResponse>(response.body) }.getOrNull()
                    if (parsed == null) {
                        endDeviceFlow(profileId, localizedString(Res.string.trakt_invalid_token_response))
                    } else {
                        storeTokens(parsed, profileId)
                    }
                    return
                }

                TraktDeviceTokenPollOutcome.Pending ->
                    publish(statusMessage = localizedString(Res.string.trakt_device_waiting_approval))

                is TraktDeviceTokenPollOutcome.SlowDown -> {
                    authState = authState.copy(devicePollIntervalSeconds = outcome.pollIntervalSeconds)
                    persist(profileId)
                    publish(statusMessage = localizedString(Res.string.trakt_device_slow_down))
                }

                TraktDeviceTokenPollOutcome.InvalidCode -> {
                    endDeviceFlow(profileId, localizedString(Res.string.trakt_device_code_invalid))
                    return
                }

                TraktDeviceTokenPollOutcome.AlreadyUsed -> {
                    endDeviceFlow(profileId, localizedString(Res.string.trakt_device_code_used))
                    return
                }

                TraktDeviceTokenPollOutcome.Expired -> {
                    endDeviceFlow(profileId, localizedString(Res.string.trakt_device_code_expired))
                    return
                }

                TraktDeviceTokenPollOutcome.Denied -> {
                    endDeviceFlow(profileId, localizedString(Res.string.trakt_authorization_denied))
                    return
                }

                is TraktDeviceTokenPollOutcome.Failed -> {
                    endDeviceFlow(
                        profileId,
                        localizedString(Res.string.trakt_device_poll_failed_code, outcome.status),
                    )
                    return
                }
            }
        }
    }

    private fun endDeviceFlow(profileId: Int, errorMessage: String) {
        clearDeviceFlow()
        persist(profileId)
        publish(isLoading = false, statusMessage = null, errorMessage = errorMessage)
    }

    private fun startLoopbackAuthorization(profileId: Int): String? {
        val target = parseTraktLoopbackRedirect(TraktConfig.REDIRECT_URI) ?: return null
        if (loopbackPending && loopbackJob?.isActive == true) return loopbackAuthorizeUrl

        val material = generateSimklPkceMaterial()
        val authorizeUrl = buildTraktAuthorizeUrl(
            clientId = TraktConfig.CLIENT_ID,
            redirectUri = TraktConfig.REDIRECT_URI,
            state = material.state,
            codeChallenge = material.challenge,
        )
        loopbackJob?.cancel()
        loopbackPending = true
        loopbackAuthorizeUrl = authorizeUrl
        publish(
            isLoading = false,
            statusMessage = localizedString(Res.string.trakt_complete_sign_in_browser),
            errorMessage = null,
        )
        val generation = profileGeneration
        loopbackJob = scope.launch {
            runLoopbackAuthorization(target, material, profileId, generation)
        }
        return authorizeUrl
    }

    private suspend fun runLoopbackAuthorization(
        target: TraktLoopbackTarget,
        material: SimklPkceMaterial,
        profileId: Int,
        generation: Long,
    ) {
        val responseHtml = traktLoopbackResponseHtml(localizedString(Res.string.trakt_loopback_return_to_app))
        val requestTarget = try {
            awaitTraktLoopbackRedirect(
                port = target.port,
                path = target.path,
                timeoutMillis = TRAKT_LOOPBACK_TIMEOUT_MS,
                responseHtml = responseHtml,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w { "Trakt loopback listener failed on port ${target.port}: ${error.message}" }
            if (generation == profileGeneration) {
                finishLoopback(localizedString(Res.string.trakt_loopback_listener_failed, target.port))
            }
            return
        }
        if (generation != profileGeneration) return
        if (requestTarget == null) {
            finishLoopback(localizedString(Res.string.trakt_loopback_timed_out))
            return
        }

        when (val callback = parseTraktLoopbackCallback(requestTarget)) {
            is TraktLoopbackCallback.Error -> finishLoopback(
                callback.description ?: localizedString(Res.string.trakt_authorization_denied),
            )

            TraktLoopbackCallback.Invalid -> finishLoopback(localizedString(Res.string.trakt_missing_auth_code))

            is TraktLoopbackCallback.AuthorizationCode -> {
                if (!constantTimeEquals(callback.state, material.state)) {
                    finishLoopback(localizedString(Res.string.trakt_invalid_callback_state))
                } else {
                    exchangeAuthorizationCode(callback.code, material.verifier, profileId, generation)
                }
            }
        }
    }

    private fun finishLoopback(errorMessage: String?) {
        loopbackPending = false
        loopbackAuthorizeUrl = null
        publish(isLoading = false, statusMessage = null, errorMessage = errorMessage)
    }

    private suspend fun exchangeAuthorizationCode(
        code: String,
        codeVerifier: String,
        profileId: Int,
        generation: Long,
    ) {
        publish(isLoading = true, errorMessage = null)
        val body = requestJson.encodeToString(
            TraktAuthorizationCodeRequest(
                code = code,
                clientId = TraktConfig.CLIENT_ID,
                clientSecret = TraktConfig.CLIENT_SECRET.takeIf(String::isNotBlank),
                redirectUri = TraktConfig.REDIRECT_URI,
                codeVerifier = codeVerifier,
            ),
        )

        val response = runCatching {
            httpRequestRaw(
                method = "POST",
                url = "$BASE_URL/oauth/token",
                body = body,
                headers = oauthHeaders(),
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Failed to exchange Trakt auth code: ${error.message}" }
        }.getOrNull()
        if (generation != profileGeneration) return

        if (response == null || response.status !in 200..299) {
            response?.let { log.w { "Trakt auth code exchange failed with HTTP ${it.status}" } }
            finishLoopback(localizedString(Res.string.trakt_sign_in_complete_failed))
            return
        }

        val parsed = runCatching {
            json.decodeFromString<TraktTokenResponse>(response.body)
        }.getOrNull()
        if (parsed == null) {
            finishLoopback(localizedString(Res.string.trakt_invalid_token_response))
            return
        }

        loopbackPending = false
        loopbackAuthorizeUrl = null
        storeTokens(parsed, profileId)
    }

    private suspend fun storeTokens(parsed: TraktTokenResponse, profileId: Int) {
        authState = authState.copy(
            accessToken = parsed.accessToken,
            refreshToken = parsed.refreshToken,
            tokenType = parsed.tokenType,
            createdAt = parsed.createdAt,
            expiresIn = parsed.expiresIn,
            deviceCode = null,
            deviceUserCode = null,
            deviceVerificationUrl = null,
            deviceCodeExpiresAtMillis = null,
            devicePollIntervalSeconds = null,
        )
        persist(profileId)
        refreshUserSettings(profileId)
        publish(
            isLoading = false,
            statusMessage = localizedString(Res.string.trakt_connected_status),
            errorMessage = null,
        )
    }

    private fun oauthHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Content-Type" to "application/json",
        "trakt-api-version" to API_VERSION,
        "trakt-api-key" to TraktConfig.CLIENT_ID,
    )

    private suspend fun disconnect(profileId: Int = currentProfileId) {
        stopPendingAuthorization()
        publish(isLoading = true, errorMessage = null)

        val token = authState.accessToken?.takeIf { it.isNotBlank() }
        if (!token.isNullOrBlank() && hasRequiredCredentials()) {
            val body = requestJson.encodeToString(
                TraktRevokeRequest(
                    token = token,
                    clientId = TraktConfig.CLIENT_ID,
                    clientSecret = TraktConfig.CLIENT_SECRET.takeIf(String::isNotBlank),
                ),
            )
            runCatching {
                httpPostJsonWithHeaders(
                    url = "$BASE_URL/oauth/revoke",
                    body = body,
                    headers = emptyMap(),
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w { "Failed to revoke Trakt token: ${error.message}" }
            }
        }

        authState = TraktAuthState()
        persist(profileId)
        publish(
            isLoading = false,
            statusMessage = localizedString(Res.string.trakt_disconnected_status),
            errorMessage = null,
        )
    }

    private suspend fun refreshTokenIfNeeded(force: Boolean, profileId: Int = currentProfileId): Boolean = refreshMutex.withLock {
        if (!hasRequiredCredentials()) return@withLock false
        val refreshToken = authState.refreshToken?.takeIf { it.isNotBlank() }
            ?: return@withLock false

        if (!force && !isTokenExpiredOrExpiring(authState)) {
            return@withLock true
        }

        val body = requestJson.encodeToString(
            TraktRefreshTokenRequest(
                refreshToken = refreshToken,
                clientId = TraktConfig.CLIENT_ID,
                clientSecret = TraktConfig.CLIENT_SECRET.takeIf(String::isNotBlank),
                redirectUri = TraktConfig.REDIRECT_URI,
            ),
        )

        val response = runCatching {
            httpRequestRaw(
                method = "POST",
                url = "$BASE_URL/oauth/token",
                body = body,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                ),
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Trakt token refresh transport failure: ${error.message}" }
        }.getOrNull() ?: return@withLock false

        if (ProfileRepository.activeProfileId != profileId || authState.refreshToken != refreshToken) {
            return@withLock false
        }

        when (traktTokenRefreshResponseAction(response.status)) {
            TraktTokenRefreshResponseAction.INVALIDATE -> {
                log.w { "Trakt rejected the refresh token with HTTP 400; clearing local credentials" }
                invalidateCredentials(profileId)
                return@withLock false
            }

            TraktTokenRefreshResponseAction.TRANSIENT_FAILURE -> {
                log.w { "Trakt token refresh failed with HTTP ${response.status}" }
                return@withLock false
            }

            TraktTokenRefreshResponseAction.ACCEPT -> Unit
        }

        val parsed = runCatching {
            json.decodeFromString<TraktTokenResponse>(response.body)
        }.getOrNull() ?: return@withLock false

        authState = authState.copy(
            accessToken = parsed.accessToken,
            refreshToken = parsed.refreshToken,
            tokenType = parsed.tokenType,
            createdAt = parsed.createdAt,
            expiresIn = parsed.expiresIn,
        )
        persist(profileId)
        publish()
        true
    }

    private fun invalidateCredentials(profileId: Int) {
        authState = TraktAuthState()
        persist(profileId)
        publish(
            isLoading = false,
            statusMessage = null,
            errorMessage = localizedString(Res.string.trakt_authorization_expired_reconnect),
        )
    }

    private fun loadFromDisk(profileId: Int) {
        stopPendingAuthorization()
        currentProfileId = profileId
        profileGeneration += 1L
        hasLoaded = true
        val payload = TraktAuthStorage.loadPayload(profileId).orEmpty().trim()
        authState = if (payload.isBlank()) {
            TraktAuthState()
        } else {
            runCatching { json.decodeFromString<TraktAuthState>(payload) }
                .getOrElse {
                    log.w { "Failed to parse Trakt auth payload: ${it.message}" }
                    TraktAuthState()
                }
        }
        if (authState.hasPendingDeviceCode && !hasActiveDeviceCode()) {
            clearDeviceFlow()
            persist(profileId)
        }
        publish(statusMessage = null, errorMessage = null)
    }

    private fun clearDeviceFlow() {
        authState = authState.copy(
            deviceCode = null,
            deviceUserCode = null,
            deviceVerificationUrl = null,
            deviceCodeExpiresAtMillis = null,
            devicePollIntervalSeconds = null,
        )
    }

    private fun stopPendingAuthorization() {
        pollJob?.cancel()
        pollJob = null
        isPolling = false
        loopbackJob?.cancel()
        loopbackJob = null
        loopbackPending = false
        loopbackAuthorizeUrl = null
    }

    private fun publish(
        isLoading: Boolean = _uiState.value.isLoading,
        statusMessage: String? = _uiState.value.statusMessage,
        errorMessage: String? = _uiState.value.errorMessage,
    ) {
        val tokenExpiresAtMillis = authState.createdAt
            ?.let { createdAtSeconds ->
                authState.expiresIn?.let { expiresInSeconds ->
                    (createdAtSeconds + expiresInSeconds) * 1_000L
                }
            }

        val mode = when {
            authState.isAuthenticated -> TraktConnectionMode.CONNECTED
            authState.hasPendingDeviceCode || loopbackPending -> TraktConnectionMode.AWAITING_APPROVAL
            else -> TraktConnectionMode.DISCONNECTED
        }

        _isAuthenticated.value = authState.isAuthenticated
        _uiState.value = TraktAuthUiState(
            mode = mode,
            credentialsConfigured = hasRequiredCredentials(),
            isLoading = isLoading,
            username = authState.username,
            tokenExpiresAtMillis = tokenExpiresAtMillis,
            deviceUserCode = authState.deviceUserCode,
            deviceVerificationUrl = authState.deviceVerificationUrl,
            deviceCodeExpiresAtMillis = authState.deviceCodeExpiresAtMillis,
            pollIntervalSeconds = authState.devicePollIntervalSeconds ?: TRAKT_DEFAULT_POLL_INTERVAL_SECONDS,
            isPolling = isPolling,
            authFlow = authFlow(),
            statusMessage = statusMessage,
            errorMessage = errorMessage,
        )
    }

    private fun persist(profileId: Int = currentProfileId) {
        TraktAuthStorage.savePayload(profileId, json.encodeToString(authState))
    }

    private fun isTokenExpiredOrExpiring(state: TraktAuthState): Boolean {
        val createdAt = state.createdAt ?: return true
        val expiresIn = state.expiresIn ?: return true
        val expiresAtSeconds = createdAt + expiresIn
        val nowSeconds = TraktPlatformClock.nowEpochMs() / 1_000L
        return nowSeconds >= (expiresAtSeconds - 60)
    }

    private fun localizedString(resource: StringResource): String = runBlocking { getString(resource) }

    private fun localizedString(resource: StringResource, vararg formatArgs: Any): String =
        runBlocking { getString(resource, *formatArgs) }
}

internal enum class TraktTokenRefreshResponseAction {
    ACCEPT,
    INVALIDATE,
    TRANSIENT_FAILURE,
}

internal fun traktTokenRefreshResponseAction(status: Int): TraktTokenRefreshResponseAction = when {
    status == 400 -> TraktTokenRefreshResponseAction.INVALIDATE
    status in 200..299 -> TraktTokenRefreshResponseAction.ACCEPT
    else -> TraktTokenRefreshResponseAction.TRANSIENT_FAILURE
}

@Serializable
private data class TraktAuthorizationCodeRequest(
    @SerialName("code") val code: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String? = null,
    @SerialName("redirect_uri") val redirectUri: String,
    @SerialName("code_verifier") val codeVerifier: String,
    @SerialName("grant_type") val grantType: String = "authorization_code",
)

@Serializable
private data class TraktDeviceCodeRequest(
    @SerialName("client_id") val clientId: String,
)

@Serializable
private data class TraktDeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_url") val verificationUrl: String = TRAKT_ACTIVATION_URL,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("interval") val interval: Int = TRAKT_DEFAULT_POLL_INTERVAL_SECONDS,
)

@Serializable
private data class TraktDeviceTokenRequest(
    @SerialName("code") val code: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String? = null,
)

@Serializable
private data class TraktRefreshTokenRequest(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String? = null,
    @SerialName("redirect_uri") val redirectUri: String,
    @SerialName("grant_type") val grantType: String = "refresh_token",
)

@Serializable
private data class TraktRevokeRequest(
    @SerialName("token") val token: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String? = null,
)

@Serializable
private data class TraktTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
private data class TraktUserSettingsResponse(
    val user: TraktUserDto? = null,
)

@Serializable
private data class TraktUserDto(
    val username: String? = null,
    val ids: TraktUserIdsDto? = null,
)

@Serializable
private data class TraktUserIdsDto(
    val slug: String? = null,
)

private fun traktLoopbackResponseHtml(message: String): String {
    val escaped = message
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    return """<!doctype html><html><head><meta charset="utf-8">""" +
        """<meta name="viewport" content="width=device-width, initial-scale=1"><title>Trakt</title></head>""" +
        """<body style="font-family:sans-serif;background:#111;color:#eee;display:flex;align-items:center;""" +
        """justify-content:center;min-height:90vh;margin:0;padding:16px;text-align:center">""" +
        """<p style="font-size:18px;max-width:28em">$escaped</p></body></html>"""
}
