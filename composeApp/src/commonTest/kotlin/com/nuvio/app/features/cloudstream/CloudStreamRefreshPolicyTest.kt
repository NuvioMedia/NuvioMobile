package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudStreamRefreshPolicyTest {
    @Test
    fun `never refreshed repositories are immediately eligible`() {
        assertTrue(shouldRefreshCloudStreamRepository(0L, 1_000L))
    }

    @Test
    fun `recently refreshed repositories are not refreshed on each app launch`() {
        assertFalse(
            shouldRefreshCloudStreamRepository(
                lastRefreshedAtEpochMs = 1_000L,
                nowEpochMs = 1_000L + CLOUD_STREAM_REPOSITORY_REFRESH_INTERVAL_MS - 1L,
            ),
        )
    }

    @Test
    fun `repository is eligible after twelve hours`() {
        assertTrue(
            shouldRefreshCloudStreamRepository(
                lastRefreshedAtEpochMs = 1_000L,
                nowEpochMs = 1_000L + CLOUD_STREAM_REPOSITORY_REFRESH_INTERVAL_MS,
            ),
        )
    }

    @Test
    fun `clock rollback makes refresh eligible`() {
        assertTrue(shouldRefreshCloudStreamRepository(2_000L, 1_000L))
    }
}
