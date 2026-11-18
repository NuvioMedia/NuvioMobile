package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.poster.CustomPosterScreen
import com.nuvio.app.core.poster.CustomPosterUrlRepository
import com.nuvio.app.core.poster.CustomPosterUrlResolver
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState

@Composable
internal fun DownloadPoster(
    item: DownloadItem,
    modifier: Modifier = Modifier,
) {
    val pattern = rememberLibraryPosterPattern()
    val originalPoster = item.posterArtwork()
    val customPoster = remember(item.parentMetaId, item.parentMetaType, pattern) {
        item.customPosterUrl(pattern)
    }
    NuvioPosterCard(
        title = item.title,
        imageUrl = customPoster ?: originalPoster,
        fallbackImageUrl = originalPoster,
        modifier = modifier.width(NuvioTokens.Space.s48),
        showTitleBelow = false,
    )
}

@Composable
internal fun DownloadThumbnail(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    watchProgress: Float = 0f,
) {
    val tokens = MaterialTheme.nuvio
    val cornerRadius = rememberPosterCardStyleUiState().cornerRadiusDp.dp
    Box(
        modifier = modifier
            .width(NuvioTokens.Space.s96)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(cornerRadius))
            .background(tokens.colors.surfaceCard),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl == null) {
            Icon(
                imageVector = Icons.Rounded.Movie,
                contentDescription = null,
                tint = tokens.colors.textMuted,
                modifier = Modifier.size(NuvioTokens.Icon.md),
            )
        } else {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        }
        if (watchProgress > 0f) {
            NuvioProgressBar(
                progress = watchProgress,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(NuvioTokens.Space.s4),
                height = NuvioTokens.Space.s3,
            )
        }
    }
}

@Composable
private fun rememberLibraryPosterPattern(): String {
    CustomPosterUrlRepository.ensureLoaded()
    val pattern by CustomPosterUrlRepository.pattern.collectAsStateWithLifecycle()
    val screens by CustomPosterUrlRepository.enabledScreens.collectAsStateWithLifecycle()
    return if (CustomPosterScreen.LIBRARY in screens) pattern else ""
}

private fun DownloadItem.customPosterUrl(pattern: String): String? {
    if (pattern.isBlank()) return null
    return CustomPosterUrlResolver.resolve(
        pattern = pattern,
        ids = CustomPosterUrlResolver.extractIds(parentMetaId),
        type = if (parentMetaType.equals("movie", ignoreCase = true)) "movie" else "series",
    )
}
