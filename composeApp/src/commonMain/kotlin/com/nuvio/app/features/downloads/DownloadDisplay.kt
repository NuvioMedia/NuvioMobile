package com.nuvio.app.features.downloads

import androidx.compose.runtime.Composable
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.home_continue_watching_hours_minutes_left
import nuvio.composeapp.generated.resources.home_continue_watching_minutes_left
import org.jetbrains.compose.resources.stringResource

internal const val DownloadTextSeparator = " • "

internal val DownloadItem.sizeOnDiskBytes: Long
    get() = if (status == DownloadStatus.Completed) totalBytes ?: downloadedBytes else downloadedBytes

internal val DownloadItem.hasKnownSize: Boolean
    get() = (totalBytes ?: 0L) > 0L

internal fun DownloadItem.posterArtwork(): String? =
    listOf(poster, background, episodeThumbnail).firstOrNull { !it.isNullOrBlank() }

internal fun DownloadItem.landscapeArtwork(): String? =
    listOf(episodeThumbnail, background, poster).firstOrNull { !it.isNullOrBlank() }

internal fun DownloadItem.episodeCode(): String? =
    if (isEpisode) localizedSeasonEpisodeCode(seasonNumber, episodeNumber) else null

internal fun DownloadItem.episodeLabel(): String? {
    val code = episodeCode() ?: return null
    return listOfNotNull(code, episodeTitle?.trim()?.takeIf { it.isNotBlank() })
        .joinToString(DownloadTextSeparator)
}

internal fun DownloadItem.episodeDisplayTitle(): String =
    listOfNotNull(
        localizedSeasonEpisodeCode(seasonNumber = null, episodeNumber = episodeNumber),
        episodeTitle?.trim()?.takeIf { it.isNotBlank() },
    ).joinToString(DownloadTextSeparator).ifBlank { title }

@Composable
internal fun formatTimeLeft(remainingMs: Long): String {
    val totalMinutes = (remainingMs / 60_000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60L
    return if (hours > 0L) {
        stringResource(Res.string.home_continue_watching_hours_minutes_left, hours, totalMinutes % 60L)
    } else {
        stringResource(Res.string.home_continue_watching_minutes_left, totalMinutes)
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 ${localizedByteUnit("B")}"
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    val value = bytes.toDouble()
    return when {
        value >= gib -> "${((value / gib) * 10.0).toInt() / 10.0} ${localizedByteUnit("GB")}"
        value >= mib -> "${((value / mib) * 10.0).toInt() / 10.0} ${localizedByteUnit("MB")}"
        value >= kib -> "${((value / kib) * 10.0).toInt() / 10.0} ${localizedByteUnit("KB")}"
        else -> "$bytes ${localizedByteUnit("B")}"
    }
}
