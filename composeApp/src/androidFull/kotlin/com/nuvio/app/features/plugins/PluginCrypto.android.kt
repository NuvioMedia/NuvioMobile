package com.nuvio.app.features.plugins

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private val secureRandom = SecureRandom()

internal fun pluginGetRandomValues(length: Int): ByteArray {
    require(length >= 0) { "Random byte length must be non-negative" }
    val bytes = ByteArray(length)
    secureRandom.nextBytes(bytes)
    return bytes
}

internal fun pluginDigest(algorithm: String, data: ByteArray): ByteArray {
    return MessageDigest.getInstance(normalizeDigestAlgorithm(algorithm)).digest(data)
}

/**
 * ABDX-style Proof-of-Work solver: find the smallest nonce >= 0 such that
 * SHA-256(challenge + ":" + nonce) has at least [difficulty] leading zero
 * bits (MSB-first, byte by byte). Runs fully native (single MessageDigest
 * instance reused) so ~2^18 attempts complete in a couple of seconds instead
 * of tens of seconds when driven per-hash from JS.
 * @return nonce as decimal string
 */
internal fun pluginPowFindNonce(challenge: String, difficulty: Int): String {
    val target = if (difficulty > 0) difficulty else 18
    val md = MessageDigest.getInstance("SHA-256")
    val prefix = challenge.encodeToByteArray() // ASCII hex challenge
    // message buffer: challenge + ':' + up to 10 decimal digits
    val msg = ByteArray(prefix.size + 1 + 10)
    prefix.copyInto(msg, 0)
    msg[prefix.size] = ':'.code.toByte()
    var nonce = 0L
    while (true) {
        // render nonce decimal right-aligned at the end of msg then copy in order
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
            // digits were written backwards at msg[msg.size-len .. msg.size)
            // shift them left to sit right after the ':' when len < 10
            if (len < 10) {
                val dst = prefix.size + 1
                val src = msg.size - len
                for (i in 0 until len) msg[dst + i] = msg[src + i]
            }
        }
        val total = prefix.size + 1 + len
        md.reset()
        md.update(msg, 0, total)
        val digest = md.digest()
        if (leadingZeroBits(digest) >= target) return nonce.toString()
        nonce++
        if (nonce > (1L shl 24)) throw IllegalArgumentException("abdx pow cap exceeded")
    }
}

