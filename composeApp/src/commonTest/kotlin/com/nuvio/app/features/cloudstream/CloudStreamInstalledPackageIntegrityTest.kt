package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudStreamInstalledPackageIntegrityTest {
    @Test
    fun validatesChecksumBelongingToInstalledPackageVersion() {
        val installedBytes = "installed-v1".encodeToByteArray()
        val installedHash = "sha256-${sha256Hex(installedBytes)}"

        assertTrue(matchesInstalledCloudStreamPackage(installedHash, installedBytes))
        assertFalse(matchesInstalledCloudStreamPackage(installedHash, "new-v2".encodeToByteArray()))
    }

    @Test
    fun rejectsMalformedStoredChecksum() {
        assertFalse(matchesInstalledCloudStreamPackage("sha256-not-a-digest", byteArrayOf(1, 2, 3)))
    }

    @Test
    fun permitsMissingRepositoryChecksumAfterArchiveInspection() {
        // The runtime separately validates the .cs3 ZIP structure before this check.
        assertTrue(matchesInstalledCloudStreamPackage(null, byteArrayOf(1, 2, 3)))
    }
}
