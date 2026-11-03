package com.nuvio.app.features.cloudstream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

object CloudStreamRepositoryParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseRepository(rawUrl: String, payload: String): CloudStreamRepositoryManifest {
        val manifestUrl = normalizeCloudStreamRepositoryUrl(rawUrl)
        val dto = json.decodeFromString<CloudStreamRepositoryManifestDto>(payload)
        val manifestVersion = dto.manifestVersion ?: 1
        require(manifestVersion > 0) { "CloudStream manifestVersion must be positive" }
        val pluginLists = dto.pluginLists
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { resolveCloudStreamUrl(manifestUrl, it) }
            .distinct()
            .toList()
        require(pluginLists.isNotEmpty()) { "CloudStream repository does not contain pluginLists" }

        return CloudStreamRepositoryManifest(
            sourceUrl = manifestUrl,
            name = dto.name?.trim()?.takeIf(String::isNotBlank)
                ?: manifestUrl.substringBefore('?').substringAfterLast('/').removeSuffix(".json"),
            description = dto.description?.trim()?.takeIf(String::isNotBlank),
            iconUrl = dto.iconUrl?.trim()?.takeIf(String::isNotBlank)?.let { resolveCloudStreamUrl(manifestUrl, it) },
            manifestVersion = manifestVersion,
            pluginListUrls = pluginLists,
        )
    }

    fun parsePluginList(
        repositoryManifestUrl: String,
        pluginListUrl: String,
        payload: String,
    ): List<CloudStreamPluginMetadata> {
        val normalizedRepositoryUrl = normalizeCloudStreamRepositoryUrl(repositoryManifestUrl)
        val normalizedPluginListUrl = resolveCloudStreamUrl(normalizedRepositoryUrl, pluginListUrl)
        val elements = json.parseToJsonElement(payload).jsonArray
        val decoded = elements.map { element ->
            runCatching { json.decodeFromJsonElement<CloudStreamPluginMetadataDto>(element) }.getOrNull()
        }
        require(decoded.all { it != null }) {
            "CloudStream plugin list contains invalid entries; refusing a partial refresh"
        }

        // A malformed declared checksum makes that package entry unusable, but it
        // must not discard otherwise valid entries from the same provider list.
        // Structural/identity errors still reject the list to avoid destructive
        // partial refreshes.
        val validHashEntries = decoded.filterNotNull().filter { dto ->
            val declaredHash = dto.fileHash?.trim()?.takeIf(String::isNotBlank)
            declaredHash == null || CloudStreamFileHash.parse(declaredHash) != null
        }
        val plugins = validHashEntries.mapNotNull {
            it.toDomainOrNull(normalizedRepositoryUrl, normalizedPluginListUrl)
        }
        require(plugins.size == validHashEntries.size) {
            "CloudStream plugin list contains invalid entries; refusing a partial refresh"
        }
        return plugins
    }

    fun mergePluginLists(lists: List<List<CloudStreamPluginMetadata>>): List<CloudStreamPluginMetadata> {
        val selected = linkedMapOf<String, CloudStreamPluginMetadata>()
        lists.flatten().forEach { plugin ->
            val existing = selected[plugin.id.value]
            if (existing == null || plugin.version > existing.version) {
                selected[plugin.id.value] = plugin
            }
        }
        return selected.values.sortedWith(compareBy({ it.name.lowercase() }, { it.internalName.lowercase() }))
    }

    fun parseCs3Manifest(payload: String): CloudStreamCs3Manifest =
        json.decodeFromString(payload)

    private fun CloudStreamPluginMetadataDto.toDomainOrNull(
        repositoryManifestUrl: String,
        pluginListUrl: String,
    ): CloudStreamPluginMetadata? {
        val internalName = internalName?.trim()?.takeIf(String::isNotBlank) ?: return null
        val displayName = name?.trim()?.takeIf(String::isNotBlank) ?: internalName
        val packageUrl = url?.trim()?.takeIf(String::isNotBlank)?.let { resolveCloudStreamUrl(pluginListUrl, it) }
            ?: return null
        val resolvedVersion = version?.takeIf { it >= 0 } ?: 0
        val normalizedTvTypes = tvTypes.orEmpty()
            .map(String::trim)
            .filter(String::isNotBlank)
        // A missing hash is allowed by older repositories, but a declared hash must
        // be understood and validated. Treating malformed metadata as "no hash"
        // would silently disable package integrity verification.
        val normalizedHash = fileHash?.trim()?.takeIf(String::isNotBlank)
        val parsedHash = normalizedHash?.let { CloudStreamFileHash.parse(it) ?: return null }
        return CloudStreamPluginMetadata(
            id = CloudStreamPluginId(repositoryManifestUrl, internalName),
            repositoryManifestUrl = repositoryManifestUrl,
            packageUrl = packageUrl,
            status = CloudStreamPluginStatus.fromWireValue(status),
            version = resolvedVersion,
            name = displayName,
            internalName = internalName,
            authors = authors.orEmpty().map(String::trim).filter(String::isNotBlank).distinct(),
            description = description?.trim()?.takeIf(String::isNotBlank),
            fileSize = fileSize?.takeIf { it >= 0 },
            projectUrl = repositoryUrl?.trim()?.takeIf(String::isNotBlank)?.let { resolveCloudStreamUrl(pluginListUrl, it) },
            language = language?.trim()?.takeIf(String::isNotBlank),
            tvTypes = normalizedTvTypes.map(CloudStreamTvType::fromWireValue).distinct(),
            rawTvTypes = normalizedTvTypes,
            iconUrl = iconUrl?.trim()?.takeIf(String::isNotBlank)?.let { resolveCloudStreamUrl(pluginListUrl, it) },
            apiVersion = apiVersion?.takeIf { it > 0 } ?: 1,
            fileHash = parsedHash,
        )
    }
}

