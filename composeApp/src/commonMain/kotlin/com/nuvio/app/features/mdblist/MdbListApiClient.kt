package com.nuvio.app.features.mdblist

import kotlinx.serialization.json.Json

class MdbListApiClient(
    private val http: MdbListHttpClient,
    private val auth: MdbListAuthRepository,
    private val store: MdbListAuthStore
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(
        path: String,
        query: Map<String, String> = emptyMap(),
        scope: MdbListAuthScope = store.scope(),
        acceptedStatuses: Set<Int> = emptySet()
    ): MdbListHttpResponse = execute(MdbListHttpMethod.GET, path, query, "", scope, acceptedStatuses)

    suspend fun post(
        path: String,
        body: String,
        scope: MdbListAuthScope = store.scope(),
        query: Map<String, String> = emptyMap()
    ): MdbListHttpResponse = execute(MdbListHttpMethod.POST, path, query, body, scope)

    suspend fun put(
        path: String,
        body: String,
        scope: MdbListAuthScope = store.scope()
    ): MdbListHttpResponse = execute(MdbListHttpMethod.PUT, path, emptyMap(), body, scope)

    suspend fun delete(
        path: String,
        scope: MdbListAuthScope = store.scope()
    ): MdbListHttpResponse = execute(MdbListHttpMethod.DELETE, path, emptyMap(), "", scope)

    suspend fun refreshUser(scope: MdbListAuthScope = store.scope()): MdbListUser {
        val previousLimitKey = limitKey(scope)
        val response = get("/user", scope = scope)
        val user = runCatching {
            json.decodeFromString<MdbListUser>(response.body)
        }.getOrElse { throw MdbListAuthException(MdbListAuthError.INVALID_RESPONSE) }
        if (user.id == null || user.id <= 0L) throw MdbListAuthException(MdbListAuthError.INVALID_RESPONSE)
        if (!store.saveUser(user, scope)) store.checkScope(scope)
        http.associateAccountLimit(previousLimitKey, "user:${user.id}")
        store.checkScope(scope)
        return user
    }

    private suspend fun execute(
        method: MdbListHttpMethod,
        path: String,
        query: Map<String, String>,
        body: String,
        scope: MdbListAuthScope,
        acceptedStatuses: Set<Int> = emptySet()
    ): MdbListHttpResponse {
        var authorization = auth.authorization(scope)
        repeat(2) { attempt ->
            val response = http.execute(
                MdbListHttpRequest(
                    method = method,
                    path = path,
                    query = query,
                    body = body,
                    accessToken = authorization.tokens.accessToken,
                    limitKey = limitKey(scope)
                ),
                checkScope = { store.checkScope(scope) }
            )
            if (response.status == 401) {
                if (attempt == 1) {
                    store.clearAuth(scope, MdbListAuthError.AUTHORIZATION_REVOKED, authorization.tokens.accessToken)
                    throw MdbListAuthException(MdbListAuthError.AUTHORIZATION_REVOKED)
                }
                authorization = auth.authorization(scope, authorization.tokens.accessToken)
            } else {
                if (response.status !in 200..299 && response.status !in acceptedStatuses) {
                    throw MdbListApiException(response.status, response.errorCode())
                }
                return response
            }
        }
        throw MdbListAuthException(MdbListAuthError.AUTHORIZATION_REVOKED)
    }

    private fun limitKey(scope: MdbListAuthScope): String = store.state.value.user?.id?.let { "user:$it" }
        ?: "profile:${scope.profileId}:${scope.generation}"
}
