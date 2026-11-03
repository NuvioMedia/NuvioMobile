package com.nuvio.app.features.cloudstream

fun sha256Hex(input: ByteArray): String = sha256(input).joinToString("") { byte ->
    byte.toUByte().toString(16).padStart(2, '0')
}

private fun sha256(input: ByteArray): ByteArray {
    val initialHash = intArrayOf(
        0x6a09e667,
        0xbb67ae85.toInt(),
        0x3c6ef372,
        0xa54ff53a.toInt(),
        0x510e527f,
        0x9b05688c.toInt(),
        0x1f83d9ab,
        0x5be0cd19,
    )
    val constants = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

    val bitLength = input.size.toLong() * 8L
    val paddingSize = ((56 - (input.size + 1) % 64) + 64) % 64
    val padded = ByteArray(input.size + 1 + paddingSize + 8)
    input.copyInto(padded)
    padded[input.size] = 0x80.toByte()
    for (index in 0 until 8) {
        padded[padded.lastIndex - index] = (bitLength ushr (index * 8)).toByte()
    }

    val words = IntArray(64)
    for (chunkStart in padded.indices step 64) {
        for (index in 0 until 16) {
            val offset = chunkStart + index * 4
            words[index] =
                ((padded[offset].toInt() and 0xff) shl 24) or
                    ((padded[offset + 1].toInt() and 0xff) shl 16) or
                    ((padded[offset + 2].toInt() and 0xff) shl 8) or
                    (padded[offset + 3].toInt() and 0xff)
        }
        for (index in 16 until 64) {
            val s0 = words[index - 15].rotateRight(7) xor words[index - 15].rotateRight(18) xor (words[index - 15] ushr 3)
            val s1 = words[index - 2].rotateRight(17) xor words[index - 2].rotateRight(19) xor (words[index - 2] ushr 10)
            words[index] = words[index - 16] + s0 + words[index - 7] + s1
        }

        var a = initialHash[0]
        var b = initialHash[1]
        var c = initialHash[2]
        var d = initialHash[3]
        var e = initialHash[4]
        var f = initialHash[5]
        var g = initialHash[6]
        var h = initialHash[7]

        for (index in 0 until 64) {
            val sum1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val choose = (e and f) xor (e.inv() and g)
            val temporary1 = h + sum1 + choose + constants[index] + words[index]
            val sum0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val temporary2 = sum0 + majority
            h = g
            g = f
            f = e
            e = d + temporary1
            d = c
            c = b
            b = a
            a = temporary1 + temporary2
        }

        initialHash[0] += a
        initialHash[1] += b
        initialHash[2] += c
        initialHash[3] += d
        initialHash[4] += e
        initialHash[5] += f
        initialHash[6] += g
        initialHash[7] += h
    }

    return ByteArray(32).also { output ->
        initialHash.forEachIndexed { index, value ->
            output[index * 4] = (value ushr 24).toByte()
            output[index * 4 + 1] = (value ushr 16).toByte()
            output[index * 4 + 2] = (value ushr 8).toByte()
            output[index * 4 + 3] = value.toByte()
        }
    }
}

private fun Int.rotateRight(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))