internal fun retainCloudStreamRepositoryIdentity(
    requestedManifestUrl: String,
    resolvedManifest: CloudStreamRepositoryManifest,
): CloudStreamRepositoryManifest = resolvedManifest.copy(
    sourceUrl = normalizeCloudStreamRepositoryUrl(requestedManifestUrl),
)

fun normalizeCloudStreamRepositoryUrl(rawUrl: String): String {
    var value = rawUrl.trim()
    require(value.isNotBlank()) { "CloudStream repository URL is required" }
    value = value
        .removePrefix("cloudstreamrepo://")
        .removePrefix("https://cs.repo/?")
        .removePrefix("http://cs.repo/?")
        .trim()
    if (!value.startsWith("http://") && !value.startsWith("https://")) {
        value = "https://$value"
    }
    require(value.startsWith("https://") || value.startsWith("http://")) {
        "CloudStream repository URL must use HTTP or HTTPS"
    }
    value = value.substringBefore('#')

    val githubPrefix = "https://github.com/"
    if (value.startsWith(githubPrefix, ignoreCase = true)) {
        val rest = value.substring(githubPrefix.length).trim('/')
        val parts = rest.split('/').filter(String::isNotBlank)
        require(parts.size >= 2) { "Invalid GitHub repository URL" }
        val owner = parts[0]
        val repository = parts[1].removeSuffix(".git")
        value = when {
            parts.size >= 5 && parts[2] == "blob" ->
                "https://raw.githubusercontent.com/$owner/$repository/${parts[3]}/${parts.drop(4).joinToString("/")}"
            parts.size >= 5 && parts[2] == "raw" ->
                "https://raw.githubusercontent.com/$owner/$repository/${parts[3]}/${parts.drop(4).joinToString("/")}"
            parts.size >= 4 && parts[2] == "tree" ->
                "https://raw.githubusercontent.com/$owner/$repository/${parts[3]}/" +
                    parts.drop(4).ifEmpty { listOf("repo.json") }.joinToString("/")
            parts.size == 2 -> "https://raw.githubusercontent.com/$owner/$repository/master/repo.json"
            else -> value
        }
    }

    val rawGithubPrefix = "https://raw.githubusercontent.com/"
    if (value.startsWith(rawGithubPrefix, ignoreCase = true)) {
        val rest = value.substring(rawGithubPrefix.length).trim('/')
        val parts = rest.split('/').filter(String::isNotBlank)
        if (parts.size >= 6 && parts[2] == "refs" && parts[3] == "heads") {
            value = buildString {
                append(rawGithubPrefix)
                append(parts[0])
                append('/')
                append(parts[1])
                append('/')
                append(parts[4])
                append('/')
                append(parts.drop(5).joinToString("/"))
            }
        }
    }

    val withoutQuery = value.substringBefore('?')
    val lastSegment = withoutQuery.substringAfterLast('/')
    if (!lastSegment.contains('.')) {
        value = value.trimEnd('/') + "/repo.json"
    }
    return normalizeHttpUrlPath(value)
}

fun resolveCloudStreamUrl(baseUrl: String, candidate: String): String {
    val value = candidate.trim()
    require(value.isNotBlank()) { "CloudStream URL is blank" }
    if (value.startsWith("https://") || value.startsWith("http://")) {
        return normalizeHttpUrlPath(value)
    }
    if (value.startsWith("//")) {
        val scheme = baseUrl.substringBefore("://", "https")
        return normalizeHttpUrlPath("$scheme:$value")
    }

    val base = baseUrl.substringBefore('#')
    val schemeSeparator = base.indexOf("://")
    require(schemeSeparator > 0) { "Base URL must be absolute" }
    val schemeAndHostEnd = base.indexOf('/', schemeSeparator + 3).let { if (it < 0) base.length else it }
    val origin = base.substring(0, schemeAndHostEnd)
    val pathBase = base.substring(0, base.substringBefore('?').lastIndexOf('/').coerceAtLeast(schemeAndHostEnd))
    return normalizeHttpUrlPath(
        if (value.startsWith('/')) "$origin$value" else "$pathBase/$value",
    )
}

private fun normalizeHttpUrlPath(url: String): String {
    val fragmentless = url.substringBefore('#')
    val query = fragmentless.substringAfter('?', "").takeIf { '?' in fragmentless }
    val withoutQuery = fragmentless.substringBefore('?')
    val schemeSeparator = withoutQuery.indexOf("://")
    require(schemeSeparator > 0) { "URL must be absolute" }
    val pathStart = withoutQuery.indexOf('/', schemeSeparator + 3)
    if (pathStart < 0) return withoutQuery + query?.let { "?$it" }.orEmpty()
    val origin = withoutQuery.substring(0, pathStart)
    val normalizedSegments = mutableListOf<String>()
    withoutQuery.substring(pathStart + 1).split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> if (normalizedSegments.isNotEmpty()) normalizedSegments.removeAt(normalizedSegments.lastIndex)
            else -> normalizedSegments += segment
        }
    }
    return "$origin/${normalizedSegments.joinToString("/")}" + query?.let { "?$it" }.orEmpty()
}
