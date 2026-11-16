package com.nuvio.app.features.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.i18n.localizedMediaStatusLabel
import com.nuvio.app.features.details.components.DetailHeroMetaBadge
import com.nuvio.app.features.details.components.ImdbRatingSourceLabel
import com.nuvio.app.features.details.components.ImdbYellow
import com.nuvio.app.features.details.components.RatingsRow
import com.nuvio.app.features.details.components.formatOneDecimal
import com.nuvio.app.features.details.formatRuntimeForDisplay
import com.nuvio.app.features.home.MetaPreview
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.media_movie
import nuvio.composeapp.generated.resources.media_series
import org.jetbrains.compose.resources.stringResource

private const val HERO_RATINGS_LIMIT = 3

@Composable
internal fun HomeHeroMetaRows(
    item: MetaPreview,
    isTablet: Boolean,
    showImdbRating: Boolean,
    showMdbListRatings: Boolean,
) {
    val type = item.type.trim().lowercase()
    val isSeries = type == "series" || type == "tv"
    val typeLabel = when (type) {
        "movie" -> stringResource(Res.string.media_movie)
        "series" -> stringResource(Res.string.media_series)
        else -> item.type.replaceFirstChar(Char::uppercase)
    }
    val genre = item.genres.firstOrNull()?.takeIf { it.isNotBlank() }
    val runtime = formatRuntimeForDisplay(item.runtime)?.takeIf { it.isNotBlank() }
    val release = item.releaseInfo?.takeIf { it.isNotBlank() }?.let(::formatReleaseDateForDisplay)
    val status = item.status?.trim()?.takeIf { it.isNotBlank() }
        ?.let { localizedMediaStatusLabel(it, isSeries).uppercase() }
    val badges = listOfNotNull(item.ageRating?.trim()?.takeIf { it.isNotBlank() }, status)
    val language = item.language?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
    val ratings = if (showMdbListRatings) item.externalRatings else emptyList()
    val imdbRating = item.imdbRating?.trim()
        ?.takeIf { showImdbRating && ratings.isEmpty() }
        ?.toDoubleOrNull()
        ?.takeIf { it > 0.0 }
        ?.let(::formatOneDecimal)
    val primaryImdbRating = imdbRating?.takeIf { !isSeries && badges.isEmpty() }
    val secondaryImdbRating = imdbRating?.takeIf { primaryImdbRating == null }
    val horizontalAlignment = if (isTablet) Alignment.Start else Alignment.CenterHorizontally
    val rowArrangement = Arrangement.spacedBy(8.dp, horizontalAlignment)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = rowArrangement,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeroMetaText(text = typeLabel)
            genre?.let {
                HeroMetaDot()
                HeroMetaText(text = it, modifier = Modifier.weight(1f, fill = false))
            }
            runtime?.let {
                HeroMetaDot()
                HeroMetaText(text = it)
            }
            release?.let {
                HeroMetaDot()
                HeroMetaText(text = it)
            }
            primaryImdbRating?.let {
                HeroMetaDot()
                HeroImdbRating(rating = it)
            }
        }

        if (badges.isNotEmpty() || secondaryImdbRating != null || language != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = rowArrangement,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (badges.isNotEmpty()) {
                    DetailHeroMetaBadge(
                        text = badges.first(),
                        secondaryText = badges.getOrNull(1),
                        contentColor = MaterialTheme.colorScheme.onBackground,
                    )
                }
                secondaryImdbRating?.let {
                    if (badges.isNotEmpty()) HeroMetaDot()
                    HeroImdbRating(rating = it)
                }
                language?.let {
                    if (badges.isNotEmpty() || secondaryImdbRating != null) HeroMetaDot()
                    HeroMetaText(text = it)
                }
            }
        }

        if (ratings.isNotEmpty()) {
            RatingsRow(
                ratings = ratings,
                maxItems = HERO_RATINGS_LIMIT,
            )
        }
    }
}

@Composable
private fun HeroImdbRating(rating: String) {
    val textStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    Row(verticalAlignment = Alignment.CenterVertically) {
        ImdbRatingSourceLabel(storeTextStyle = textStyle)
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = rating,
            style = textStyle,
            color = ImdbYellow,
            maxLines = 1,
        )
    }
}

@Composable
private fun HeroMetaText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onBackground,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun HeroMetaDot() {
    Box(
        modifier = Modifier
            .size(4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)),
    )
}