private fun leadingZeroBits(digest: ByteArray): Int {
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

internal fun pluginPbkdf2(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    keySizeBits: Int,
    algorithm: String,
): ByteArray {
    require(iterations > 0) { "PBKDF2 iterations must be positive" }
    require(keySizeBits > 0 && keySizeBits % 8 == 0) { "PBKDF2 key size must be a positive byte-aligned bit length" }

    val prfAlgo = normalizeHmacAlgorithm(algorithm)
    val mac = Mac.getInstance(prfAlgo)
    mac.init(SecretKeySpec(password, prfAlgo))
    
    val hLen = mac.macLength
    val dkLen = keySizeBits / 8
    val dk = ByteArray(dkLen)
    
    val blocks = (dkLen + hLen - 1) / hLen
    val u = ByteArray(hLen)
    val t = ByteArray(hLen)
    
    val blockIndexBytes = ByteArray(4)
    
    for (i in 1..blocks) {
        mac.reset()
        mac.update(salt)
        blockIndexBytes[0] = (i ushr 24).toByte()
        blockIndexBytes[1] = (i ushr 16).toByte()
        blockIndexBytes[2] = (i ushr 8).toByte()
        blockIndexBytes[3] = i.toByte()
        mac.update(blockIndexBytes)
        
        val u1 = mac.doFinal()
        u1.copyInto(t)
        u1.copyInto(u)
        
        for (j in 2..iterations) {
            mac.reset()
            val uj = mac.doFinal(u)
            uj.copyInto(u)
            for (k in 0 until hLen) {
                t[k] = (t[k].toInt() xor uj[k].toInt()).toByte()
            }
        }
        
        val offset = (i - 1) * hLen
        val len = minOf(hLen, dkLen - offset)
        t.copyInto(dk, destinationOffset = offset, startIndex = 0, endIndex = len)
    }
    
    return dk
}

internal fun pluginAesEncrypt(
    mode: String,
    key: ByteArray,
    iv: ByteArray,
    data: ByteArray,
): ByteArray {
    val normalizedMode = normalizeAesTransformation(mode)
    requireValidAesKey(key)
    if (!normalizedMode.contains("ECB")) {
        require(iv.isNotEmpty()) { "AES mode $mode requires an IV" }
    }
    val cipher = Cipher.getInstance(normalizedMode)
    val keySpec = SecretKeySpec(key, "AES")
    
    if (normalizedMode.contains("ECB")) {
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
    } else if (normalizedMode.contains("GCM")) {
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
    } else {
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
    }

    return cipher.doFinal(data)
}

internal fun pluginAesDecrypt(
    mode: String,
    key: ByteArray,
    iv: ByteArray,
    data: ByteArray,
): ByteArray {
    val normalizedMode = normalizeAesTransformation(mode)
    requireValidAesKey(key)
    if (!normalizedMode.contains("ECB")) {
        require(iv.isNotEmpty()) { "AES mode $mode requires an IV" }
    }
    val cipher = Cipher.getInstance(normalizedMode)
    val keySpec = SecretKeySpec(key, "AES")
    
    if (normalizedMode.contains("ECB")) {
        cipher.init(Cipher.DECRYPT_MODE, keySpec)
    } else if (normalizedMode.contains("GCM")) {
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
    } else {
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
    }

    return cipher.doFinal(data)
}

internal fun pluginSign(algorithm: String, privateKey: ByteArray, data: ByteArray): ByteArray {
    val (keyAlgo, sigAlgo) = when (algorithm.uppercase()) {
        "RSASSA-PKCS1-V1_5-SHA256", "RSASSA-PKCS1-V1_5" -> "RSA" to "SHA256withRSA"
        "ECDSA-SHA256", "ECDSA" -> "EC" to "SHA256withECDSA"
        else -> "RSA" to "SHA256withRSA"
    }
    val factory = KeyFactory.getInstance(keyAlgo)
    val privKey = factory.generatePrivate(PKCS8EncodedKeySpec(privateKey))
    val sig = Signature.getInstance(sigAlgo)
    sig.initSign(privKey)
    sig.update(data)
    return sig.sign()
}

internal fun pluginVerify(algorithm: String, publicKey: ByteArray, signature: ByteArray, data: ByteArray): Boolean {
    val (keyAlgo, sigAlgo) = when (algorithm.uppercase()) {
        "RSASSA-PKCS1-V1_5-SHA256", "RSASSA-PKCS1-V1_5" -> "RSA" to "SHA256withRSA"
        "ECDSA-SHA256", "ECDSA" -> "EC" to "SHA256withECDSA"
        else -> "RSA" to "SHA256withRSA"
    }
    val factory = KeyFactory.getInstance(keyAlgo)
    val pubKey = factory.generatePublic(X509EncodedKeySpec(publicKey))
    val sig = Signature.getInstance(sigAlgo)
    sig.initVerify(pubKey)
    sig.update(data)
    return sig.verify(signature)
}

internal fun pluginDigestHex(algorithm: String, data: String): String {
    val digest = pluginDigest(algorithm, data.encodeToByteArray())
    return digest.joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }
}

internal fun pluginHmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
    val normalized = normalizeHmacAlgorithm(algorithm)
    val mac = Mac.getInstance(normalized)
    mac.init(SecretKeySpec(key, normalized))
    return mac.doFinal(data)
}

internal fun pluginHmacHex(algorithm: String, key: String, data: String): String {
    val digest = pluginHmac(algorithm, key.encodeToByteArray(), data.encodeToByteArray())
    return digest.joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }
}

private fun normalizeDigestAlgorithm(algorithm: String): String {
    return when (algorithm.normalizedAlgorithmToken()) {
        "MD5" -> "MD5"
        "SHA1" -> "SHA-1"
        "SHA256" -> "SHA-256"
        "SHA384" -> "SHA-384"
        "SHA512" -> "SHA-512"
        else -> error("Unsupported digest algorithm: $algorithm")
    }
}

private fun normalizeHmacAlgorithm(algorithm: String): String {
    return when (algorithm.normalizedAlgorithmToken().removePrefix("HMAC")) {
        "MD5" -> "HmacMD5"
        "SHA1" -> "HmacSHA1"
        "SHA256" -> "HmacSHA256"
        "SHA384" -> "HmacSHA384"
        "SHA512" -> "HmacSHA512"
        else -> error("Unsupported HMAC algorithm: $algorithm")
    }
}

private fun normalizeAesTransformation(mode: String): String {
    val normalized = mode.normalizedAlgorithmToken()
    val noPadding = normalized.contains("NOPADDING")
    val padding = if (noPadding) "NoPadding" else "PKCS5Padding"
    return when {
        normalized.contains("GCM") -> "AES/GCM/NoPadding"
        normalized.contains("ECB") -> "AES/ECB/$padding"
        normalized.contains("CBC") -> "AES/CBC/$padding"
        else -> "AES/CBC/$padding"
    }
}

private fun requireValidAesKey(key: ByteArray) {
    require(key.size == 16 || key.size == 24 || key.size == 32) {
        "AES key must be 16, 24, or 32 bytes"
    }
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
    // Robust URL-safe base64 decoding fallback
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
