package com.nuvio.app.features.details.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.nuvioHorizontalScrollBleed
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaExternalRating
import com.nuvio.app.features.details.formatRuntimeForDisplay
import com.nuvio.app.features.details.formatMetaReleaseLineForDetails
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_AUDIENCE
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_IMDB
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_LETTERBOXD
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_METACRITIC
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_MAL
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_TMDB
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_TOMATOES
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_TRAKT
import com.nuvio.app.features.mdblist.RottenTomatoesStatus
import com.nuvio.app.features.mdblist.rottenTomatoesStatus
import nuvio.composeapp.generated.resources.*
import nuvio.composeapp.generated.resources.rating_audience_score
import nuvio.composeapp.generated.resources.rating_imdb
import nuvio.composeapp.generated.resources.rating_letterboxd
import nuvio.composeapp.generated.resources.rating_metacritic
import nuvio.composeapp.generated.resources.rating_rotten_tomatoes
import nuvio.composeapp.generated.resources.rating_tmdb
import nuvio.composeapp.generated.resources.rating_trakt
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.runBlocking
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

@Composable
fun DetailMetaInfo(
    meta: MetaDetails,
    modifier: Modifier = Modifier,
    horizontalScrollPadding: Dp = 0.dp,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val releaseLine = formatMetaReleaseLineForDetails(meta)
        val runtimeText = formatRuntimeForDisplay(meta.runtime)
        val ageBadge = meta.ageRating?.trim()?.takeIf { it.isNotBlank() }
        val hasMdbImdbRating = meta.externalRatings.any { it.source == PROVIDER_IMDB }
        val validImdbRating = meta.imdbRating
            ?.takeIf { raw -> raw.toDoubleOrNull()?.let { it > 0.0 } == true }
        val hasMetaRow = releaseLine != null ||
            runtimeText != null ||
            ageBadge != null ||
            (validImdbRating != null && !hasMdbImdbRating)
        if (hasMetaRow) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                releaseLine?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                    )
                }
                runtimeText?.let { rt ->
                    Text(
                        text = rt,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                    )
                }
                ageBadge?.let { badge ->
                    DetailHeroMetaBadge(text = badge)
                }
                if (validImdbRating != null && !hasMdbImdbRating) {
                    val imdbTextStyle = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.sp,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ImdbRatingSourceLabel(
                            storeTextStyle = imdbTextStyle,
                            storeTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = validImdbRating,
                            style = imdbTextStyle,
                            color = ImdbYellow,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = meta.externalRatings.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            DetailRatingsRow(
                ratings = meta.externalRatings,
                horizontalScrollPadding = horizontalScrollPadding,
            )
        }

        if (meta.director.isNotEmpty()) {
            MetaLabelValueRow(
                label = stringResource(Res.string.details_director),
                value = meta.director.joinToString(", "),
            )
        }

        if (meta.writer.isNotEmpty()) {
            MetaLabelValueRow(
                label = stringResource(Res.string.details_writer),
                value = meta.writer.joinToString(", "),
            )
        }

        if (!meta.description.isNullOrBlank()) {
            ExpandableDescription(
                text = meta.description,
                collapsedMaxLines = 3,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            )
        }
    }
}

@Composable
private fun DetailRatingsRow(
    ratings: List<MetaExternalRating>,
    horizontalScrollPadding: Dp,
) {
    val orderedRatings = remember(ratings) {
        val bySource = ratings.associateBy { it.source }
        ratingVisuals.mapNotNull { visuals ->
            bySource[visuals.source]?.let { rating -> visuals to rating }
        }
    }

    if (orderedRatings.isEmpty()) return

    Row(
        modifier = Modifier
            .nuvioHorizontalScrollBleed(horizontalScrollPadding)
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = horizontalScrollPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        orderedRatings.forEach { (visuals, rating) ->
            val ratingTextStyle = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.sp,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (visuals.source == PROVIDER_IMDB && !AppFeaturePolicy.imdbRatingLogoEnabled) {
                    ImdbRatingSourceLabel(
                        storeTextStyle = ratingTextStyle,
                        storeTextColor = visuals.valueColor,
                    )
                } else {
                    val logoHeight = when (rating.rottenTomatoesStatus) {
                        RottenTomatoesStatus.CERTIFIED_FRESH, RottenTomatoesStatus.VERIFIED_HOT -> 24.dp
                        else -> 16.dp
                    }
                    Image(
                        painter = painterResource(visuals.logoFor(rating)),
                        contentDescription = visuals.displayName,
                        modifier = Modifier.size(width = maxOf(visuals.logoWidth, logoHeight), height = logoHeight),
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = visuals.format(rating.value),
                    style = ratingTextStyle,
                    color = visuals.valueColor,
                )
            }
        }
    }
}

