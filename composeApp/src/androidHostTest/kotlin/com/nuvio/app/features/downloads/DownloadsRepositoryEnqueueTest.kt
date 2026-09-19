package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadsRepositoryEnqueueTest {
    @Test
    fun enqueueWithoutLocationReturnsMissingLocation() {
        val context = RuntimeEnvironment.getApplication()
        DownloadsStorage.initialize(context)
        DownloadLocationManager.initialize(context)
        DownloadsRepository.clearLocalState()

        val result = DownloadsRepository.enqueueFromStream(
            contentType = "movie",
            videoId = "tt1",
            parentMetaId = "tt1",
            parentMetaType = "movie",
            title = "Test movie",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            stream = StreamItem(
                url = "https://example.com/video.mkv",
                addonName = "Test",
                addonId = "test",
            ),
        )

        assertEquals(DownloadEnqueueResult.MissingLocation, result)
    }
}
