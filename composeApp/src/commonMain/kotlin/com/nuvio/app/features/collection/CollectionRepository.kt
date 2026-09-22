package com.nuvio.app.features.collection

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.enabledAddons
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.collections_import_error_collection_blank_id
import nuvio.composeapp.generated.resources.collections_import_error_collection_blank_title
import nuvio.composeapp.generated.resources.collections_import_error_collection_duplicate_id
import nuvio.composeapp.generated.resources.collections_import_error_empty_json
import nuvio.composeapp.generated.resources.collections_import_error_folder_blank_id
import nuvio.composeapp.generated.resources.collections_import_error_folder_blank_title
import nuvio.composeapp.generated.resources.collections_import_error_folder_duplicate_id
import nuvio.composeapp.generated.resources.collections_import_error_invalid_json
import nuvio.composeapp.generated.resources.collections_import_error_source_blank_fields
import nuvio.composeapp.generated.resources.collections_import_error_trakt_list_id
import org.jetbrains.compose.resources.getString
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

object CollectionRepository {
    private val log = Logger.withTag("CollectionRepository")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _collections = MutableStateFlow<List<Collection>>(emptyList())
    val collections: StateFlow<List<Collection>> = _collections.asStateFlow()
    private val _localChangeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    internal val localChangeEvents: SharedFlow<Unit> = _localChangeEvents.asSharedFlow()
    private var rawCollectionsJson: JsonElement = JsonArray(emptyList())

    private var hasLoaded = false

    fun initialize() {
        if (hasLoaded) return
        hasLoaded = true
        val payload = CollectionStorage.loadPayload()
        if (payload.isNullOrBlank()) return

        runCatching {
            val parsed = json.parseToJsonElement(payload)
            rawCollectionsJson = parsed
            val decoded = json.decodeFromJsonElement<List<Collection>>(parsed)
            val normalized = normalizeCollections(decoded, source = "local storage")
            _collections.value = CollectionMobileSettingsRepository.applyToCollections(normalized)
            if (normalized.size != decoded.size) {
                persist(sync = false)
            }
        }.onFailure { e ->
            log.e(e) { "Failed to load collections from storage" }
        }
    }

    fun onProfileChanged() {
        hasLoaded = false
        _collections.value = emptyList()
        rawCollectionsJson = JsonArray(emptyList())
    }

    fun clearLocalState() {
        hasLoaded = false
        _collections.value = emptyList()
        rawCollectionsJson = JsonArray(emptyList())
    }

    fun getCollection(id: String): Collection? =
        _collections.value.find { it.id == id }

    fun addCollection(collection: Collection) {
        ensureLoaded()
        val decorated = CollectionMobileSettingsRepository.applyToCollection(collection)
        _collections.value = _collections.value.upsertCollectionById(decorated)
        persist()
    }

    fun updateCollection(collection: Collection) {
        ensureLoaded()
        val decorated = CollectionMobileSettingsRepository.applyToCollection(collection)
        _collections.value = _collections.value.deduplicatedById().map {
            if (it.id == collection.id) decorated else it
        }
        persist()
    }

    fun removeCollection(collectionId: String) {
        ensureLoaded()
        _collections.value = _collections.value.filter { it.id != collectionId }
        persist()
    }

    fun setCollections(collections: List<Collection>) {
        ensureLoaded()
        val normalized = normalizeCollections(collections, source = "setCollections")
        _collections.value = CollectionMobileSettingsRepository.applyToCollections(normalized)
        persist()
    }

    fun moveUp(index: Int) {
        moveByIndex(index, index - 1)
    }

    fun moveDown(index: Int) {
        moveByIndex(index, index + 1)
    }

    fun moveByIndex(fromIndex: Int, toIndex: Int) {
        ensureLoaded()
        val list = _collections.value.toMutableList()
        if (fromIndex == toIndex) return
        if (fromIndex !in list.indices || toIndex !in list.indices) return
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        _collections.value = list
        persist()
    }

    fun exportToJson(): String {
        ensureLoaded()
        return mergedCollectionsJson().toString()
    }

