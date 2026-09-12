package com.nuvio.app.features.plugins.runtime.crypto

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.nuvio.app.features.plugins.runtime.host.HostModule
import com.nuvio.app.features.plugins.pluginDigestHex
import com.nuvio.app.features.plugins.pluginHmacHex
import com.nuvio.app.features.plugins.pluginBase64Encode
import com.nuvio.app.features.plugins.pluginBase64Decode
import com.nuvio.app.features.plugins.pluginUtf8ToHex
import com.nuvio.app.features.plugins.pluginHexToUtf8
import com.nuvio.app.features.plugins.pluginHexToByteArray
import com.nuvio.app.features.plugins.pluginGetRandomValues
import com.nuvio.app.features.plugins.pluginDigest
import com.nuvio.app.features.plugins.pluginHmac
import com.nuvio.app.features.plugins.pluginPbkdf2
import com.nuvio.app.features.plugins.pluginAesDecrypt
import com.nuvio.app.features.plugins.pluginAesEncrypt
import com.nuvio.app.features.plugins.pluginSign
import com.nuvio.app.features.plugins.pluginVerify

import com.nuvio.app.features.plugins.pluginPowFindNonce

internal class CryptoBridge : HostModule {
    override fun register(runtime: QuickJs) {
        // ABDX-style proof-of-work solved natively in one bridge call
        // (SHA-256("challenge:nonce") leading-zero-bits >= difficulty).
        runtime.function("__crypto_pow_find_nonce") { args ->
            val challenge = args.getOrNull(0)?.toString() ?: ""
            val difficulty = (args.getOrNull(1) as? Number)?.toInt() ?: 18
            runCatching {
                pluginPowFindNonce(challenge, difficulty)
            }.getOrDefault("")
        }

        // Hex transport keeps binary data stable across the QuickJS/native bridge.
        runtime.function("__crypto_get_random_values_hex") { args ->
            val length = (args.getOrNull(0) as? Number)?.toInt() ?: 0
            pluginGetRandomValues(length).toHexString()
        }

        runtime.function("__crypto_digest_hex_raw") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: "SHA256"
            val data = pluginHexToByteArray(args.getOrNull(1)?.toString() ?: "")
            pluginDigest(algorithm, data).toHexString()
        }

        runtime.function("__crypto_hmac_hex_raw") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: "SHA256"
            val key = pluginHexToByteArray(args.getOrNull(1)?.toString() ?: "")
            val data = pluginHexToByteArray(args.getOrNull(2)?.toString() ?: "")
            pluginHmac(algorithm, key, data).toHexString()
        }

        runtime.function("__crypto_pbkdf2_hex") { args ->
            val password = pluginHexToByteArray(args.getOrNull(0)?.toString() ?: "")
            val salt = pluginHexToByteArray(args.getOrNull(1)?.toString() ?: "")
            val iterations = (args.getOrNull(2) as? Number)?.toInt() ?: 1000
            val keySizeBits = (args.getOrNull(3) as? Number)?.toInt() ?: 256
            val algorithm = args.getOrNull(4)?.toString() ?: "SHA256"
            pluginPbkdf2(password, salt, iterations, keySizeBits, algorithm).toHexString()
        }

        runtime.function("__crypto_aes_encrypt_hex") { args ->
            val mode = args.getOrNull(0)?.toString() ?: "AES-CBC"
            val key = pluginHexToByteArray(args.getOrNull(1)?.toString() ?: "")
            val iv = pluginHexToByteArray(args.getOrNull(2)?.toString() ?: "")
            val data = pluginHexToByteArray(args.getOrNull(3)?.toString() ?: "")
            pluginAesEncrypt(mode, key, iv, data).toHexString()
        }

        runtime.function("__crypto_aes_decrypt_hex") { args ->
            val mode = args.getOrNull(0)?.toString() ?: "AES-CBC"
            val key = pluginHexToByteArray(args.getOrNull(1)?.toString() ?: "")
            val iv = pluginHexToByteArray(args.getOrNull(2)?.toString() ?: "")
            val data = pluginHexToByteArray(args.getOrNull(3)?.toString() ?: "")
            pluginAesDecrypt(mode, key, iv, data).toHexString()
        }

        runtime.function("__crypto_sign_hex") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: ""
            val privateKey = pluginHexToByteArray(args.getOrNull(1)?.toString() ?: "")
            val data = pluginHexToByteArray(args.getOrNull(2)?.toString() ?: "")
            pluginSign(algorithm, privateKey, data).toHexString()
        }

        runtime.function("__crypto_verify_hex") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: ""
            val publicKey = pluginHexToByteArray(args.getOrNull(1)?.toString() ?: "")
            val signature = pluginHexToByteArray(args.getOrNull(2)?.toString() ?: "")
            val data = pluginHexToByteArray(args.getOrNull(3)?.toString() ?: "")
            pluginVerify(algorithm, publicKey, signature, data)
        }

        // --- Legacy Hex/String Bridges (Backward Compatibility) ---

        runtime.function("__crypto_digest_hex") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: "SHA256"
            val data = args.getOrNull(1)?.toString() ?: ""
            runCatching {
                pluginDigestHex(algorithm, data)
            }.getOrDefault("")
        }

        runtime.function("__crypto_hmac_hex") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: "SHA256"
            val key = args.getOrNull(1)?.toString() ?: ""
            val data = args.getOrNull(2)?.toString() ?: ""
            runCatching {
                pluginHmacHex(algorithm, key, data)
            }.getOrDefault("")
        }

        runtime.function("__crypto_base64_encode") { args ->
            val data = args.getOrNull(0)?.toString() ?: ""
            runCatching {
                pluginBase64Encode(data)
            }.getOrDefault("")
        }

        runtime.function("__crypto_base64_decode") { args ->
            val data = args.getOrNull(0)?.toString() ?: ""
            runCatching {
                pluginBase64Decode(data)
            }.getOrDefault("")
        }

        runtime.function("__crypto_utf8_to_hex") { args ->
            val data = args.getOrNull(0)?.toString() ?: ""
            runCatching {
                pluginUtf8ToHex(data)
            }.getOrDefault("")
        }

        runtime.function("__crypto_hex_to_utf8") { args ->
            val data = args.getOrNull(0)?.toString() ?: ""
            runCatching {
                pluginHexToUtf8(data)
            }.getOrDefault("")
        }
    }
}

private fun ByteArray.toHexString(): String =
    joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }
