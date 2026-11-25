package com.nuvio.app.features.audiosync

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Iterative in-place radix-2 complex FFT with precomputed twiddles and bit-reversal table. */
internal class Fft(val size: Int) {
    private val cosTable: DoubleArray
    private val sinTable: DoubleArray
    private val bitReversed: IntArray

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two: $size" }
        cosTable = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
        sinTable = DoubleArray(size / 2) { sin(2.0 * PI * it / size) }
        val bits = size.countTrailingZeroBits()
        bitReversed = IntArray(size) { index ->
            var reversed = 0
            var value = index
            repeat(bits) {
                reversed = (reversed shl 1) or (value and 1)
                value = value ushr 1
            }
            reversed
        }
    }

    /** Forward transform (e^{-i...}) when [inverse] is false. The inverse is unnormalized. */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        for (i in 0 until size) {
            val j = bitReversed[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        val sign = if (inverse) 1.0 else -1.0
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var start = 0
            while (start < size) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = sign * sinTable[k]
                    val l = j + half
                    val xr = re[l] * wr - im[l] * wi
                    val xi = re[l] * wi + im[l] * wr
                    re[l] = re[j] - xr
                    im[l] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                start += half * 2
            }
            half *= 2
        }
    }

    companion object {
        private const val CACHED_SIZES = 4

        // The tables never change, so transforms of one size share them instead of rebuilding
        // 1.5 MB of them on every alignment pass (every few seconds while searching).
        private val cache = LinkedHashMap<Int, Fft>()
        private val cacheLock = SynchronizedObject()

        /** A shared instance for [size]; [transform] only reads the tables, so it is thread-safe. */
        fun of(size: Int): Fft = synchronized(cacheLock) {
            val fft = cache.remove(size) ?: Fft(size)
            cache[size] = fft
            if (cache.size > CACHED_SIZES) cache.remove(cache.keys.first())
            fft
        }

        fun sizeFor(minimum: Int): Int {
            var n = 2
            while (n < minimum) n = n shl 1
            return n
        }
    }
}
