package com.nuvio.app.features.mdblist

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MdbListNetworkEngineTest {
    @Test
    fun queryValuesCannotChangeRequestDestination() {
        val request = MdbListHttpRequest(MdbListHttpMethod.GET, "/lists/user", mapOf("cursor" to "a&b=#雪"))
        val url = io.ktor.http.Url(mdbListRequestUrl(MdbListConfiguration("client", "test"), request))
        assertEquals("api.mdblist.com", url.host)
        assertEquals("a&b=#雪", url.parameters["cursor"])
        listOf("//other.test/path", "/path?secret", "/path#fragment", "/path\\other").forEach { path ->
            assertFailsWith<IllegalArgumentException> {
                mdbListRequestUrl(MdbListConfiguration("client", "test"), MdbListHttpRequest(MdbListHttpMethod.GET, path))
            }
        }
    }

    @Test
    fun responseLimitAppliesToDeclaredAndStreamedBodies() = runTest {
        assertEquals("雪", readMdbListResponseBody(ByteReadChannel("雪"), null, 3))
        expectMdbListFailure<IOException> { readMdbListResponseBody(ByteReadChannel(""), 4, 3) }
        expectMdbListFailure<IOException> { readMdbListResponseBody(ByteReadChannel("abcd"), null, 3) }
        assertEquals("", readMdbListResponseBody(ByteReadChannel(""), null, 3))
    }
}
