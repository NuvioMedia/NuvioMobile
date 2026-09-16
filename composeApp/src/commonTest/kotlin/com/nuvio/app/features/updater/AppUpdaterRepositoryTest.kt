package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppUpdaterRepositoryTest {
    @Test
    fun channelsUseTheSameReleaseEndpointsAsTv() {
        assertEquals("releases/latest", AppUpdaterRepository.releasePath(UpdateChannel.STABLE))
        assertEquals("releases?per_page=100", AppUpdaterRepository.releasePath(UpdateChannel.BETA))
    }

    @Test
    fun stableAcceptsReleasesFromAnyBranch() {
        listOf("main", "master", "cmp-rewrite", "1234567").forEach { branch ->
            val response = """{
                "tag_name": "v1.1.0", "target_commitish": "$branch",
                "assets": [{"name": "app.apk", "browser_download_url": "https://example.com/app.apk"}]
            }"""
            assertEquals("v1.1.0", select(response, UpdateChannel.STABLE)?.tag)
        }
    }

    @Test
    fun stableRejectsLegacyBetaNamesEvenIfGithubMarksThemStable() {
        val response = """{
            "tag_name": "1.1.0", "name": "Beta 1.1.0 Hotfix",
            "assets": [{"name": "app.apk", "browser_download_url": "https://example.com/app.apk"}]
        }"""
        assertNull(select(response, UpdateChannel.STABLE))
    }

    @Test
    fun betaSkipsReleasesWithoutAnApkAndSortsByVersion() {
        val response = """[
            {"tag_name": "1.1.0-beta.9", "assets": [
                {"name": "app.apk", "browser_download_url": "https://example.com/beta9.apk"}]},
            {"tag_name": "1.2.0", "assets": [
                {"name": "app.ipa", "browser_download_url": "https://example.com/app.ipa"}]},
            {"tag_name": "1.1.0-beta.10", "assets": [
                {"name": "app.apk", "browser_download_url": "https://example.com/beta10.apk"}]}
        ]"""
        assertEquals("1.1.0-beta.10", select(response, UpdateChannel.BETA)?.tag)
    }

    @Test
    fun betaReceivesFinalPromotion() {
        val response = """[
            {"tag_name": "1.1.0-beta.10", "prerelease": true, "assets": [
                {"name": "app.apk", "browser_download_url": "https://example.com/beta.apk"}]},
            {"tag_name": "1.1.0", "assets": [
                {"name": "app.apk", "browser_download_url": "https://example.com/stable.apk"}]}
        ]"""
        assertEquals("1.1.0", select(response, UpdateChannel.BETA)?.tag)
    }

    @Test
    fun apkSelectionPreservesDeviceAbiPreference() {
        val response = """{
            "tag_name": "1.1.0", "assets": [
                {"name": "app-universal.apk", "browser_download_url": "https://example.com/universal.apk"},
                {"name": "app-arm64-v8a.apk", "browser_download_url": "https://example.com/arm64.apk"},
                {"name": "app-x86_64.apk", "browser_download_url": "https://example.com/x86.apk"}
            ]
        }"""
        assertEquals("app-arm64-v8a.apk", select(response, UpdateChannel.STABLE)?.assetName)
        assertEquals(
            "app-universal.apk",
            AppUpdaterRepository.selectUpdate(response, UpdateChannel.STABLE, listOf("armeabi-v7a"))?.assetName,
        )
    }

    private fun select(response: String, channel: UpdateChannel): AppUpdate? =
        AppUpdaterRepository.selectUpdate(response, channel, listOf("arm64-v8a"))
}