    fun importFromJson(jsonString: String): Result<List<Collection>> {
        return runCatching {
            val validation = validateJson(jsonString)
            if (!validation.valid) {
                throw IllegalArgumentException(validation.error.orEmpty())
            }
            rawCollectionsJson = json.parseToJsonElement(jsonString)
            val imported = json.decodeFromString<List<Collection>>(jsonString).deduplicatedById()
            _collections.value = CollectionMobileSettingsRepository.applyToCollections(imported)
            persist()
            imported
        }
    }

    fun validateJson(jsonString: String): ValidationResult {
        if (jsonString.isBlank()) {
            return ValidationResult(
                valid = false,
                error = runBlocking { getString(Res.string.collections_import_error_empty_json) },
            )
        }
        return try {
            val collections = json.decodeFromString<List<Collection>>(jsonString)
            validateImportModel(collections)?.let { error ->
                return ValidationResult(valid = false, error = error.localizedMessage())
            }
            ValidationResult(
                valid = true,
                collectionCount = collections.size,
                folderCount = collections.sumOf { it.folders.size },
            )
        } catch (e: Exception) {
            ValidationResult(
                valid = false,
                error = runBlocking {
                    getString(Res.string.collections_import_error_invalid_json, e.message.orEmpty())
                },
            )
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun generateId(): String = Uuid.random().toString()

    fun getAvailableCatalogs(): List<AvailableCatalog> {
        val addons = AddonRepository.uiState.value.addons.enabledAddons()
        return addons.mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            addon to manifest
        }.flatMap { (addon, manifest) ->
            manifest.catalogs
                .filter { catalog -> catalog.extra.none { it.isRequired && it.name != "genre" } }
                .map { catalog ->
                    val genreExtra = catalog.extra.firstOrNull { it.name == "genre" }
                    AvailableCatalog(
                        addonId = manifest.id,
                        addonName = addon.displayTitle,
                        type = catalog.type,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        genreOptions = genreExtra?.options.orEmpty(),
                        genreRequired = genreExtra?.isRequired == true,
                    )
                }
        }
    }

    internal fun applyFromRemote(collections: List<Collection>, rawJson: JsonElement) {
        rawCollectionsJson = rawJson
        val normalized = normalizeCollections(collections, source = "remote sync")
        _collections.value = CollectionMobileSettingsRepository.applyToCollections(normalized)
        persist(sync = false)
    }

    internal fun onMobileSettingsChanged() {
        if (!hasLoaded) return
        _collections.value = CollectionMobileSettingsRepository.applyToCollections(
            _collections.value.deduplicatedById(),
        )
    }

    private fun normalizeCollections(collections: List<Collection>, source: String): List<Collection> {
        val normalized = collections.deduplicatedById()
        if (normalized.size != collections.size) {
            log.w {
                "Dropped ${collections.size - normalized.size} duplicate collection IDs from $source"
            }
        }
        return normalized
    }

    private fun ensureLoaded() {
        if (!hasLoaded) initialize()
    }

    private fun persist(sync: Boolean = true) {
        runCatching {
            CollectionStorage.savePayload(mergedCollectionsJson().toString())
            if (sync) {
                _localChangeEvents.tryEmit(Unit)
            }
        }.onFailure { e ->
            log.e(e) { "Failed to persist collections" }
        }
    }

    private fun mergedCollectionsJson(): JsonArray =
        CollectionJsonPreserver.merge(json, rawCollectionsJson, _collections.value).also {
            rawCollectionsJson = it
        }
}

internal fun List<Collection>.deduplicatedById(): List<Collection> {
    if (size < 2) return this

    val collectionsById = linkedMapOf<String, Collection>()
    forEach { collection ->
        collectionsById[collection.id] = collection
    }
    return if (collectionsById.size == size) this else collectionsById.values.toList()
}

internal fun List<Collection>.upsertCollectionById(collection: Collection): List<Collection> {
    val normalized = deduplicatedById()
    val existingIndex = normalized.indexOfFirst { existing -> existing.id == collection.id }
    if (existingIndex == -1) return normalized + collection

    return normalized.toMutableList().apply {
        this[existingIndex] = collection
    }
}

internal sealed interface CollectionImportModelError {
    data class BlankCollectionId(val collectionIndex: Int) : CollectionImportModelError
    data class DuplicateCollectionId(val collectionId: String) : CollectionImportModelError
    data class BlankCollectionTitle(val collectionId: String) : CollectionImportModelError
    data class BlankFolderId(val folderIndex: Int, val collectionTitle: String) : CollectionImportModelError
    data class DuplicateFolderId(val folderId: String, val collectionTitle: String) : CollectionImportModelError
    data class BlankFolderTitle(val folderId: String, val collectionTitle: String) : CollectionImportModelError
    data class InvalidTraktListId(val sourceIndex: Int, val folderTitle: String) : CollectionImportModelError
    data class BlankSourceFields(val sourceIndex: Int, val folderTitle: String) : CollectionImportModelError
}

internal fun validateImportModel(collections: List<Collection>): CollectionImportModelError? {
    val collectionIds = mutableSetOf<String>()
    collections.forEachIndexed { ci, c ->
        if (c.id.isBlank()) {
            return CollectionImportModelError.BlankCollectionId(ci + 1)
        }
        if (!collectionIds.add(c.id)) {
            return CollectionImportModelError.DuplicateCollectionId(c.id)
        }
        if (c.title.isBlank()) {
            return CollectionImportModelError.BlankCollectionTitle(c.id)
        }

        val folderIds = mutableSetOf<String>()
        c.folders.forEachIndexed { fi, f ->
            if (f.id.isBlank()) {
                return CollectionImportModelError.BlankFolderId(fi + 1, c.title)
            }
            if (!folderIds.add(f.id)) {
                return CollectionImportModelError.DuplicateFolderId(f.id, c.title)
            }
            if (f.title.isBlank()) {
                return CollectionImportModelError.BlankFolderTitle(f.id, c.title)
            }
            f.resolvedSources.forEachIndexed { si, s ->
                if (s.hasInvalidTraktListId()) {
                    return CollectionImportModelError.InvalidTraktListId(si + 1, f.title)
                }

                val invalidAddon = !s.isTmdb && !s.isTrakt &&
                    (s.addonId.isNullOrBlank() || s.type.isNullOrBlank() || s.catalogId.isNullOrBlank())
                val invalidTmdb = s.isTmdb &&
                    s.tmdbSourceType.isNullOrBlank()
                if (invalidAddon || invalidTmdb) {
                    return CollectionImportModelError.BlankSourceFields(si + 1, f.title)
                }
            }
        }
    }
    return null
}

private fun CollectionImportModelError.localizedMessage(): String =
    runBlocking {
        when (this@localizedMessage) {
            is CollectionImportModelError.BlankCollectionId ->
                getString(Res.string.collections_import_error_collection_blank_id, collectionIndex)
            is CollectionImportModelError.DuplicateCollectionId ->
                getString(Res.string.collections_import_error_collection_duplicate_id, collectionId)
            is CollectionImportModelError.BlankCollectionTitle ->
                getString(Res.string.collections_import_error_collection_blank_title, collectionId)
            is CollectionImportModelError.BlankFolderId ->
                getString(Res.string.collections_import_error_folder_blank_id, folderIndex, collectionTitle)
            is CollectionImportModelError.DuplicateFolderId ->
                getString(Res.string.collections_import_error_folder_duplicate_id, folderId, collectionTitle)
            is CollectionImportModelError.BlankFolderTitle ->
                getString(Res.string.collections_import_error_folder_blank_title, folderId, collectionTitle)
            is CollectionImportModelError.InvalidTraktListId ->
                getString(Res.string.collections_import_error_trakt_list_id, sourceIndex, folderTitle)
            is CollectionImportModelError.BlankSourceFields ->
                getString(Res.string.collections_import_error_source_blank_fields, sourceIndex, folderTitle)
        }
    }
