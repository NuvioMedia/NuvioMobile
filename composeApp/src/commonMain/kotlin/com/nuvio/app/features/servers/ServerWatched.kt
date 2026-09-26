package com.nuvio.app.features.servers

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.servers_watched_failed
import org.jetbrains.compose.resources.getString

internal object ServerWatched {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun isServerItem(item: WatchedItem): Boolean = ServerItemRef.isServerId(item.id)

    suspend fun apply(items: Collection<WatchedItem>, played: Boolean) {
        val failed = write(targets(items), played)
        if (failed.isEmpty()) return
        reportFailure()
        if (played) {
            val series = failed.filter { it.episode != null }.distinctBy { it.id }
            WatchedRepository.unmarkWatchedLocally(
                failed + items.filter { item -> item.episode == null && series.any { it.id == item.id } },
            )
            series.forEach { WatchedRepository.updateFullyWatchedSeries(it.id, it.type, isFullyWatched = false) }
        } else {
            WatchedRepository.markWatchedLocally(failed)
        }
        failed.mapNotNull { ServerItemRef.parse(it.id) }.distinct().forEach { resync(it) }
    }

    fun mirror(items: Collection<WatchedItem>, played: Boolean): Job? {
        val connections = ServerRepository.enabledConnections().filter { it.useCatalogMetadata }
        if (connections.isEmpty() || items.none { !isServerItem(it) }) return null
        return scope.launch {
            var lookupFailures = 0
            val targets = items.filterNot(::isServerItem).flatMap { item ->
                val request = ServerMatcher.request(item.type, item.videoId ?: item.id, item.season, item.episode)
                    ?: return@flatMap emptyList()
                connections.filter { ServerMatcher.supports(it, request.kind) }.flatMap { connection ->
                    try {
                        ServerMatcher.match(connection, request, forceRefresh = false).map { it to item }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        lookupFailures++
                        emptyList()
                    }
                }
            }.distinctBy { it.first }
            if (lookupFailures > 0 || write(targets, played).isNotEmpty()) reportFailure()
        }
    }

    internal fun targets(items: Collection<WatchedItem>): List<Pair<ServerItemRef, WatchedItem>> =
        items.mapNotNull { item -> item.serverRef()?.let { it to item } }.distinctBy { it.first }

    internal suspend fun write(targets: List<Pair<ServerItemRef, WatchedItem>>, played: Boolean): List<WatchedItem> {
        val failed = mutableListOf<WatchedItem>()
        targets.chunked(WRITE_BATCH).forEach { batch ->
            if (failed.isNotEmpty()) {
                failed += batch.map { it.second }
                return@forEach
            }
            failed += coroutineScope {
                batch.map { (ref, item) -> async { item.takeUnless { setPlayed(ref, played) } } }.awaitAll().filterNotNull()
            }
        }
        return failed
    }

    private fun WatchedItem.serverRef(): ServerItemRef? = when {
        episode != null -> ServerItemRef.parse(videoId)
        type == ServerMediaKind.MOVIE.contentType -> ServerItemRef.parse(id)
        else -> null
    }

    private suspend fun setPlayed(ref: ServerItemRef, played: Boolean): Boolean =
        try {
            ServerRepository.call(ref.connectionId) { provider, session ->
                if (!provider.supports(ServerCapability.USER_STATE_WRITE)) throw ServerException(ServerFailure.UNSUPPORTED)
                provider.setPlayed(session, ref.itemId, played)
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            false
        }

    private suspend fun resync(ref: ServerItemRef) {
        val details = try {
            ServerCatalog.details(ref)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return
        }
        ServerUserStateProjection.apply(details)
        WatchedRepository.reconcileSeriesWatchedState(details.meta, CurrentDateProvider.todayIsoDate())
    }

    private suspend fun reportFailure() {
        NuvioToastController.show(getString(Res.string.servers_watched_failed))
    }

    private const val WRITE_BATCH = 6
}
