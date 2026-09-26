package com.nuvio.app.features.servers

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.random.Random

data class ServersUiState(
    val connections: List<ServerConnection> = emptyList(),
    val failures: Map<String, ServerFailure> = emptyMap(),
    val revision: Int = 0,
) {
    val enabledConnections: List<ServerConnection>
        get() = connections.filter { it.enabled }
}

object ServerRepository {
    private val log = Logger.withTag("ServerRepository")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(ServersUiState())
    val uiState: StateFlow<ServersUiState> = _uiState.asStateFlow()

    private val lock = SynchronizedObject()

    @Volatile
    private var loadedProfileId: Int? = null

    @Volatile
    private var generation = 0L
    private val tokens = mutableMapOf<String, String>()

    fun ensureLoaded() {
        val profileId = ProfileRepository.activeProfileId
        if (loadedProfileId != profileId) load(profileId)
    }

    fun onProfileChanged() {
        load(ProfileRepository.activeProfileId)
    }

    fun clearLocalState() {
        generation++
        synchronized(lock) { tokens.clear() }
        loadedProfileId = null
        runCatching { ServerStorage.clear() }.onFailure { log.w(it) { "Unable to clear server storage" } }
        _uiState.value = ServersUiState(revision = _uiState.value.revision + 1)
        ServerMatcher.clear()
    }

    fun removeProfile(profileId: Int) {
        readConnections(profileId).forEach { deleteCredential(it.credentialRef) }
        runCatching { ServerStorage.write(connectionsKey(profileId), null) }
        if (loadedProfileId == profileId) load(profileId)
    }

    fun connection(connectionId: String): ServerConnection? {
        ensureLoaded()
        return _uiState.value.connections.firstOrNull { it.id == connectionId }
    }

    fun enabledConnections(): List<ServerConnection> {
        ensureLoaded()
        return _uiState.value.enabledConnections
    }

    fun provider(connection: ServerConnection): ServerProvider? = ServerProviders.forId(connection.providerId)

    fun sourceLabel(connection: ServerConnection): String =
        "${provider(connection)?.displayName ?: connection.providerId} · ${connection.name}"

    fun session(connectionId: String): ServerSession? {
        val connection = connection(connectionId)?.takeIf { it.enabled } ?: return null
        val token = synchronized(lock) {
            tokens.getOrPut(connection.credentialRef) {
                readCredential(connection.credentialRef) ?: return null
            }
        }
        return ServerSession(connection, token)
    }

    suspend fun <T> call(
        connectionId: String,
        block: suspend (ServerProvider, ServerSession) -> T,
    ): T {
        val startedGeneration = generation
        val connection = connection(connectionId) ?: throw ServerException(ServerFailure.NOT_FOUND)
        if (!connection.enabled) throw ServerException(ServerFailure.UNREACHABLE)
        val session = session(connectionId) ?: throw ServerException(ServerFailure.AUTH_REQUIRED)
        val provider = provider(session.connection) ?: throw ServerException(ServerFailure.UNSUPPORTED)
        return try {
            block(provider, session).also {
                if (startedGeneration != generation) throw CancellationException("Server scope changed")
                setFailure(connectionId, null)
            }
        } catch (error: ServerException) {
            if (startedGeneration == generation &&
                (error.failure == ServerFailure.AUTH_REQUIRED || error.failure == ServerFailure.UNREACHABLE)
            ) {
                setFailure(connectionId, error.failure)
            }
            throw error
        }
    }

    suspend fun connect(provider: ServerProvider, address: String, username: String, password: String): ServerConnection {
        ensureLoaded()
        val startedGeneration = generation
        val profileId = loadedProfileId ?: ProfileRepository.activeProfileId
        val signIn = provider.signIn(address, username, password)
        val existing = _uiState.value.connections.firstOrNull {
            it.providerId == provider.id &&
                it.remoteServerId == signIn.serverId &&
                it.remoteUserId == signIn.userId
        }
        val draft = ServerConnection(
            id = existing?.id ?: newId("c"),
            providerId = provider.id,
            name = signIn.serverName,
            address = signIn.address,
            remoteServerId = signIn.serverId,
            remoteUserId = signIn.userId,
            userName = signIn.userName,
            credentialRef = newId("k"),
            libraries = existing?.libraries.orEmpty(),
            enabled = true,
            useCatalogMetadata = existing?.useCatalogMetadata ?: false,
        )
        val libraries = provider.libraries(ServerSession(draft, signIn.token))
        if (startedGeneration != generation || profileId != loadedProfileId) {
            throw CancellationException("Server scope changed")
        }
        val connection = draft.copy(libraries = mergeLibraries(existing?.libraries.orEmpty(), libraries))
        store(connection, signIn.token)
        existing?.let { deleteCredential(it.credentialRef) }
        return connection
    }

