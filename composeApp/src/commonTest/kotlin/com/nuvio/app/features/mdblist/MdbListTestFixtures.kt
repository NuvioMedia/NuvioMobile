package com.nuvio.app.features.mdblist

import io.ktor.utils.io.errors.IOException

internal class MdbListTestPersistence : MdbListAuthPersistence {
    val profiles = mutableMapOf<Int, String>()
    var failWrites = false

    override fun read(profileId: Int): String? = profiles[profileId]
    override fun write(profileId: Int, value: String?) {
        if (failWrites) throw IOException("Storage unavailable")
        if (value == null) profiles.remove(profileId) else profiles[profileId] = value
    }
    override fun clear() = profiles.clear()
}

internal class MdbListTestEngine : MdbListHttpEngine {
    val requests = mutableListOf<MdbListHttpRequest>()
    val responses = ArrayDeque<MdbListHttpResponse>()
    var intercept: suspend (MdbListHttpRequest) -> Unit = {}

    override suspend fun execute(request: MdbListHttpRequest): MdbListHttpResponse {
        requests += request
        intercept(request)
        return responses.removeFirst()
    }
}

internal class MdbListTestHarness {
    var now = 1_700_000_000_000L
    val sleeps = mutableListOf<Long>()
    val persistence = MdbListTestPersistence()
    val store = MdbListAuthStore(persistence)
    val engine = MdbListTestEngine()
    val configuration = MdbListConfiguration("public-client", "test")
    val http = MdbListHttpClient(engine, { now }, { sleeps += it; now += it })
    val auth = MdbListAuthRepository(http, configuration, store) { now }
    val api = MdbListApiClient(http, auth, store)

    fun reply(status: Int = 200, body: String = "{}", headers: Map<String, String> = emptyMap()) {
        engine.responses += MdbListHttpResponse(status, body, headers)
    }

    fun connected(accessToken: String = "access-one", refreshToken: String = "refresh-one", expiresIn: Long = 120_000L) {
        store.authorize(MdbListTokens(accessToken, refreshToken, now + expiresIn), store.scope())
    }

    suspend fun pending(): MdbListDeviceSession {
        reply(body = DEVICE_RESPONSE)
        return auth.startDeviceAuthorization()
    }

    companion object {
        val DEVICE_RESPONSE = """{"device_code":"device-secret","user_code":"ABCD-EFGH","verification_uri":"https://mdblist.com/oauth/device/","verification_uri_complete":"https://mdblist.com/oauth/device/?user_code=ABCD-EFGH","expires_in":300,"interval":5}"""
        val TOKEN_RESPONSE = """{"access_token":"access-two","refresh_token":"refresh-two","token_type":"Bearer","expires_in":2592000,"scope":"write"}"""
    }
}

internal suspend inline fun <reified T : Throwable> expectMdbListFailure(block: suspend () -> Unit): T {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) return error
        throw AssertionError("Expected ${T::class.simpleName}, got ${error::class.simpleName}", error)
    }
    throw AssertionError("Expected ${T::class.simpleName}")
}
