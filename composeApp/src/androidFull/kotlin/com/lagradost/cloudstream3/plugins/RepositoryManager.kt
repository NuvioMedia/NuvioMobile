package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData

/**
 * Read-only compatibility view. Repository installation remains owned by Nuvio,
 * so downloaded plugins cannot mutate or remove the user's Nuvio repositories.
 */
object RepositoryManager {
    val PREBUILT_REPOSITORIES: Array<RepositoryData> = emptyArray()

    fun getRepositories(): Array<RepositoryData> = emptyArray()

    /** Repository ownership stays with Nuvio; this ABI intentionally performs no mutation. */
    suspend fun removeRepository(context: Context, repository: RepositoryData) = Unit
}
