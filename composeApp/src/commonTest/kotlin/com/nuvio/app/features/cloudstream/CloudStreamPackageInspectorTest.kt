package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CloudStreamPackageInspectorTest {
    @Test
    fun inspectsSafeCs3CentralDirectory() {
        val archive = fakeZip("manifest.json", "classes.dex")
        val inspection = CloudStreamPackageInspector.inspect(archive)
        assertTrue(inspection.containsManifest)
        assertTrue(inspection.containsDex)
        assertEquals(2, inspection.entries.size)
    }

    @Test
    fun rejectsPathTraversal() {
        val archive = fakeZip("manifest.json", "classes.dex", "../escape.txt")
        assertFailsWith<IllegalArgumentException> {
            CloudStreamPackageInspector.inspect(archive)
        }
    }

    @Test
    fun rejectsCentralDirectoryThatOverlapsEndRecord() {
        val archive = fakeZip("manifest.json", "classes.dex")
        val endOffset = archive.size - 22
        val declaredDirectorySize = archive.readIntLeForTest(endOffset + 12)
        archive.writeIntLeForTest(endOffset + 12, declaredDirectorySize + 1)

        assertFailsWith<IllegalArgumentException> {
            CloudStreamPackageInspector.inspect(archive)
        }
    }

    @Test
    fun rejectsEntryThatRunsPastDeclaredCentralDirectory() {
        val archive = fakeZip("manifest.json", "classes.dex")
        val endOffset = archive.size - 22
        archive.writeIntLeForTest(endOffset + 12, 1)

        assertFailsWith<IllegalArgumentException> {
            CloudStreamPackageInspector.inspect(archive)
        }
    }

    @Test
    fun rejectsMultiDiskArchives() {
        val archive = fakeZip("manifest.json", "classes.dex")
        val endOffset = archive.size - 22
        archive[endOffset + 4] = 1

        assertFailsWith<IllegalArgumentException> {
            CloudStreamPackageInspector.inspect(archive)
        }
    }

    @Test
    fun rejectsTrailingBytesAfterZipEndRecord() {
        val archive = fakeZip("manifest.json", "classes.dex") + byteArrayOf(0x01)

        assertFailsWith<IllegalArgumentException> {
            CloudStreamPackageInspector.inspect(archive)
        }
    }

    @Test
    fun rejectsDuplicateEntryPaths() {
        val archive = fakeZip("manifest.json", "classes.dex", "manifest.json")

        assertFailsWith<IllegalArgumentException> {
            CloudStreamPackageInspector.inspect(archive)
        }
    }

    @Test
    fun rejectsEntryAssignedToAnotherDisk() {
        val archive = fakeZip("manifest.json", "classes.dex")
        val endOffset = archive.size - 22
        val centralDirectoryOffset = archive.readIntLeForTest(endOffset + 16)
        // Disk-start is at byte 34 of the first central-directory record.
        archive[centralDirectoryOffset + 34] = 1

        assertFailsWith<IllegalArgumentException> {
            CloudStreamPackageInspector.inspect(archive)
        }
    }

    @Test
    fun rejectsCentralEntryWithoutAValidLocalHeader() {
        val archive = fakeZip("manifest.json", "classes.dex")
        val endOffset = archive.size - 22
        val centralOffset = archive.readIntLeForTest(endOffset + 16)
        archive.writeIntLeForTest(centralOffset + 42, -1)

        assertFailsWith<IllegalArgumentException> {
            CloudStreamPackageInspector.inspect(archive)
        }
    }

    @Test
    fun rejectsDifferentLocalAndCentralEntryNames() {
        val archive = fakeZip("manifest.json", "classes.dex")
        // The first local file name starts after the 30-byte local ZIP header.
        archive[30] = 'x'.code.toByte()

        assertFailsWith<IllegalArgumentException> {
            CloudStreamPackageInspector.inspect(archive)
        }
    }

    @Test
    fun rejectsPackageWithoutDex() {
        assertFailsWith<IllegalArgumentException> {
            CloudStreamPackageInspector.inspect(fakeZip("manifest.json"))
        }
    }

    private fun fakeZip(vararg names: String): ByteArray {
        val localEntries = mutableListOf<ByteArray>()
        val centralEntries = mutableListOf<ByteArray>()
        names.forEach { name ->
            val nameBytes = name.encodeToByteArray()
            val localOffset = localEntries.sumOf { it.size }
            val local = byteArrayOf(0x50, 0x4b, 0x03, 0x04) +
                shortLe(20) + // version needed
                shortLe(0) + // flags
                shortLe(0) + // stored
                shortLe(0) + shortLe(0) + // time/date
                intLe(0) + intLe(0) + intLe(0) + // CRC, compressed size, uncompressed size
                shortLe(nameBytes.size) + shortLe(0) +
                nameBytes
            localEntries += local

            val central = byteArrayOf(0x50, 0x4b, 0x01, 0x02) +
                shortLe(20) + shortLe(20) +
                shortLe(0) + shortLe(0) +
                shortLe(0) + shortLe(0) +
                intLe(0) + intLe(0) + intLe(0) +
                shortLe(nameBytes.size) + shortLe(0) + shortLe(0) +
                shortLe(0) + shortLe(0) + intLe(0) +
                intLe(localOffset) +
                nameBytes
            centralEntries += central
        }

        val localData = localEntries.fold(ByteArray(0)) { acc, entry -> acc + entry }
        val central = centralEntries.fold(ByteArray(0)) { acc, entry -> acc + entry }
        val centralOffset = localData.size
        val end = byteArrayOf(0x50, 0x4b, 0x05, 0x06) +
            shortLe(0) + shortLe(0) +
            shortLe(names.size) + shortLe(names.size) +
            intLe(central.size) + intLe(centralOffset) + shortLe(0)
        return localData + central + end
    }

    private fun ByteArray.readIntLeForTest(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)

    private fun ByteArray.writeIntLeForTest(offset: Int, value: Int) {
        repeat(4) { index -> this[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun shortLe(value: Int): ByteArray = byteArrayOf(value.toByte(), (value ushr 8).toByte())

    private fun intLe(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )
}

