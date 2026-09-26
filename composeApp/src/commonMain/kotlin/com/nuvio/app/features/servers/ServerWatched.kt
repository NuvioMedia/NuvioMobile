package com.nuvio.app.features.servers

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.watched.WatchedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.servers_watched_failed
import org.jetbrains.compose.resources.getString

internal object ServerWatched {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun isServerItem(item: WatchedItem): Boolean = ServerItemRef.isServerId(item.id)

    suspend fun apply(items: Collection<WatchedItem>, played: Boolean) {
        val refs = items.mapNotNull { item ->
            ServerItemRef.parse(item.videoId) ?: ServerItemRef.parse(item.id)?.takeIf { item.episode == null }
        }.distinct()
        reportFailures(setPlayed(refs, played))
    }

    fun mirror(items: Collection<WatchedItem>, played: Boolean): Job? {
        val connections = ServerRepository.enabledConnections().filter { it.useCatalogMetadata }
        if (connections.isEmpty() || items.none { !isServerItem(it) }) return null
        return scope.launch {
            var failures = 0
            val refs = items.filterNot(::isServerItem).flatMap { item ->
                val request = ServerMatcher.request(item.type, item.videoId ?: item.id, item.season, item.episode)
                    ?: return@flatMap emptyList()
                connections.filter { ServerMatcher.supports(it, request.kind) }.flatMap { connection ->
                    try {
                        ServerMatcher.match(connection, request, forceRefresh = false)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        failures++
                        emptyList()
                    }
                }
            }.distinct()
            reportFailures(failures + setPlayed(refs, played))
        }
    }

    private suspend fun setPlayed(refs: List<ServerItemRef>, played: Boolean): Int = refs.count { ref ->
        try {
            ServerRepository.call(ref.connectionId) { provider, session ->
                if (!provider.supports(ServerCapability.USER_STATE_WRITE)) throw ServerException(ServerFailure.UNSUPPORTED)
                provider.setPlayed(session, ref.itemId, played)
            }
            false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            true
        }
    }

    private suspend fun reportFailures(failures: Int) {
        if (failures > 0) NuvioToastController.show(getString(Res.string.servers_watched_failed))
    }
}
