@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.nuvio.app.features.streams.StreamSubtitle

/** Same marker as NuvioTV: an addon subtitle URL the player must not load like the stream. */
private const val SUBTITLE_URI_MARKER = "nuvio_type=subtitle"

/** Marks an HTTP(S) addon subtitle URL so [SubtitleRoutingDataSourceFactory] fetches it on its own. */
internal fun markedSubtitleUri(url: String): Uri {
    val uri = Uri.parse(url)
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return uri
    val query = uri.encodedQuery
    return uri.buildUpon()
        .encodedQuery(if (query.isNullOrEmpty()) SUBTITLE_URI_MARKER else "$query&$SUBTITLE_URI_MARKER")
        .build()
}

/**
 * Track id for an addon subtitle attached as a sideloaded track. libass (AssHandler) pairs the
 * selected track (`"1:" + id`) with the track it renders (`id`), so a sideloaded track without an
 * id is never rendered. Same scheme as NuvioTV's `buildAddonSubtitleTrackId`.
 */
internal fun addonSubtitleTrackId(url: String): String =
    "nuvio_addon_subtitle:${url.hashCode().toUInt().toString(16)}"

/** The original URL of a marked subtitle URI, keeping its query encoding; null when not marked. */
internal fun Uri.withoutSubtitleMarker(): Uri? {
    val parts = encodedQuery?.split('&') ?: return null
    if (SUBTITLE_URI_MARKER !in parts) return null
    val remaining = parts.filter { it != SUBTITLE_URI_MARKER }
    return buildUpon().encodedQuery(remaining.joinToString("&").ifEmpty { null }).build()
}

/**
 * Loads marked addon subtitles without the stream's request headers (e.g. its `Authorization`)
 * and response header overrides, like NuvioTV's subtitle download path. Headers a stream declared
 * for its own subtitles are still applied by URL. Everything else goes to [media].
 */
internal class SubtitleRoutingDataSourceFactory(
    private val media: DataSource.Factory,
    private val subtitles: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = SubtitleRoutingDataSource(media, subtitles)
}

internal fun subtitleDataSourceFactory(
    context: Context,
    externalSubtitles: List<StreamSubtitle>,
): DataSource.Factory = DefaultDataSource.Factory(
    context,
    SubtitleRequestHeaderDataSourceFactory(
        upstreamFactory = PlayerPlaybackNetworking.createHttpDataSourceFactory(),
        externalSubtitles = externalSubtitles,
    ),
)

private class SubtitleRoutingDataSource(
    private val media: DataSource.Factory,
    private val subtitles: DataSource.Factory,
) : DataSource {
    private val transferListeners = mutableListOf<TransferListener>()
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        active?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val subtitleUri = dataSpec.uri.withoutSubtitleMarker()
        val source = (if (subtitleUri != null) subtitles else media).createDataSource()
        transferListeners.forEach(source::addTransferListener)
        active = source
        return source.open(if (subtitleUri != null) dataSpec.withUri(subtitleUri) else dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active) { "read before open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }
}
