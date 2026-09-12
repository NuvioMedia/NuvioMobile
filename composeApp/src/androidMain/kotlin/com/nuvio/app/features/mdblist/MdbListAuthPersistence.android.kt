package com.nuvio.app.features.mdblist

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal actual object PlatformMdbListAuthPersistence : MdbListAuthPersistence {
    private const val keyAlias = "com.nuvio.media.mdblist.credentials.v1"
    private lateinit var preferences: android.content.SharedPreferences

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences("nuvio_mdblist_auth", Context.MODE_PRIVATE)
    }

    private val lock = Any()

    actual override fun read(profileId: Int): String? = synchronized(lock) {
        if (!::preferences.isInitialized) return@synchronized null
        val key = "profile.$profileId"
        val value = preferences.getString(key, null) ?: return@synchronized null
        try {
            val parts = value.split('.', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, decode(parts[0])))
            cipher.updateAAD("$keyAlias:$key".toByteArray(Charsets.UTF_8))
            cipher.doFinal(decode(parts[1])).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            if (!preferences.edit().remove(key).commit()) throw IOException("Unable to clear protected credentials")
            null
        }
    }

    actual override fun write(profileId: Int, value: String?) = synchronized(lock) {
        val key = "profile.$profileId"
        val editor = preferences.edit()
        if (value == null) {
            editor.remove(key)
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD("$keyAlias:$key".toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            editor.putString(key, "${encode(cipher.iv)}.${encode(encrypted)}")
        }
        if (!editor.commit()) throw IOException("Unable to save protected credentials")
    }

    actual override fun clear() = synchronized(lock) {
        if (!::preferences.isInitialized) return@synchronized
        if (!preferences.edit().clear().commit()) throw IOException("Unable to clear protected credentials")
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}