    internal fun store(connection: ServerConnection, token: String) {
        ensureLoaded()
        writeCredential(connection.credentialRef, token)
        saveConnections(
            _uiState.value.connections.filterNot { it.id == connection.id } + connection,
            invalidate = true,
        )
        setFailure(connection.id, null)
    }

    suspend fun refreshLibraries(connectionId: String) {
        val libraries = call(connectionId) { provider, session -> provider.libraries(session) }
        updateConnection(connectionId) { it.copy(libraries = mergeLibraries(it.libraries, libraries)) }
    }

    fun setLibrarySelected(connectionId: String, libraryId: String, selected: Boolean) {
        updateConnection(connectionId) { connection ->
            connection.copy(
                libraries = connection.libraries.map {
                    if (it.id == libraryId) it.copy(selected = selected) else it
                },
            )
        }
    }

    fun setCatalogMetadata(connectionId: String, enabled: Boolean) {
        updateConnection(connectionId) { it.copy(useCatalogMetadata = enabled) }
    }

    fun setEnabled(connectionId: String, enabled: Boolean) {
        updateConnection(connectionId) { it.copy(enabled = enabled) }
    }

    fun remove(connectionId: String) {
        val connection = connection(connectionId) ?: return
        val session = session(connectionId)
        saveConnections(_uiState.value.connections.filterNot { it.id == connectionId }, invalidate = true)
        deleteCredential(connection.credentialRef)
        setFailure(connectionId, null)
        if (session != null) {
            scope.launch {
                withContext(NonCancellable) {
                    withTimeoutOrNull(5_000L) {
                        runCatching { provider(connection)?.signOut(session) }
                    }
                }
            }
        }
    }

    private fun load(profileId: Int) {
        generation++
        synchronized(lock) { tokens.clear() }
        loadedProfileId = profileId
        ServerMatcher.clear()
        _uiState.value = ServersUiState(
            connections = readConnections(profileId),
            revision = _uiState.value.revision + 1,
        )
    }

    private fun updateConnection(connectionId: String, transform: (ServerConnection) -> ServerConnection) {
        ensureLoaded()
        val connections = _uiState.value.connections.map { if (it.id == connectionId) transform(it) else it }
        saveConnections(connections, invalidate = true)
    }

    private fun saveConnections(connections: List<ServerConnection>, invalidate: Boolean) {
        val profileId = loadedProfileId ?: return
        runCatching {
            ServerStorage.write(
                connectionsKey(profileId),
                json.encodeToString(ListSerializer(ServerConnection.serializer()), connections),
            )
        }.onFailure { log.w(it) { "Unable to save server connections" } }
        if (invalidate) {
            generation++
            }
        _uiState.update { it.copy(connections = connections, revision = it.revision + 1) }
    }

    private fun setFailure(connectionId: String, failure: ServerFailure?) {
        _uiState.update { state ->
            if (state.failures[connectionId] == failure) return@update state
            val failures = if (failure == null) state.failures - connectionId else state.failures + (connectionId to failure)
            state.copy(failures = failures)
        }
    }

    private fun readConnections(profileId: Int): List<ServerConnection> =
        runCatching {
            ServerStorage.read(connectionsKey(profileId))
                ?.let { json.decodeFromString(ListSerializer(ServerConnection.serializer()), it) }
        }.getOrNull().orEmpty()

    private fun readCredential(ref: String): String? =
        runCatching { ServerStorage.read(credentialKey(ref)) }.getOrNull()

    private fun writeCredential(ref: String, token: String) {
        ServerStorage.write(credentialKey(ref), token)
        synchronized(lock) { tokens[ref] = token }
    }

    private fun deleteCredential(ref: String) {
        synchronized(lock) { tokens.remove(ref) }
        runCatching { ServerStorage.write(credentialKey(ref), null) }
    }

    private fun mergeLibraries(previous: List<ServerLibrary>, current: List<ServerLibrary>): List<ServerLibrary> {
        val selection = previous.associate { it.id to it.selected }
        return current.map { library -> library.copy(selected = selection[library.id] ?: true) }
    }

    private fun connectionsKey(profileId: Int) = "profile.$profileId.connections"

    private fun credentialKey(ref: String) = "credential.$ref"

    private fun newId(prefix: String): String =
        prefix + (0 until 15).joinToString("") { Random.nextInt(16).toString(16) }
}
