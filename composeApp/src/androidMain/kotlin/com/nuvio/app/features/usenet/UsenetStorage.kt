package com.nuvio.app.features.usenet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json

/** Profile-local sources. Keystore ciphertext is never part of profile or cloud sync. */
internal class UsenetSourceStorage(context: Context) {
    private val preferences = context.getSharedPreferences("usenet_sources", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    // Decrypting takes a keystore round trip and screens read the sources on the main thread:
    // each profile is decrypted once, and every later read is this copy.
    private val decrypted = HashMap<Int, UsenetSourceConfiguration>()

    @Synchronized
    fun read(profileId: Int): UsenetSourceConfiguration {
        decrypted[profileId]?.let { return it }
        val encrypted = preferences.getString(key(profileId), null) ?: return UsenetSourceConfiguration()
        // Do not silently overwrite credentials if the device keystore becomes unavailable.
        val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(key(profileId).toByteArray())
        return json.decodeFromString<UsenetSourceConfiguration>(
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8),
        ).also { decrypted[profileId] = it }
    }

    @Synchronized
    fun update(profileId: Int, value: UsenetSourceConfiguration) {
        value.validate()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(key(profileId).toByteArray())
        val bytes = cipher.iv + cipher.doFinal(json.encodeToString(UsenetSourceConfiguration.serializer(), value).toByteArray())
        check(preferences.edit().putString(key(profileId), Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) {
            "Could not save Usenet sources"
        }
        decrypted[profileId] = value
    }

    @Synchronized
    fun removeProfile(profileId: Int) {
        // Dropped first: a failed write must not leave removed credentials readable.
        decrypted.remove(profileId)
        preferences.edit().remove(key(profileId)).commit()
    }

    @Synchronized
    fun clear() {
        decrypted.clear()
        preferences.edit().clear().commit()
    }

    private fun key(profileId: Int) = "profile_$profileId"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "nuvio_usenet_sources_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** Device-local engine tuning, shared by profiles; never holds credentials or NZB URLs. */
internal class UsenetEngineSettingsStorage(context: Context) {
    private val preferences = context.getSharedPreferences("usenet_performance", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun read(): UsenetConfiguration = preferences.getString(KEY, null)
        ?.let { runCatching { json.decodeFromString(UsenetConfiguration.serializer(), it) }.getOrNull() }
        ?.sanitized()
        ?: UsenetConfiguration()

    fun update(value: UsenetConfiguration) {
        preferences.edit().putString(KEY, json.encodeToString(UsenetConfiguration.serializer(), value.sanitized())).apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object { const val KEY = "configuration" }
}
