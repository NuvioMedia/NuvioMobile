package com.nuvio.app.features.downloads

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_enqueue_missing_location
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadEnqueueResultTest {
    @Test
    fun missingLocationResultMapsToItsMessage() {
        assertEquals(
            Res.string.downloads_enqueue_missing_location,
            DownloadEnqueueResult.MissingLocation.messageResource(),
        )
    }
}
