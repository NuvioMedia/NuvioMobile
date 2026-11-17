package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class VodCacheSizingTest {
    private val mb = 1024L * 1024L
    private val gb = 1024L * mb

    private fun autoBytes(freeGb: Long): Long {
        val free = freeGb * gb
        val runtimeMax = free - gb
        return VodCacheSizing.resolveAutoBytes(
            freeSpaceBytes = free,
            minBytes = 100L * mb,
            runtimeMaxBytes = runtimeMax,
        )
    }

    @Test
    fun lowStorageTakesTheFloorRatherThanAFifthOfFreeSpace() {
        assertEquals(2L * gb, autoBytes(6))
    }

    @Test
    fun theFreeSpaceCeilingStillWinsOverTheFloor() {
        assertEquals(1L * gb, autoBytes(2))
    }

    @Test
    fun ampleStorageKeepsOneFifthSizing() {
        assertEquals(4L * gb, autoBytes(20))
    }
}
