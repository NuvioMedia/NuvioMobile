package com.nuvio.app.features.servers.plex

import com.nuvio.app.features.servers.ServerException
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.mediabrowser.pathSegment
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Clock

/** plex.tv: the code sign-in, the account's servers and its Plex Home users. */
internal class PlexAccountClient(
    private val client: PlexClient,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val accountBase: String = ACCOUNT_BASE,
    private val clientsBase: String = CLIENTS_BASE,
) {
    private val probes = Semaphore(MAX_PROBES)

    suspend fun startPin(strong: Boolean): PlexPin {
        val startedAt = now()
        val reply = send(
            HttpMethod.Post,
            accountBase,
            PINS_PATH,
            PinWire.serializer(),
            query = mapOf("strong" to strong.toString()),
        )
        if (reply.id <= 0 || reply.code.isBlank()) throw ServerException(ServerFailure.FAILED)
        return PlexPin(reply.id, reply.code, startedAt + reply.expiresIn.coerceAtLeast(MIN_PIN_SECONDS) * 1_000L, strong)
    }

    /** Plex's own sign-in page with [pin] filled in; approving there links the pin to the account. */
    fun authPage(pin: PlexPin): String =
        "$AUTH_PAGE#?" + listOf(
            "clientID" to client.clientId,
            "code" to pin.code,
            "context[device][product]" to PlexClient.PRODUCT,
        ).joinToString("&") { (name, value) -> "${name.encodeURLParameter()}=${value.encodeURLParameter()}" }

    /** plex.tv can answer a fresh code with 404 for a moment; that counts as waiting until the code runs out. */
    suspend fun checkPin(pin: PlexPin): PlexPinStatus {
        val reply = try {
            send(HttpMethod.Get, accountBase, "$PINS_PATH/${pin.id}", PinWire.serializer())
        } catch (error: ServerException) {
            if (error.failure != ServerFailure.NOT_FOUND) throw error
            return if (now() >= pin.expiresAtMs) PlexPinStatus.Expired else PlexPinStatus.Waiting
        }
        val token = reply.authToken?.takeIf { it.isNotBlank() }
        return when {
            token != null -> PlexPinStatus.Linked(token)
            now() >= pin.expiresAtMs -> PlexPinStatus.Expired
            else -> PlexPinStatus.Waiting
        }
    }

    suspend fun account(token: String): PlexAccount {
        val reply = send(HttpMethod.Get, accountBase, USER_PATH, AccountWire.serializer(), token)
        if (reply.id <= 0) throw ServerException(ServerFailure.FAILED)
        return PlexAccount(token, reply.id.toString(), reply.uuid, reply.title.ifBlank { reply.username })
    }

    /** The Plex Media Servers [token] can use, each with the token it takes. */
    suspend fun servers(token: String): List<PlexServer> =
        send(
            HttpMethod.Get,
            clientsBase,
            RESOURCES_PATH,
            ListSerializer(ResourceWire.serializer()),
            token,
            mapOf("includeHttps" to "1", "includeRelay" to "1", "includeIPv6" to "1"),
        )
            .filter { resource ->
                "server" in resource.provides.split(',').map(String::trim) && resource.clientIdentifier.isNotBlank()
            }
            .mapNotNull { resource ->
                val serverToken = resource.accessToken?.takeIf { it.isNotBlank() } ?: token.takeIf { resource.owned }
                    ?: return@mapNotNull null
                PlexServer(
                    machineId = resource.clientIdentifier,
                    name = resource.name.ifBlank { resource.clientIdentifier.take(8) },
                    owned = resource.owned,
                    accessToken = serverToken,
                    addresses = resource.connections.flatMap(::addresses).distinctBy { it.uri },
                )
            }

    /**
     * A connection as plex.tv lists it, plus the plain IP address for a home one: many routers refuse to resolve the
     * *.plex.direct names of home addresses (DNS rebinding protection).
     */
    private fun addresses(connection: ConnectionWire): List<PlexAddress> {
        val listed = connection.uri.trimEnd('/').takeIf(::isWebAddress)
            ?.let { PlexAddress(it, local = connection.local, relay = connection.relay) }
        val plain = if (connection.local && !connection.relay && connection.address.isNotBlank() && connection.port > 0) {
            val host = if (connection.ipv6 || ':' in connection.address) "[${connection.address}]" else connection.address
            "http://$host:${connection.port}".takeIf(::isWebAddress)?.let { PlexAddress(it, local = true, relay = false) }
        } else {
            null
        }
        return listOfNotNull(listed, plain)
    }

    /** The account's Plex Home users; just the account itself when it has no Plex Home. */
    suspend fun homeUsers(account: PlexAccount): List<PlexHomeUser> {
        val users = sendOrNull(
            HttpMethod.Get,
            clientsBase,
            HOME_USERS_PATH,
            HomeUsersWire.serializer(),
            account.token,
            accepted = NO_HOME,
        )?.users.orEmpty()
        val home = users.filter { it.uuid.isNotBlank() }.map { user ->
            PlexHomeUser(
                id = user.id.toString(),
                uuid = user.uuid,
                name = user.title.ifBlank { user.username.orEmpty() },
                hasPin = user.protected,
                admin = user.admin,
                restricted = user.restricted,
            )
        }
        return home.ifEmpty {
            listOf(PlexHomeUser(account.id, account.uuid, account.name, hasPin = false, admin = true, restricted = false))
        }
    }

    /** [user]'s own account, or null when the PIN is wrong. */
    suspend fun switchUser(account: PlexAccount, user: PlexHomeUser, pin: String?): PlexAccount? {
        if (user.uuid == account.uuid && !user.hasPin) return account
        val reply = sendOrNull(
            HttpMethod.Post,
            clientsBase,
            "$HOME_USERS_PATH/${pathSegment(user.uuid)}/switch",
            AccountWire.serializer(),
            account.token,
            mapOf("pin" to pin?.trim()?.takeIf { it.isNotEmpty() }),
            accepted = WRONG_PIN,
        ) ?: return null
        val token = reply.authToken?.takeIf { it.isNotBlank() } ?: throw ServerException(ServerFailure.FAILED)
        return PlexAccount(token, user.id, user.uuid, user.name)
    }

    /**
     * Tries every address of [server] at once and keeps the best one that answers as that server: the internet address
     * first, since a phone takes it out of the house, then the home network, then Plex Relay. Unencrypted addresses are
     * only used on the home network.
     */
    suspend fun reach(server: PlexServer, machineId: suspend (String) -> String?): PlexServer {
        val candidates = server.addresses.filter { it.uri.startsWith("https://", ignoreCase = true) || it.local }
        val answered = coroutineScope {
            candidates.map { address ->
                async {
                    val answer = try {
                        probes.withPermit { withTimeoutOrNull(REACH_TIMEOUT_MS) { machineId(address.uri) } }
                    } catch (_: ServerException) {
                        null
                    }
                    address.takeIf { answer == server.machineId }
                }
            }.awaitAll().filterNotNull()
        }
        val best = answered.minWithOrNull(compareBy({ rank(it) }, { !it.uri.startsWith("https://", ignoreCase = true) }))
            ?: return server.reached(PlexReach.OFFLINE, null)
        val reach = when {
            best.relay -> PlexReach.RELAY
            best.local -> PlexReach.LOCAL
            else -> PlexReach.REMOTE
        }
        return server.reached(reach, best.uri)
    }

    private fun rank(address: PlexAddress): Int = when {
        address.relay -> 2
        address.local -> 1
        else -> 0
    }

    private suspend fun <T> send(
        method: HttpMethod,
        base: String,
        path: String,
        reply: DeserializationStrategy<T>,
        token: String? = null,
        query: Map<String, String?> = emptyMap(),
    ): T = sendOrNull(method, base, path, reply, token, query, emptySet()) ?: throw ServerException(ServerFailure.FAILED)

    /** Null when plex.tv answered with one of the [accepted] error statuses. */
    private suspend fun <T> sendOrNull(
        method: HttpMethod,
        base: String,
        path: String,
        reply: DeserializationStrategy<T>,
        token: String?,
        query: Map<String, String?> = emptyMap(),
        accepted: Set<Int>,
    ): T? {
        val response = client.execute(method, base, path, token, query, accepted = accepted)
        if (response.status in accepted) return null
        return try {
            client.json.decodeFromString(reply, response.body)
        } catch (_: SerializationException) {
            throw ServerException(ServerFailure.FAILED)
        } catch (_: IllegalArgumentException) {
            throw ServerException(ServerFailure.FAILED)
        }
    }

    companion object {
        const val ACCOUNT_BASE = "https://plex.tv"
        const val CLIENTS_BASE = "https://clients.plex.tv"
        const val LINK_PAGE = "plex.tv/link"
        private const val AUTH_PAGE = "https://app.plex.tv/auth"
        private const val PINS_PATH = "/api/v2/pins"
        private const val USER_PATH = "/api/v2/user"
        private const val RESOURCES_PATH = "/api/v2/resources"
        private const val HOME_USERS_PATH = "/api/v2/home/users"
        private const val REACH_TIMEOUT_MS = 8_000L
        private const val MAX_PROBES = 5
        private const val MIN_PIN_SECONDS = 60L
        private val NO_HOME = setOf(400, 404)
        private val WRONG_PIN = setOf(403, 422)
    }
}

internal fun isWebAddress(value: String): Boolean {
    if (!value.startsWith("http://", ignoreCase = true) && !value.startsWith("https://", ignoreCase = true)) return false
    val url = runCatching { Url(value) }.getOrNull() ?: return false
    return (url.protocol == URLProtocol.HTTP || url.protocol == URLProtocol.HTTPS) && url.host.isNotBlank()
}
