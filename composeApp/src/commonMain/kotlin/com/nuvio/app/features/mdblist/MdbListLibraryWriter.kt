package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingListManagementCapabilities
import com.nuvio.app.features.tracking.TrackingListManager
import com.nuvio.app.features.tracking.supportsContentType
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.tracking.LibraryListPrivacy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class MdbListLibraryWriter(
    private val api: MdbListApiClient,
    private val sync: MdbListSyncRepository,
    private val now: () -> Long
) : TrackingListManager {
    override val capabilities = TrackingListManagementCapabilities(
        privacyOptions = listOf(LibraryListPrivacy.PRIVATE, LibraryListPrivacy.PUBLIC)
    )

    override suspend fun createList(name: String, description: String?, privacy: LibraryListPrivacy) {
        val body = metadataBody(name, description, privacy)
        val scope = sync.currentScope()
        write(scope) { library ->
            val response = mdbListResponseElement(api.post("/lists/user/add", body, scope).body).objectValue()
            val id = response.number("id")?.takeIf { it > 0 } ?: throw MdbListDecodingException()
            val created = MdbListLibraryList(id, response.text("name") ?: name.trim(), privacy == LibraryListPrivacy.PRIVATE)
            library.copy(lists = library.lists.filterNot { it.id == id } + created,
                itemsByList = library.itemsByList + (created.key to emptyList()))
        }
    }

    override suspend fun updateList(key: String, name: String, description: String?, privacy: LibraryListPrivacy) {
        val body = metadataBody(name, description, privacy)
        val id = mdbListPersonalListId(key)
        val scope = sync.currentScope()
        write(scope) { library ->
            require(library.lists.any { it.id == id }) { "This static list is no longer available" }
            val response = mdbListResponseElement(api.put("/lists/$id", body, scope).body).objectValue()
            if (response.flag("success") != true || response.number("id") != id) throw MdbListDecodingException()
            library.copy(lists = library.lists.map { list ->
                if (list.id != id) list else list.copy(name = response.text("name") ?: name.trim(),
                    private = response.flag("private") ?: (privacy == LibraryListPrivacy.PRIVATE))
            })
        }
    }

    override suspend fun deleteList(key: String) {
        val id = mdbListPersonalListId(key)
        val scope = sync.currentScope()
        write(scope) { library ->
            require(library.lists.any { it.id == id }) { "This static list is no longer available" }
            val response = api.delete("/lists/$id", scope)
            if (response.status != 204) {
                val body = mdbListResponseElement(response.body).objectValue()
                if (body.flag("success") != true || body.number("id") != id) throw MdbListDecodingException()
            }
            library.copy(lists = library.lists.filterNot { it.id == id }, itemsByList = library.itemsByList - key)
        }
    }

    suspend fun applyMembershipChanges(scope: MdbListAuthScope, input: LibraryItem, changes: Map<String, Boolean>) {
        val suppliedTarget = runCatching { input.mdbListLibraryItem() }.getOrNull()
        for ((key, desired) in changes) {
            mdbListLibraryItemsPath(key)
            write(scope) { library ->
                val tab = library.tabs().firstOrNull { it.key == key }
                require(tab != null) { "This list is no longer available" }
                val contentType = mdbListLibraryType(input.type)?.contentType ?: input.type
                if (!desired && !tab.supportsContentType(contentType)) return@write library
                require(tab.supportsContentType(contentType)) { "This list does not accept this item" }
                val current = library.itemsByList[key].orEmpty()
                val resolved = suppliedTarget ?: current.firstOrNull {
                    it.type == mdbListLibraryType(input.type) && input.id in it.media.ids.aliases()
                }
                if (resolved == null && !desired) return@write library
                val target = requireNotNull(resolved) { "An external movie or show ID is required" }
                if (current.any(target::matches) == desired) return@write library
                val action = if (desired) "add" else "remove"
                val response = api.post("${mdbListLibraryItemsPath(key)}/$action", target.membershipBody(key == MDBLIST_WATCHLIST_KEY), scope)
                verifyMdbListMembershipResponse(response.body, target.type, desired)
                val items = current.filterNot(target::matches) + if (desired) listOf(target.copy(
                    listedAt = now(), rank = (current.mapNotNull { it.rank }.maxOrNull() ?: 0) + 1
                )) else emptyList()
                library.copy(itemsByList = library.itemsByList + (key to items))
            }
        }
    }

    private fun metadataBody(name: String, description: String?, privacy: LibraryListPrivacy): String {
        require(name.isNotBlank()) { "Enter a list name" }
        require(description.isNullOrBlank()) { "MDBList does not support editing list descriptions" }
        require(privacy in capabilities.privacyOptions) { "MDBList supports private and public lists" }
        return buildJsonObject { put("name", name.trim()); put("private", privacy == LibraryListPrivacy.PRIVATE) }.toString()
    }

    private suspend fun write(scope: MdbListAuthScope, update: suspend (MdbListLibrarySnapshot) -> MdbListLibrarySnapshot) {
        try {
            sync.mutate(scope) { previous ->
                val library = previous.library ?: MdbListLibraryRemote(api, scope).synchronize(null, previous.accountId, now())
                previous.copy(library = update(library)) to Unit
            }
        } catch (error: Exception) {
            withContext(NonCancellable) {
                try {
                    sync.mutate(scope) { previous -> previous.copy(library = previous.library?.copy(invalidated = true)) to Unit }
                } catch (_: Exception) {
                }
            }
            throw error
        }
    }
}
