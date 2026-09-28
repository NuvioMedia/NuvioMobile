package com.nuvio.app.features.tracking

import kotlinx.coroutines.flow.Flow

interface TrackingLibrarySorter {
    fun observeAddedOrder(listKey: String, descending: Boolean): Flow<List<String>?>
}