@Composable
private fun ImdbRatingSourceLabel(
    storeTextStyle: TextStyle,
    storeTextColor: Color,
) {
    if (AppFeaturePolicy.imdbRatingLogoEnabled) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = ImdbYellow,
        ) {
            Text(
                text = stringResource(Res.string.source_imdb),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.sp,
                ),
                color = ImdbBlack,
            )
        }
    } else {
        Text(
            text = stringResource(Res.string.source_imdb),
            style = storeTextStyle,
            color = storeTextColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun MetaLabelValueRow(
    label: String,
    value: String,
) {
    Row {
        Text(
            text = "$label:  ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DetailHeroMetaBadge(
    text: String,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        modifier = Modifier
            .border(
                border = BorderStroke(1.dp, contentColor.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val ImdbYellow = Color(0xFFF5C518)
private val ImdbBlack = Color(0xFF000000)

private data class RatingVisuals(
    val source: String,
    val displayName: String,
    val logo: DrawableResource,
    val logoWidth: androidx.compose.ui.unit.Dp,
    val valueColor: Color,
    val format: (Double) -> String,
)

private fun RatingVisuals.logoFor(rating: MetaExternalRating): DrawableResource =
    when (rating.rottenTomatoesStatus) {
        RottenTomatoesStatus.FRESH -> Res.drawable.rating_rotten_tomatoes
        RottenTomatoesStatus.ROTTEN -> Res.drawable.rating_rotten_tomatoes_rotten
        RottenTomatoesStatus.CERTIFIED_FRESH -> Res.drawable.rating_rotten_tomatoes_certified
        RottenTomatoesStatus.HOT -> Res.drawable.rating_audience_score
        RottenTomatoesStatus.STALE -> Res.drawable.rating_audience_stale
        RottenTomatoesStatus.VERIFIED_HOT -> Res.drawable.rating_audience_verified_hot
        null -> logo
    }

private val ratingVisuals = listOf(
    RatingVisuals(
        source = PROVIDER_IMDB,
        displayName = "IMDb",
        logo = Res.drawable.rating_imdb,
        logoWidth = 30.dp,
        valueColor = Color(0xFFF5C518),
        format = ::formatOneDecimal,
    ),
    RatingVisuals(
        source = PROVIDER_TMDB,
        displayName = "TMDB",
        logo = Res.drawable.rating_tmdb,
        logoWidth = 16.dp,
        valueColor = Color(0xFF01B4E4),
        format = ::formatWhole,
    ),
    RatingVisuals(
        source = PROVIDER_TRAKT,
        displayName = "Trakt",
        logo = Res.drawable.rating_trakt,
        logoWidth = 16.dp,
        valueColor = Color(0xFFED1C24),
        format = ::formatWhole,
    ),
    RatingVisuals(
        source = PROVIDER_LETTERBOXD,
        displayName = "Letterboxd",
        logo = Res.drawable.rating_letterboxd,
        logoWidth = 16.dp,
        valueColor = Color(0xFF00E054),
        format = ::formatOneDecimal,
    ),
    RatingVisuals(
        source = PROVIDER_MAL,
        displayName = "MyAnimeList",
        logo = Res.drawable.rating_mal,
        logoWidth = 16.dp,
        valueColor = Color(0xFF2E51A2),
        format = ::formatOneDecimal,
    ),
    RatingVisuals(
        source = PROVIDER_TOMATOES,
        displayName = "Rotten Tomatoes",
        logo = Res.drawable.rating_rotten_tomatoes,
        logoWidth = 16.dp,
        valueColor = Color(0xFFFA320A),
        format = ::formatPercent,
    ),
    RatingVisuals(
        source = PROVIDER_AUDIENCE,
        displayName = runBlocking { getString(Res.string.rating_audience_score) },
        logo = Res.drawable.rating_audience_score,
        logoWidth = 16.dp,
        valueColor = Color(0xFFFA320A),
        format = ::formatPercent,
    ),
    RatingVisuals(
        source = PROVIDER_METACRITIC,
        displayName = "Metacritic",
        logo = Res.drawable.rating_metacritic,
        logoWidth = 16.dp,
        valueColor = Color(0xFFFFCC33),
        format = ::formatWhole,
    ),
)

private fun formatOneDecimal(value: Double): String {
    val rounded = (value * 10.0).roundToInt()
    val whole = rounded / 10
    val decimal = (rounded % 10).absoluteValue
    return "$whole.$decimal"
}

private fun formatWhole(value: Double): String = value.roundToInt().toString()

private fun formatPercent(value: Double): String = "${value.roundToInt()}%"
