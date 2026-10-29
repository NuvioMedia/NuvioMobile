package com.nuvio.app.features.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.SkeletonBlock
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioHorizontalScrollBleed
import com.nuvio.app.core.ui.withDuplicateSafeLazyKeys
import com.nuvio.app.features.trakt.TraktCommentReview
import kotlinx.coroutines.flow.distinctUntilChanged
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun DetailCommentsSection(
    comments: List<TraktCommentReview>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    canLoadMore: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onCommentClick: (TraktCommentReview) -> Unit,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    horizontalScrollPadding: Dp = 0.dp,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(listState, comments.size, canLoadMore, isLoadingMore, isLoading, error) {
        if (isLoading || !error.isNullOrBlank()) return@LaunchedEffect
        snapshotFlow {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val totalItems = listState.layoutInfo.totalItemsCount
            canLoadMore && !isLoadingMore && totalItems > 0 && lastVisibleIndex >= totalItems - 3
        }
            .distinctUntilChanged()
            .collect { shouldLoadMore ->
                if (shouldLoadMore) onLoadMore()
            }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (showHeader) {
            CommentsHeader()
            Spacer(modifier = Modifier.height(12.dp))
        }

        when {
            isLoading -> {
                LazyRow(
                    modifier = Modifier
                        .nuvioHorizontalScrollBleed(horizontalScrollPadding)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = horizontalScrollPadding),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(3) {
                        LoadingCommentCard()
                    }
                }
            }

            !error.isNullOrBlank() -> {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Text(stringResource(Res.string.action_retry))
                    }
                }
            }

            comments.isEmpty() -> {
                Text(
                    text = stringResource(Res.string.detail_comments_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                LazyRow(
                    modifier = Modifier
                        .nuvioHorizontalScrollBleed(horizontalScrollPadding)
                        .fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = horizontalScrollPadding),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        items = comments.withDuplicateSafeLazyKeys { it.id },
                        key = { it.lazyKey },
                    ) { keyedReview ->
                        val review = keyedReview.value
                        CommentCard(
                            review = review,
                            onClick = { onCommentClick(review) },
                        )
                    }
                    if (isLoadingMore) {
                        item(key = "loading_more_comments") {
                            LoadingCommentCard()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentsHeader() {
    BoxWithConstraints {
        val isTablet = maxWidth >= 720.dp
        val titleSize = if (isTablet) 22.sp else 20.sp

        Text(
            text = stringResource(Res.string.detail_comments_title),
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = titleSize,
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun CommentCard(
    review: TraktCommentReview,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isAmoled = colorScheme.background == Color.Black && colorScheme.surface == Color(0xFF050505)
    val bodyText = if (review.hasSpoilerContent) {
        stringResource(Res.string.detail_comments_spoiler_card)
    } else {
        review.comment
    }

    BoxWithConstraints {
        val isTablet = maxWidth >= 720.dp
        val cardWidth = if (isTablet) 340.dp else 280.dp
        val cardHeight = if (isTablet) 210.dp else 190.dp

        Surface(
            modifier = modifier
                .width(cardWidth)
                .height(cardHeight)
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(16.dp),
            color = if (isAmoled) Color(0xFF121212) else colorScheme.surfaceVariant.copy(alpha = 0.5f),
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = review.authorDisplayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )

                if (review.review) {
                    CommentChip(text = stringResource(Res.string.detail_comments_badge_review))
                }

                Text(
                    text = bodyText,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                    color = if (review.hasSpoilerContent) {
                        MaterialTheme.nuvio.colors.warning
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    review.rating?.let { rating ->
                        Text(
                            text = stringResource(Res.string.detail_comments_badge_rating, rating),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = stringResource(Res.string.detail_comments_likes, review.likes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CommentChip(text: String) {
    Box(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun LoadingCommentCard() {
    BoxWithConstraints {
        val isTablet = maxWidth >= 720.dp
        val cardWidth = if (isTablet) 340.dp else 280.dp
        val cardHeight = if (isTablet) 210.dp else 190.dp

        Surface(
            modifier = Modifier.width(cardWidth).height(cardHeight),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SkeletonBlock(width = 96.dp, height = 14.dp)
                Spacer(modifier = Modifier.height(6.dp))
                SkeletonBlock(modifier = Modifier.fillMaxWidth(), height = 12.dp)
                SkeletonBlock(modifier = Modifier.fillMaxWidth(0.94f), height = 12.dp)
                SkeletonBlock(modifier = Modifier.fillMaxWidth(0.68f), height = 12.dp)
                Spacer(modifier = Modifier.weight(1f))
                SkeletonBlock(width = 64.dp, height = 10.dp)
            }
        }
    }
}
