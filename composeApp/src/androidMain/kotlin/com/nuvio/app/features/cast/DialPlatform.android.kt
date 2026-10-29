package com.nuvio.app.features.cast

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

internal actual object DialPlatform {
    private const val TAG = "DialPlatform"
    private var appContext: Context? = null
    fun initialize(ctx: Context) { appContext = ctx.applicationContext }

    actual suspend fun scanDevices(timeoutMs: Int): List<UnifiedCastDevice> = withContext(Dispatchers.IO) {
        // DIAL uses same SSDP but ST: urn:dial-multiscreen-org:service:dial:1
        // We do lightweight scan - parse LOCATION and Application-URL
        val locations = mutableSetOf<String>()
        var socket: MulticastSocket? = null
        try {
            socket = MulticastSocket(null).apply {
                reuseAddress = true
                soTimeout = timeoutMs
                timeToLive = 2
            }
            socket.bind(java.net.InetSocketAddress(0))
            val group = InetAddress.getByName("239.255.255.250")
            val msg = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: 239.255.255.250:1900\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 3\r\n")
                append("ST: urn:dial-multiscreen-org:service:dial:1\r\n")
                append("\r\n")
            }
            val bytes = msg.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(bytes, bytes.size, group, 1900))
            Log.d(TAG, "Sent DIAL M-SEARCH")
            val buf = ByteArray(8192)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val remaining = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(100)
                socket.soTimeout = remaining.coerceAtMost(1000)
                val p = DatagramPacket(buf, buf.size)
                try { socket.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
                val resp = String(p.data, 0, p.length, Charsets.UTF_8)
                val loc = Regex("(?i)LOCATION:\\s*(\\S+)").find(resp)?.groupValues?.getOrNull(1)?.trim()
                val appUrl = Regex("(?i)Application-URL:\\s*(\\S+)").find(resp)?.groupValues?.getOrNull(1)?.trim()
                if (!loc.isNullOrBlank() && appUrl != null) {
                    val key = "$loc|$appUrl"
                    if (locations.add(key)) Log.i(TAG, "DIAL found $loc app=$appUrl")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DIAL scan failed: ${e.message}")
        } finally { try { socket?.close() } catch (_: Exception) {} }

        // DIAL application launch requires an installed receiver app on the target device.
        // Returning un-launchable entries degrades UX; keep discovery ready for when a receiver exists.
        emptyList()
    }
}
