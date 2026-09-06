package com.nuvio.app.features.plugins

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import com.nuvio.app.features.plugins.cryptointerop.*
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

internal fun pluginGetRandomValues(length: Int): ByteArray {
    require(length >= 0) { "Random byte length must be non-negative" }
    if (length == 0) return ByteArray(0)
    val bytes = ByteArray(length)
    @OptIn(ExperimentalForeignApi::class)
    val status = SecRandomCopyBytes(kSecRandomDefault, length.toULong(), bytes.refTo(0))
    require(status == 0) { "Failed to generate secure random bytes: status $status" }
    return bytes
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginDigest(algorithm: String, data: ByteArray): ByteArray {
    val normalized = normalizeDigestAlgorithm(algorithm)
    val output = ByteArray(
        when (normalized) {
            "MD5" -> CC_MD5_DIGEST_LENGTH.toInt()
            "SHA1" -> CC_SHA1_DIGEST_LENGTH.toInt()
            "SHA256" -> CC_SHA256_DIGEST_LENGTH.toInt()
            "SHA384" -> CC_SHA384_DIGEST_LENGTH.toInt()
            "SHA512" -> CC_SHA512_DIGEST_LENGTH.toInt()
            else -> error("Unsupported digest algorithm: $algorithm")
        },
    )

    data.usePinned { pinnedData ->
        output.usePinned { pinnedOutput ->
            val dataPtr = if (data.isNotEmpty()) pinnedData.addressOf(0) else null
            val outputPtr = pinnedOutput.addressOf(0).reinterpret<UByteVar>()

            when (normalized) {
                "MD5" -> CC_MD5(dataPtr, data.size.toUInt(), outputPtr)
                "SHA1" -> CC_SHA1(dataPtr, data.size.toUInt(), outputPtr)
                "SHA256" -> CC_SHA256(dataPtr, data.size.toUInt(), outputPtr)
                "SHA384" -> CC_SHA384(dataPtr, data.size.toUInt(), outputPtr)
                "SHA512" -> CC_SHA512(dataPtr, data.size.toUInt(), outputPtr)
            }
        }
    }

    return output
}

/**
 * ABDX-style Proof-of-Work solver (iOS): find the smallest nonce >= 0 such
 * that SHA-256(challenge + ":" + nonce) has at least [difficulty] leading
 * zero bits. Runs fully native via CommonCrypto.
 * @return nonce as decimal string
 */
@OptIn(ExperimentalForeignApi::class)
internal fun pluginPowFindNonce(challenge: String, difficulty: Int): String {
    val target = if (difficulty > 0) difficulty else 18
    val prefix = challenge.encodeToByteArray() // ASCII hex challenge
    // message buffer: challenge + ':' + up to 10 decimal digits
    val msg = ByteArray(prefix.size + 1 + 10)
    prefix.copyInto(msg, 0)
    msg[prefix.size] = ':'.code.toByte()
    val output = ByteArray(CC_SHA256_DIGEST_LENGTH.toInt())
    var nonce = 0L
    while (true) {
        var n = nonce
        var len = 0
        var tail = msg.size
        if (n == 0L) {
            msg[tail - 1] = '0'.code.toByte()
            len = 1
        } else {
            while (n > 0 && tail > prefix.size + 1) {
                msg[--tail] = ('0'.code.toByte() + (n % 10).toInt()).toByte()
                n /= 10
                len++
            }
            if (len < 10) {
                val dst = prefix.size + 1
                val src = msg.size - len
                for (i in 0 until len) msg[dst + i] = msg[src + i]
            }
        }
        val total = prefix.size + 1 + len
        msg.usePinned { pinnedMsg ->
            output.usePinned { pinnedOut ->
                val dataPtr = if (total > 0) pinnedMsg.addressOf(0).reinterpret<UByteVar>() else null
                val outPtr = pinnedOut.addressOf(0).reinterpret<UByteVar>()
                CC_SHA256(dataPtr, total.toUInt(), outPtr)
            }
        }
        if (leadingZeroBitsIos(output) >= target) return nonce.toString()
        nonce++
        if (nonce > (1L shl 24)) throw IllegalArgumentException("abdx pow cap exceeded")
    }
}

private fun leadingZeroBitsIos(digest: ByteArray): Int {
    var bits = 0
    for (b in digest) {
        val ub = b.toInt() and 0xff
        if (ub == 0) { bits += 8; continue }
        var x = ub
        while (x < 128) { bits++; x = x shl 1 }
        return bits
    }
    return bits
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginPbkdf2(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    keySizeBits: Int,
    algorithm: String,
): ByteArray {
    require(iterations > 0) { "PBKDF2 iterations must be positive" }
    require(keySizeBits > 0 && keySizeBits % 8 == 0) { "PBKDF2 key size must be a positive byte-aligned bit length" }

    val prf = normalizePbkdf2Prf(algorithm)
    
    val derivedKeyLen = keySizeBits / 8
    val derivedKey = ByteArray(derivedKeyLen)
    
    password.usePinned { pinnedPassword ->
        salt.usePinned { pinnedSalt ->
            derivedKey.usePinned { pinnedDerivedKey ->
                val passwordPtr = if (password.isNotEmpty()) pinnedPassword.addressOf(0).reinterpret<ByteVar>() else null
                val saltPtr = if (salt.isNotEmpty()) pinnedSalt.addressOf(0).reinterpret<UByteVar>() else null
                val derivedKeyPtr = pinnedDerivedKey.addressOf(0).reinterpret<UByteVar>()

                val status = CCKeyDerivationPBKDF(
                    algorithm = kCCPBKDF2,
                    password = passwordPtr,
                    passwordLen = password.size.toULong(),
                    salt = saltPtr,
                    saltLen = salt.size.toULong(),
                    prf = prf,
                    rounds = iterations.toUInt(),
                    derivedKey = derivedKeyPtr,
                    derivedKeyLen = derivedKeyLen.toULong()
                )
                
                require(status == kCCSuccess) { "PBKDF2 failed with status: $status" }
            }
        }
    }
    
    return derivedKey
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginAesEncrypt(
    mode: String,
    key: ByteArray,
    iv: ByteArray,
    data: ByteArray,
): ByteArray {
    requireValidAesKey(key)
    if (!mode.uppercase().contains("ECB")) {
        require(iv.isNotEmpty()) { "AES mode $mode requires an IV" }
    }

    val isGcm = mode.uppercase().contains("GCM")
    if (isGcm) {
        var encryptedData: ByteArray? = null
        memScoped {
            val cryptorRefVar = alloc<com.nuvio.app.features.plugins.cryptointerop.CCCryptorRefVar>()
            
            key.usePinned { pinnedKey ->
                iv.usePinned { pinnedIv ->
                    data.usePinned { pinnedData ->
                        val keyPtr = if (key.isNotEmpty()) pinnedKey.addressOf(0) else null
                        val ivPtr = if (iv.isNotEmpty()) pinnedIv.addressOf(0) else null
                        val dataPtr = if (data.isNotEmpty()) pinnedData.addressOf(0) else null
                        
                        val status = CCCryptorCreateWithMode(
                            op = kCCEncrypt,
                            mode = kCCModeGCM,
                            alg = kCCAlgorithmAES,
                            padding = ccNoPadding,
                            iv = ivPtr,
                            key = keyPtr,
                            keyLength = key.size.toULong(),
                            tweak = null,
                            tweakLength = 0UL,
                            numRounds = 0,
                            options = 0U,
                            cryptorRef = cryptorRefVar.ptr
                        )
                        
                        if (status != kCCSuccess) {
                            error("CCCryptorCreateWithMode failed with status: $status")
                        }
                        
                        val cryptorRef = cryptorRefVar.value ?: error("Cryptor reference was null")
                        
                        try {
                            val cipherTextBytes = ByteArray(data.size)
                            cipherTextBytes.usePinned { pinnedCipher ->
                                val cipherPtr = if (data.isNotEmpty()) pinnedCipher.addressOf(0) else null
                                val cryptStatus = CCCryptorGCMEncrypt(
                                    cryptorRef = cryptorRef,
                                    dataIn = dataPtr,
                                    dataInLength = data.size.toULong(),
                                    dataOut = cipherPtr
                                )
                                if (cryptStatus != kCCSuccess) {
                                    error("CCCryptorGCMEncrypt failed with status: $cryptStatus")
                                }
                            }
                            
                            val tagBytes = ByteArray(16)
                            val tagLengthVar = alloc<platform.posix.size_tVar>()
                            tagLengthVar.value = 16UL
                            
                            tagBytes.usePinned { pinnedTag ->
                                val tagPtr = pinnedTag.addressOf(0)
                                val finalStatus = CCCryptorGCMFinal(
                                    cryptorRef = cryptorRef,
                                    tag = tagPtr,
                                    tagLength = tagLengthVar.ptr
                                )
                                if (finalStatus != kCCSuccess) {
                                    error("CCCryptorGCMFinal failed with status: $finalStatus")
                                }
                            }
                            
                            encryptedData = cipherTextBytes + tagBytes
                        } finally {
                            CCCryptorRelease(cryptorRef)
                        }
                    }
                }
            }
        }
        return encryptedData ?: ByteArray(0)
    }
    
    val isEcb = mode.uppercase().contains("ECB")
    val isNoPadding = mode.uppercase().contains("NOPADDING")

    val dataOutAvailable = data.size + 16 // AES block size
    val dataOut = ByteArray(dataOutAvailable)
    
    var finalData: ByteArray? = null
    
    memScoped {
        val dataOutMoved = alloc<platform.posix.size_tVar>()
        
        var options = 0U
        if (isEcb) {
            options = options or kCCOptionECBMode
        }
        if (!isNoPadding) {
            options = options or kCCOptionPKCS7Padding
        }

        key.usePinned { pinnedKey ->
            iv.usePinned { pinnedIv ->
                data.usePinned { pinnedData ->
                    dataOut.usePinned { pinnedDataOut ->
                        val status = CCCrypt(
                            op = kCCEncrypt,
                            alg = kCCAlgorithmAES,
                            options = options,
                            key = if (key.isNotEmpty()) pinnedKey.addressOf(0) else null,
                            keyLength = key.size.toULong(),
                            iv = if (!isEcb && iv.isNotEmpty()) pinnedIv.addressOf(0) else null,
                            dataIn = if (data.isNotEmpty()) pinnedData.addressOf(0) else null,
                            dataInLength = data.size.toULong(),
                            dataOut = pinnedDataOut.addressOf(0),
                            dataOutAvailable = dataOutAvailable.toULong(),
                            dataOutMoved = dataOutMoved.ptr
                        )
                        
                        if (status == kCCSuccess) {
                            finalData = dataOut.copyOf(dataOutMoved.value.toInt())
                        } else {
                            error("CCCrypt Encrypt failed with status: $status")
                        }
                    }
                }
            }
        }
    }
    
    return finalData ?: ByteArray(0)
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginAesDecrypt(
    mode: String,
    key: ByteArray,
    iv: ByteArray,
    data: ByteArray,
): ByteArray {
    requireValidAesKey(key)
    if (!mode.uppercase().contains("ECB")) {
        require(iv.isNotEmpty()) { "AES mode $mode requires an IV" }
    }

    val isGcm = mode.uppercase().contains("GCM")
    if (isGcm) {
        require(data.size >= 16) { "Data too short for GCM decryption" }
        val ciphertextLen = data.size - 16
        val ciphertext = data.copyOfRange(0, ciphertextLen)
        val tagBytes = data.copyOfRange(ciphertextLen, data.size)
        
        var decryptedData: ByteArray? = null
        
        memScoped {
            val cryptorRefVar = alloc<com.nuvio.app.features.plugins.cryptointerop.CCCryptorRefVar>()
            
            key.usePinned { pinnedKey ->
                iv.usePinned { pinnedIv ->
                    ciphertext.usePinned { pinnedCipher ->
                        tagBytes.usePinned { pinnedTag ->
                            val keyPtr = if (key.isNotEmpty()) pinnedKey.addressOf(0) else null
                            val ivPtr = if (iv.isNotEmpty()) pinnedIv.addressOf(0) else null
                            val cipherPtr = if (ciphertext.isNotEmpty()) pinnedCipher.addressOf(0) else null
                            val tagPtr = pinnedTag.addressOf(0)
                            
                            val status = CCCryptorCreateWithMode(
                                op = kCCDecrypt,
                                mode = kCCModeGCM,
                                alg = kCCAlgorithmAES,
                                padding = ccNoPadding,
                                iv = ivPtr,
                                key = keyPtr,
                                keyLength = key.size.toULong(),
                                tweak = null,
                                tweakLength = 0UL,
                                numRounds = 0,
                                options = 0U,
                                cryptorRef = cryptorRefVar.ptr
                            )
                            
                            if (status != kCCSuccess) {
                                error("CCCryptorCreateWithMode failed with status: $status")
                            }
                            
                            val cryptorRef = cryptorRefVar.value ?: error("Cryptor reference was null")
                            
                            try {
                                val plainTextBytes = ByteArray(ciphertextLen)
                                plainTextBytes.usePinned { pinnedPlain ->
                                    val plainPtr = if (ciphertextLen > 0) pinnedPlain.addressOf(0) else null
                                    val cryptStatus = CCCryptorGCMDecrypt(
                                        cryptorRef = cryptorRef,
                                        dataIn = cipherPtr,
                                        dataInLength = ciphertextLen.toULong(),
                                        dataOut = plainPtr
                                    )
                                    if (cryptStatus != kCCSuccess) {
                                        error("CCCryptorGCMDecrypt failed with status: $cryptStatus")
                                    }
                                }
                                
                                val tagLengthVar = alloc<platform.posix.size_tVar>()
                                tagLengthVar.value = 16UL
                                
                                val finalStatus = CCCryptorGCMFinal(
                                    cryptorRef = cryptorRef,
                                    tag = tagPtr,
                                    tagLength = tagLengthVar.ptr
                                )
                                if (finalStatus != kCCSuccess) {
                                    error("CCCryptorGCMFinal failed with status: $finalStatus (tag verification failed)")
                                }
                                
                                decryptedData = plainTextBytes
                            } finally {
                                CCCryptorRelease(cryptorRef)
                            }
                        }
                    }
                }
            }
        }
        return decryptedData ?: ByteArray(0)
    }
    
    val isEcb = mode.uppercase().contains("ECB")
    val isNoPadding = mode.uppercase().contains("NOPADDING")

    val dataOutAvailable = data.size + 16 // AES block size
    val dataOut = ByteArray(dataOutAvailable)
    
    var finalData: ByteArray? = null
    
    memScoped {
        val dataOutMoved = alloc<platform.posix.size_tVar>()
        
        var options = 0U
        if (isEcb) {
            options = options or kCCOptionECBMode
        }
        if (!isNoPadding) {
            options = options or kCCOptionPKCS7Padding
        }

        key.usePinned { pinnedKey ->
            iv.usePinned { pinnedIv ->
                data.usePinned { pinnedData ->
                    dataOut.usePinned { pinnedDataOut ->
                        val status = CCCrypt(
                            op = kCCDecrypt,
                            alg = kCCAlgorithmAES,
                            options = options,
                            key = if (key.isNotEmpty()) pinnedKey.addressOf(0) else null,
                            keyLength = key.size.toULong(),
                            iv = if (!isEcb && iv.isNotEmpty()) pinnedIv.addressOf(0) else null,
                            dataIn = if (data.isNotEmpty()) pinnedData.addressOf(0) else null,
                            dataInLength = data.size.toULong(),
                            dataOut = pinnedDataOut.addressOf(0),
                            dataOutAvailable = dataOutAvailable.toULong(),
                            dataOutMoved = dataOutMoved.ptr
                        )
                        
                        if (status == kCCSuccess) {
                            finalData = dataOut.copyOf(dataOutMoved.value.toInt())
                        } else {
                            error("CCCrypt failed with status: $status")
                        }
                    }
                }
            }
        }
    }
    
    return finalData ?: ByteArray(0)
}

internal fun pluginSign(algorithm: String, privateKey: ByteArray, data: ByteArray): ByteArray {
    throw UnsupportedOperationException("Asymmetric signing is currently implemented natively only on Android")
}

internal fun pluginVerify(algorithm: String, publicKey: ByteArray, signature: ByteArray, data: ByteArray): Boolean {
    throw UnsupportedOperationException("Asymmetric verification is currently implemented natively only on Android")
}

private fun UByteArray.toHex(): String = joinToString(separator = "") { byte ->
    byte.toString(16).padStart(2, '0')
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginDigestHex(algorithm: String, data: String): String {
    val normalized = normalizeDigestAlgorithm(algorithm)
    val input = data.encodeToByteArray()
    val output = UByteArray(
        when (normalized) {
            "MD5" -> CC_MD5_DIGEST_LENGTH.toInt()
            "SHA1" -> CC_SHA1_DIGEST_LENGTH.toInt()
            "SHA256" -> CC_SHA256_DIGEST_LENGTH.toInt()
            "SHA384" -> CC_SHA384_DIGEST_LENGTH.toInt()
            "SHA512" -> CC_SHA512_DIGEST_LENGTH.toInt()
            else -> error("Unsupported digest algorithm: $algorithm")
        },
    )

    input.usePinned { pinnedInput ->
        output.usePinned { pinnedOutput ->
            val dataPtr = if (input.isNotEmpty()) pinnedInput.addressOf(0) else null
            val outputPtr = pinnedOutput.addressOf(0)

            when (normalized) {
                "MD5" -> CC_MD5(dataPtr, input.size.toUInt(), outputPtr)
                "SHA1" -> CC_SHA1(dataPtr, input.size.toUInt(), outputPtr)
                "SHA256" -> CC_SHA256(dataPtr, input.size.toUInt(), outputPtr)
                "SHA384" -> CC_SHA384(dataPtr, input.size.toUInt(), outputPtr)
                "SHA512" -> CC_SHA512(dataPtr, input.size.toUInt(), outputPtr)
            }
        }
    }

    return output.toHex()
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginHmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
    val (alg, outputSize) = normalizeHmacAlgorithm(algorithm)
    val output = ByteArray(outputSize)

    key.usePinned { pinnedKey ->
        data.usePinned { pinnedInput ->
            output.usePinned { pinnedOutput ->
                val keyPtr = if (key.isNotEmpty()) pinnedKey.addressOf(0) else null
                val inputPtr = if (data.isNotEmpty()) pinnedInput.addressOf(0) else null

                CCHmac(
                    alg,
                    keyPtr,
                    key.size.toULong(),
                    inputPtr,
                    data.size.toULong(),
                    pinnedOutput.addressOf(0).reinterpret<UByteVar>(),
                )
            }
        }
    }

    return output
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginHmacHex(algorithm: String, key: String, data: String): String {
    return pluginHmac(algorithm, key.encodeToByteArray(), data.encodeToByteArray()).toHex()
}

private fun normalizeDigestAlgorithm(algorithm: String): String {
    return when (algorithm.normalizedAlgorithmToken()) {
        "MD5" -> "MD5"
        "SHA1" -> "SHA1"
        "SHA256" -> "SHA256"
        "SHA384" -> "SHA384"
        "SHA512" -> "SHA512"
        else -> error("Unsupported digest algorithm: $algorithm")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun normalizePbkdf2Prf(algorithm: String) =
    when (algorithm.normalizedAlgorithmToken().removePrefix("HMAC")) {
        "SHA1" -> kCCPRFHmacAlgSHA1
        "SHA256" -> kCCPRFHmacAlgSHA256
        "SHA384" -> kCCPRFHmacAlgSHA384
        "SHA512" -> kCCPRFHmacAlgSHA512
        else -> error("Unsupported PBKDF2 hash algorithm: $algorithm")
    }

@OptIn(ExperimentalForeignApi::class)
private fun normalizeHmacAlgorithm(algorithm: String) =
    when (algorithm.normalizedAlgorithmToken().removePrefix("HMAC")) {
        "MD5" -> kCCHmacAlgMD5 to CC_MD5_DIGEST_LENGTH.toInt()
        "SHA1" -> kCCHmacAlgSHA1 to CC_SHA1_DIGEST_LENGTH.toInt()
        "SHA256" -> kCCHmacAlgSHA256 to CC_SHA256_DIGEST_LENGTH.toInt()
        "SHA384" -> kCCHmacAlgSHA384 to CC_SHA384_DIGEST_LENGTH.toInt()
        "SHA512" -> kCCHmacAlgSHA512 to CC_SHA512_DIGEST_LENGTH.toInt()
        else -> error("Unsupported HMAC algorithm: $algorithm")
    }

private fun requireValidAesKey(key: ByteArray) {
    require(key.size == 16 || key.size == 24 || key.size == 32) {
        "AES key must be 16, 24, or 32 bytes"
    }
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }

private fun String.normalizedAlgorithmToken(): String =
    uppercase()
        .replace("-", "")
        .replace("_", "")
        .replace("/", "")
        .replace(" ", "")

@OptIn(ExperimentalEncodingApi::class)
internal fun pluginBase64Encode(data: String): String =
    Base64.encode(data.encodeToByteArray())

@OptIn(ExperimentalEncodingApi::class)
internal fun pluginBase64Decode(data: String): String {
    var normalized = data.trim().replace("\n", "").replace("\r", "").replace(" ", "")
    normalized = normalized.replace("-", "+").replace("_", "/")
    val padNeeded = (4 - (normalized.length % 4)) % 4
    if (padNeeded > 0) {
        normalized += "=".repeat(padNeeded)
    }
    val decoded = Base64.decode(normalized)
    return decoded.decodeToString()
}

internal fun pluginUtf8ToHex(value: String): String =
    value.encodeToByteArray().joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }

internal fun pluginHexToByteArray(hex: String): ByteArray {
    val normalized = hex.trim().lowercase()
        .replace(" ", "")
        .removePrefix("0x")
    if (normalized.isEmpty()) return ByteArray(0)

    val evenHex = if (normalized.length % 2 == 0) normalized else "0$normalized"
    val out = ByteArray(evenHex.length / 2)
    for (index in out.indices) {
        val part = evenHex.substring(index * 2, index * 2 + 2)
        out[index] = part.toInt(16).toByte()
    }
    return out
}

internal fun pluginHexToUtf8(hex: String): String {
    return pluginHexToByteArray(hex).decodeToString()
}
