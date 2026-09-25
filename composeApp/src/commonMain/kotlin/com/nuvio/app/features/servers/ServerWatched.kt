package com.nuvio.app.features.servers

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.watched.WatchedItem
import kotlinx.coroutines.CancellationException
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.servers_watched_failed
import org.jetbrains.compose.resources.getString

internal object ServerWatched {
    fun isServerItem(item: WatchedItem): Boolean = ServerItemRef.isServerId(item.id)

    suspend fun apply(items: Collection<WatchedItem>, played: Boolean) {
        val refs = items.mapNotNull { item ->
            ServerItemRef.parse(item.videoId) ?: ServerItemRef.parse(item.id)?.takeIf { item.episode == null }
        }.distinct()
        val failures = refs.count { ref ->
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
        if (failures > 0) NuvioToastController.show(getString(Res.string.servers_watched_failed))
    }
}
