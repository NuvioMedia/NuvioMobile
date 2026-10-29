package com.nuvio.app.features.debrid

import com.nuvio.app.features.streams.StreamClientResolve

internal class TorboxFileSelector {
    fun selectFile(
        files: List<TorboxTorrentFileDto>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?,
    ): TorboxTorrentFileDto? = selectDebridFile(
        files = files,
        resolve = resolve,
        season = season,
        episode = episode,
        path = { it.displayName() },
        isPlayable = {
            it.mimeType.orEmpty().startsWith("video/", ignoreCase = true) || it.displayName().hasVideoExtension()
        },
        size = { it.size ?: 0L },
    )
}

internal class RealDebridFileSelector {
    fun selectFile(
        files: List<RealDebridTorrentFileDto>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?,
    ): RealDebridTorrentFileDto? = selectDebridFile(
        files = files,
        resolve = resolve,
        season = season,
        episode = episode,
        path = { it.path.orEmpty() },
        isPlayable = { it.displayName().hasVideoExtension() },
        size = { it.bytes ?: 0L },
    )
}

internal class PremiumizeDirectDownloadFileSelector {
    fun selectFile(
        files: List<PremiumizeDirectDownloadFileDto>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?,
    ): PremiumizeDirectDownloadFileDto? = selectDebridFile(
        files = files,
        resolve = resolve,
        season = season,
        episode = episode,
        path = { it.path.orEmpty() },
        isPlayable = { !it.link.isNullOrBlank() && it.displayName().hasVideoExtension() },
        size = { it.size ?: 0L },
    )
}

internal fun PremiumizeDirectDownloadFileDto.displayName(): String =
    path.orEmpty().substringAfterLast('/').substringAfterLast('\\').ifBlank { path.orEmpty() }

private fun <T> selectDebridFile(
    files: List<T>,
    resolve: StreamClientResolve,
    season: Int?,
    episode: Int?,
    path: (T) -> String,
    isPlayable: (T) -> Boolean,
    size: (T) -> Long,
): T? {
    val playable = files.filter(isPlayable)
    if (playable.isEmpty()) return null

    val names = listOfNotNull(resolve.filename, resolve.stream?.raw?.filename)
        .map { it.normalizedPath() }
        .filter { it.isNotBlank() }
        .distinct()
    for (name in names) {
        val matches = playable.matchingFiles(name, path)
        if (matches.isNotEmpty()) return matches.singleOrNull()
    }

    val episodePattern = buildEpisodePattern(season ?: resolve.season, episode ?: resolve.episode)
    if (episodePattern != null) {
        val matches = playable.filter {
            episodePattern.containsMatchIn(path(it).normalizedPath().substringAfterLast('/'))
        }
        if (matches.isNotEmpty()) return matches.singleOrNull()
    }

    if (names.isNotEmpty() || episodePattern != null) return null

    resolve.fileIdx?.let { index ->
        return files.getOrNull(index)?.takeIf(isPlayable)
    }

    return playable.maxByOrNull(size)
}

private fun String.normalizedPath(): String = trim().replace('\\', '/').removePrefix("/")

private fun <T> List<T>.matchingFiles(name: String, path: (T) -> String): List<T> {
    for (ignoreCase in listOf(false, true)) {
        val matches = filter {
            val filePath = path(it).normalizedPath()
            filePath.equals(name, ignoreCase = ignoreCase) ||
                (name.contains('/') && filePath.endsWith("/$name", ignoreCase = ignoreCase))
        }
        if (matches.isNotEmpty()) return matches
    }
    val basename = name.substringAfterLast('/')
    for (ignoreCase in listOf(false, true)) {
        val matches = filter {
            path(it).normalizedPath().substringAfterLast('/').equals(basename, ignoreCase = ignoreCase)
        }
        if (matches.isNotEmpty()) return matches
    }
    return emptyList()
}

private fun buildEpisodePattern(season: Int?, episode: Int?): Regex? {
    if (season == null || episode == null) return null
    return Regex(
        "(?<![a-z0-9])(?:s0*${season}e0*${episode}|0*${season}x0*${episode})(?![0-9])",
        RegexOption.IGNORE_CASE,
    )
}

private fun String.hasVideoExtension(): Boolean = videoExtensions.any { endsWith(it, ignoreCase = true) }

private val videoExtensions = setOf(
    ".mp4",
    ".mkv",
    ".webm",
    ".avi",
    ".mov",
    ".m4v",
    ".ts",
    ".m2ts",
    ".wmv",
    ".flv",
)
