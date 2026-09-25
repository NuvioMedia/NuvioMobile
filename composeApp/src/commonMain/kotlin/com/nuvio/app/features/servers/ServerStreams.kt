package com.nuvio.app.features.servers

import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jetbrains.compose.resources.getString

internal class ServerStreamSource(
    val addonId: String,
    val addonName: String,
    private val loader: suspend () -> List<StreamItem>,
) {
    fun loadingGroup(): AddonStreamGroup =
        AddonStreamGroup(addonName = addonName, addonId = addonId, streams = emptyList(), isLoading = true)

    suspend fun load(): AddonStreamGroup = try {
        AddonStreamGroup(addonName = addonName, addonId = addonId, streams = loader(), isLoading = false)
    } catch (error: Throwable) {
        if (error is CancellationException) currentCoroutineContext().ensureActive()
        AddonStreamGroup(
            addonName = addonName,
            addonId = addonId,
            streams = emptyList(),
            isLoading = false,
            error = getString(error.serverFailure().message()),
        )
    }
}

internal object ServerStreams {
    fun isNativeRequest(videoId: String): Boolean = ServerItemRef.isServerId(videoId)

    fun isServerSourceId(addonId: String?): Boolean = addonId?.startsWith(GROUP_PREFIX) == true

    fun sources(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean = false,
    ): List<ServerStreamSource> {
        ServerItemRef.parse(videoId)?.let { ref ->
            val connection = ServerRepository.connection(ref.connectionId) ?: return emptyList()
            return listOf(source(connection) { candidates(ref) })
        }
        val request = ServerMatcher.request(type, videoId, season, episode) ?: return emptyList()
        return ServerRepository.enabledConnections()
            .filter { ServerMatcher.supports(it, request.kind) }
            .map { connection ->
                source(connection) {
                    ServerMatcher.match(connection, request, forceRefresh).flatMap { candidates(it) }
                }
            }
    }

    private fun source(connection: ServerConnection, loader: suspend () -> List<StreamItem>): ServerStreamSource =
        ServerStreamSource(
            addonId = groupId(connection.id),
            addonName = ServerRepository.sourceLabel(connection),
            loader = loader,
        )

    suspend fun candidates(ref: ServerItemRef): List<StreamItem> {
        val connection = ServerRepository.connection(ref.connectionId) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val label = ServerRepository.sourceLabel(connection)
        return ServerRepository.call(ref.connectionId) { provider, session -> provider.candidates(session, ref.itemId) }
            .map { candidate ->
                StreamItem(
                    name = candidate.title,
                    description = candidate.description,
                    sourceName = label,
                    addonName = label,
                    addonId = groupId(connection.id),
                    behaviorHints = StreamBehaviorHints(
                        filename = candidate.filename,
                        videoSize = candidate.sizeBytes,
                    ),
                    serverTarget = candidate.target,
                )
            }
    }

    private fun groupId(connectionId: String) = "$GROUP_PREFIX$connectionId"

    private const val GROUP_PREFIX = "server:"
}
