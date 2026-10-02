package com.nuvio.app.features.downloads

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadLocationModelsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun locationPrefRoundTripsThroughSerialization() {
        val prefs = listOf(
            DownloadLocationPref(
                mode = DownloadLocationMode.ANDROID_SAF,
                value = "content://com.android.externalstorage.documents/tree/primary%3AMovies",
            ),
            DownloadLocationPref(
                mode = DownloadLocationMode.IOS_FOLDER,
                value = "/var/mobile/Documents/nuvio_downloads",
            ),
        )

        prefs.forEach { pref ->
            assertEquals(pref, json.decodeFromString<DownloadLocationPref>(json.encodeToString(pref)))
        }
    }

    @Test
    fun locationModeSerializesByName() {
        assertEquals("\"ANDROID_SAF\"", json.encodeToString(DownloadLocationMode.ANDROID_SAF))
        assertEquals("\"IOS_FOLDER\"", json.encodeToString(DownloadLocationMode.IOS_FOLDER))
    }

    @Test
    fun mimeTypeMapsCommonVideoExtensions() {
        assertEquals("video/mp4", mimeTypeForFileName("movie.mp4"))
        assertEquals("video/mp4", mimeTypeForFileName("movie.MP4"))
        assertEquals("video/mp4", mimeTypeForFileName("movie.m4v"))
        assertEquals("video/x-matroska", mimeTypeForFileName("Show S01E01.mkv"))
        assertEquals("video/webm", mimeTypeForFileName("clip.webm"))
        assertEquals("video/x-msvideo", mimeTypeForFileName("clip.avi"))
        assertEquals("video/quicktime", mimeTypeForFileName("clip.mov"))
        assertEquals("video/mp2t", mimeTypeForFileName("clip.ts"))
        assertEquals("video/x-flv", mimeTypeForFileName("clip.flv"))
        assertEquals("video/x-ms-wmv", mimeTypeForFileName("clip.wmv"))
        assertEquals("video/mpeg", mimeTypeForFileName("clip.mpeg"))
        assertEquals("video/3gpp", mimeTypeForFileName("clip.3gp"))
        assertEquals("video/ogg", mimeTypeForFileName("clip.ogv"))
    }

    @Test
    fun mimeTypeFallsBackForUnknownOrMissingExtensions() {
        assertEquals("application/octet-stream", mimeTypeForFileName("archive.zip"))
        assertEquals("application/octet-stream", mimeTypeForFileName("no-extension"))
        assertEquals("application/octet-stream", mimeTypeForFileName(""))
        assertEquals("video/mp4", mimeTypeForFileName("clip.mp4?token=abc"))
    }
}
