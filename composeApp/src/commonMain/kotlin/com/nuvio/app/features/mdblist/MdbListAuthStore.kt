package com.nuvio.app.features.mdblist

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface MdbListAuthPersistence {
    fun read(profileId: Int): String?
    fun write(profileId: Int, value: String?)
    fun clear()
}

class MdbListAuthStore(
    private val persistence: MdbListAuthPersistence,
    initialProfileId: Int = 1
) {
    private val lock = SynchronizedObject()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var currentScope = MdbListAuthScope(initialProfileId, 0)
    private var stored = load(initialProfileId)
    private val mutableState = MutableStateFlow(stateFor())

    val state: StateFlow<MdbListAuthState> = mutableState.asStateFlow()

    fun scope(): MdbListAuthScope = synchronized(lock) { currentScope }

    fun isCurrent(scope: MdbListAuthScope): Boolean = synchronized(lock) { currentScope == scope }

    fun checkScope(scope: MdbListAuthScope) {
        if (!isCurrent(scope)) throw CancellationException("MDBList account changed")
    }

    fun authorization(): MdbListAuthorization? = synchronized(lock) {
        stored.tokens?.let { MdbListAuthorization(currentScope, it) }
    }

    fun deviceCode(scope: MdbListAuthScope): String? = synchronized(lock) {
        stored.deviceCode.takeIf { currentScope == scope }
    }

    fun selectProfile(profileId: Int) = synchronized(lock) {
        if (currentScope.profileId == profileId) return@synchronized
        val value = load(profileId)
        currentScope = MdbListAuthScope(profileId, currentScope.generation + 1)
        stored = value
        publish()
    }

    fun saveSession(session: MdbListDeviceSession, deviceCode: String, scope: MdbListAuthScope): Boolean =
        mutate(scope, advanceGeneration = true) {
            it.copy(session = session, deviceCode = deviceCode)
        }

    fun updateSession(session: MdbListDeviceSession, scope: MdbListAuthScope): Boolean =
        mutate(scope) { it.copy(session = session) }

    fun cancelSession(error: MdbListAuthError? = null, scope: MdbListAuthScope = scope()): Boolean =
        mutate(scope, advanceGeneration = true, error = error) {
            it.copy(session = null, deviceCode = null)
        }

    fun authorize(tokens: MdbListTokens, scope: MdbListAuthScope): Boolean =
        mutate(scope, advanceGeneration = true) { MdbListStoredAuth(tokens = tokens) }

    fun refreshTokens(tokens: MdbListTokens, expected: MdbListAuthorization): Boolean = synchronized(lock) {
        if (stored.tokens?.accessToken != expected.tokens.accessToken) return@synchronized false
        mutate(expected.scope) { it.copy(tokens = tokens) }
    }

    fun saveUser(user: MdbListUser, scope: MdbListAuthScope): Boolean =
        mutate(scope) { it.copy(user = user) }

    fun clearAuth(
        scope: MdbListAuthScope = scope(),
        error: MdbListAuthError? = null,
        expectedAccessToken: String? = null
    ): Boolean = synchronized(lock) {
        if (expectedAccessToken != null && stored.tokens?.accessToken != expectedAccessToken) {
            return@synchronized false
        }
        mutate(scope, advanceGeneration = true, error = error) { MdbListStoredAuth() }
    }

    fun removeProfile(profileId: Int) = synchronized(lock) {
        persistence.write(profileId, null)
        if (currentScope.profileId == profileId) {
            currentScope = currentScope.copy(generation = currentScope.generation + 1)
            stored = MdbListStoredAuth()
            publish()
        }
    }

    fun clearAllProfiles() = synchronized(lock) {
        persistence.clear()
        currentScope = currentScope.copy(generation = currentScope.generation + 1)
        stored = MdbListStoredAuth()
        publish()
    }

    private fun mutate(
        scope: MdbListAuthScope,
        advanceGeneration: Boolean = false,
        error: MdbListAuthError? = null,
        transform: (MdbListStoredAuth) -> MdbListStoredAuth
    ): Boolean = synchronized(lock) {
        if (currentScope != scope) return@synchronized false
        val value = transform(stored)
        persistence.write(scope.profileId, json.encodeToString(value))
        stored = value
        if (advanceGeneration) currentScope = currentScope.copy(generation = currentScope.generation + 1)
        publish(error)
        true
    }

    private fun load(profileId: Int): MdbListStoredAuth = persistence.read(profileId)?.let { value ->
        runCatching { json.decodeFromString<MdbListStoredAuth>(value) }.getOrNull()
    }?.takeIf { value ->
        value.tokens == null || (value.tokens.accessToken.isNotBlank() && value.tokens.refreshToken.isNotBlank())
    } ?: MdbListStoredAuth()

    private fun publish(error: MdbListAuthError? = null) {
        mutableState.value = stateFor(error)
    }

    private fun stateFor(error: MdbListAuthError? = null) = MdbListAuthState(
        scope = currentScope,
        isAuthenticated = stored.tokens != null,
        user = stored.user,
        session = stored.session,
        error = error
    )
}
