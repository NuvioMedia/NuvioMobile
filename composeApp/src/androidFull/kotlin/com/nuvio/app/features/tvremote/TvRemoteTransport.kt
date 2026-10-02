package com.nuvio.app.features.tvremote

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal val remoteJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
internal data class PairedTv(val deviceId: String, val deviceName: String, val host: String, val port: Int, val pin: String, val credential: String = "")

internal data class PairingCode(val tv: PairedTv, val secret: String) {
    companion object {
        fun parse(value: String, now: Long = System.currentTimeMillis()): PairingCode {
            require(value.length in 1..2048) { "Invalid pairing code" }
            val uri = Uri.parse(value)
            require(uri.scheme == "nuvio-tv" && uri.host == "pair" && uri.getQueryParameter("v") == "1") { "Scan the code in TV Settings → Connect phone" }
            fun field(key: String) = requireNotNull(uri.getQueryParameter(key)) { "Incomplete pairing code" }
            val id = field("id").also { require(it.matches(Regex("[A-Za-z0-9-]{16,80}"))) }
            val host = field("host").also { require(isPrivateAddress(it)) { "TV must be on your home network" } }
            val port = field("port").toInt().also { require(it in 1..65535) }
            val pin = field("pin").lowercase().also { require(it.matches(Regex("[a-f0-9]{64}"))) }
            val secret = field("secret").also { require(it.matches(Regex("[A-Za-z0-9_-]{43}"))) }
            val expiry = field("expires").toLong()
            require(expiry > now && expiry <= now + 300_000) { "Pairing code expired. Generate a new code on the TV." }
            return PairingCode(PairedTv(id, field("name").take(100), host, port, pin), secret)
        }
    }
}

internal fun isPrivateAddress(host: String): Boolean {
    // Numeric addresses only: pairing must never turn into an arbitrary DNS/Internet request.
    if (!host.matches(Regex("[0-9.]+|[0-9a-fA-F:%]+"))) return false
    return runCatching { InetAddress.getByName(host).let { !it.isLoopbackAddress && (it.isSiteLocalAddress || it.isLinkLocalAddress) } }.getOrDefault(false)
}

internal class TvCredentialStore(context: Context) : PairStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "paired-tv.enc"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "nuvio.remote.credentials.v1"
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun read(): PairedTv? {
        if (!file.baseFile.exists()) return null
        return runCatching {
            val bytes = file.readFully()
            require(bytes.size in 29..8192)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
            remoteJson.decodeFromString<PairedTv>(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
        }.getOrElse { file.delete(); null }
    }
    override fun write(tv: PairedTv) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(remoteJson.encodeToString(tv).toByteArray())
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (error: Exception) { file.failWrite(output); throw error }
    }
    override fun clear() = file.delete()
}

internal class RemoteHttpException(val status: Int) : IOException("TV request failed ($status)")

internal class TvRemoteTransport(private val tv: PairedTv) : RemoteConnection {
    private val closed = AtomicBoolean(false)
    private val trust = PinnedTvTrust(tv.pin)
    private val client = OkHttpClient.Builder()
        .sslSocketFactory(SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }.socketFactory, trust)
        // The scanned certificate is the TV identity; IP addresses legitimately change via NSD.
        .hostnameVerifier { _, session -> runCatching { trust.checkServerTrusted(session.peerCertificates.map { it as X509Certificate }.toTypedArray(), ""); true }.getOrDefault(false) }
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(28, TimeUnit.SECONDS)
        .build()
    private val base = okhttp3.HttpUrl.Builder().scheme("https").host(tv.host).port(tv.port).build()

    override suspend fun pair(secret: String): PairResponse = remoteJson.decodeFromString(request("POST", "pair", remoteJson.encodeToString(PairRequest(tv.deviceId, secret)), authenticate = false))
    override suspend fun snapshot(after: Long?): RemoteSnapshot = remoteJson.decodeFromString(request("GET", "playback", after = after))
    override suspend fun command(command: RemoteCommand) { request("POST", "command", remoteJson.encodeToString(command)) }
    override suspend fun revoke() { request("DELETE", "pair") }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Conscrypt can write TLS close_notify while closing even an idle socket.
        // Reconnect/foreground and cancellation run on Main, so teardown must use IO.
        cleanupScope.launch {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
    }

    private suspend fun request(method: String, path: String, body: String? = null, after: Long? = null, authenticate: Boolean = true): String {
        check(!closed.get()) { "TV connection is closed" }
        val url = base.newBuilder().addPathSegment("v1").addPathSegment(path).apply { after?.let { addQueryParameter("after", it.toString()) } }.build()
        val request = Request.Builder().url(url).method(method, body?.toRequestBody("application/json".toMediaType()))
            .apply { if (authenticate) header("Authorization", "Bearer ${tv.credential}") }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { cleanupScope.launch { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) throw RemoteHttpException(it.code)
                            val bodySource = requireNotNull(it.body)
                            require(bodySource.contentLength() <= 65_536) { "TV response too large" }
                            val output = java.io.ByteArrayOutputStream()
                            bodySource.byteStream().use { input ->
                                val buffer = ByteArray(4096)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    require(output.size() + count <= 65_536) { "TV response too large" }
                                    output.write(buffer, 0, count)
                                }
                            }
                            output.toString("UTF-8")
                        }
                    }
                    if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
                }
            })
            // A concurrent close may have cancelled the dispatcher before this call was enqueued.
            if (closed.get()) cleanupScope.launch { call.cancel() }
        }
    }

    companion object {
        // Independent of the polling job: cancelling that job must not cancel its socket cleanup.
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

internal class TvCertificateMismatchException : CertificateException("TV certificate changed. Pair again from the TV.")

internal class PinnedTvTrust(private val pin: String) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("Client certificate unsupported")
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.firstOrNull() ?: throw CertificateException("Missing TV certificate")
        val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
        if (!MessageDigest.isEqual(actual.toByteArray(), pin.toByteArray())) throw TvCertificateMismatchException()
        certificate.checkValidity()
    }
}
