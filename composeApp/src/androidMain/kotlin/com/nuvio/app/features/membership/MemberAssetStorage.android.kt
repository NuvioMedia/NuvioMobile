package com.nuvio.app.features.membership

import android.content.Context
import android.content.SharedPreferences
import java.io.File

internal actual object MemberAssetStorage {
    private const val preferencesName = "nuvio_member_access"
    private const val accessPayloadKey = "access_payload"
    private const val backgroundCatalogPayloadKey = "background_catalog_payload"
    private var preferences: SharedPreferences? = null
    private var legacyBrandingFile: File? = null
    private var backgroundDirectory: File? = null
    private var avatarDirectory: File? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        legacyBrandingFile = context.filesDir.resolve("membership/branding.png")
        backgroundDirectory = context.cacheDir.resolve("member_profile_backgrounds")
        avatarDirectory = context.cacheDir.resolve("member_profile_avatars")
    }

    actual fun loadAccessPayload(): String? = preferences?.getString(accessPayloadKey, null)

    actual fun saveAccessPayload(payload: String) {
        preferences?.edit()?.putString(accessPayloadKey, payload)?.apply()
        legacyBrandingFile?.delete()
    }

    actual fun loadProfileBackgroundCatalogPayload(): String? =
        preferences?.getString(backgroundCatalogPayloadKey, null)

    actual fun saveProfileBackgroundCatalogPayload(payload: String) {
        preferences?.edit()?.putString(backgroundCatalogPayloadKey, payload)?.apply()
    }

    actual fun loadProfileBackground(cacheKey: String): ByteArray? =
        backgroundFile(cacheKey)?.takeIf { it.isFile && it.length() > 0L }?.readBytes()

    actual fun saveProfileBackground(cacheKey: String, bytes: ByteArray) {
        val file = backgroundFile(cacheKey) ?: return
        saveFile(file, bytes)
    }

    actual fun loadProfileAvatar(cacheKey: String): String? =
        avatarFile(cacheKey)?.takeIf { it.isFile && it.length() > 0L }?.toFileUriString()

    actual fun saveProfileAvatar(cacheKey: String, bytes: ByteArray): String? {
        val file = avatarFile(cacheKey) ?: return null
        saveFile(file, bytes)
        return file.takeIf { it.isFile && it.length() > 0L }?.toFileUriString()
    }

    actual fun clearAccess() {
        preferences?.edit()?.remove(accessPayloadKey)?.apply()
        legacyBrandingFile?.delete()
    }

    private fun backgroundFile(cacheKey: String): File? {
        return backgroundDirectory?.resolve("${safeKey(cacheKey)}.png")
    }

    private fun avatarFile(cacheKey: String): File? = avatarDirectory?.resolve(safeKey(cacheKey))

    private fun saveFile(file: File, bytes: ByteArray) {
        val directory = file.parentFile ?: return
        directory.mkdirs()
        val temporary = directory.resolve(".${file.name}.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    private fun safeKey(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun File.toFileUriString(): String {
        val uri = toURI().toString()
        return if (uri.startsWith("file:/") && !uri.startsWith("file:///")) {
            uri.replaceFirst("file:/", "file:///")
        } else {
            uri
        }
    }
}
