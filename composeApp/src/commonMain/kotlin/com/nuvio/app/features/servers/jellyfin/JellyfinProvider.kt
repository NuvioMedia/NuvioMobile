package com.nuvio.app.features.servers.jellyfin

import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.features.servers.mediabrowser.Endpoint
import com.nuvio.app.features.servers.mediabrowser.MediaBrowserProvider
import com.nuvio.app.features.servers.mediabrowser.pathSegment
import io.ktor.client.HttpClient

internal class JellyfinProvider(
    http: HttpClient = createApiHttpClient(),
) : MediaBrowserProvider(authorizationHeader = "Authorization", apiPath = "", http = http) {
    override val id: String = "jellyfin"
    override val displayName: String = "Jellyfin"
    override val minimumVersion: String = "10.9"

    override fun viewsEndpoint(userId: String) = Endpoint("/UserViews", mapOf("userId" to userId))

    override fun itemsEndpoint(userId: String) = Endpoint("/Items", mapOf("userId" to userId))

    override fun itemEndpoint(userId: String, itemId: String) =
        Endpoint("/Items/${pathSegment(itemId)}", mapOf("userId" to userId))

    override fun resumeEndpoint(userId: String) = Endpoint("/UserItems/Resume", mapOf("userId" to userId))

    override fun playedEndpoint(userId: String, itemId: String) =
        Endpoint("/UserPlayedItems/${pathSegment(itemId)}", mapOf("userId" to userId))
}
