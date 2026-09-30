package com.nuvio.app.features.servers.emby

import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.features.servers.mediabrowser.Endpoint
import com.nuvio.app.features.servers.mediabrowser.MediaBrowserProvider
import com.nuvio.app.features.servers.mediabrowser.PublicInfo
import com.nuvio.app.features.servers.mediabrowser.pathSegment
import io.ktor.client.HttpClient

internal class EmbyProvider(
    http: HttpClient = createApiHttpClient(),
) : MediaBrowserProvider(authorizationHeader = "X-Emby-Authorization", apiPath = "/emby", http = http) {
    override val id: String = "emby"
    override val displayName: String = "Emby"
    override val minimumVersion: String = "4.7"

    override fun isSupported(info: PublicInfo): Boolean =
        info.productName?.contains("Jellyfin", ignoreCase = true) != true && isSupportedVersion(info.version)

    override fun viewsEndpoint(userId: String) = Endpoint("/Users/${pathSegment(userId)}/Views")

    override fun itemsEndpoint(userId: String) = Endpoint("/Users/${pathSegment(userId)}/Items")

    override fun itemEndpoint(userId: String, itemId: String) =
        Endpoint("/Users/${pathSegment(userId)}/Items/${pathSegment(itemId)}")

    override fun resumeEndpoint(userId: String) = Endpoint("/Users/${pathSegment(userId)}/Items/Resume")

    override fun playedEndpoint(userId: String, itemId: String) =
        Endpoint("/Users/${pathSegment(userId)}/PlayedItems/${pathSegment(itemId)}")
}
