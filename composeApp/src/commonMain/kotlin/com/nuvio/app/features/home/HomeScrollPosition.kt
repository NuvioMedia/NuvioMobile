package com.nuvio.app.features.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

@Composable
internal fun MaintainHomeScrollPosition(
    listState: LazyListState,
    profileId: Int,
    showHeroSlot: Boolean,
) {
    var scrollProfileId by rememberSaveable { mutableStateOf(profileId) }
    var hasScrolled by rememberSaveable {
        mutableStateOf(listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
    }

    LaunchedEffect(profileId, showHeroSlot, listState) {
        if (scrollProfileId != profileId) {
            scrollProfileId = profileId
            hasScrolled = false
            listState.requestScrollToItem(0)
        } else if (showHeroSlot && !hasScrolled) {
            listState.requestScrollToItem(0)
        }
    }

    LaunchedEffect(profileId, listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { isScrolling ->
            if (isScrolling) hasScrolled = true
        }
    }
}
