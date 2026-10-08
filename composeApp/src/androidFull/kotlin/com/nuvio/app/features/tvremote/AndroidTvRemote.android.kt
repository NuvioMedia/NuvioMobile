package com.nuvio.app.features.tvremote

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal object AndroidTvRemote {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var repository: TvRemoteRepository
        private set
    val initialized get() = ::repository.isInitialized
    private lateinit var media: TvRemoteMediaController
    private lateinit var discovery: TvRemoteDiscovery
    private val localOwners = mutableSetOf<Any>()
    val sheet = MutableStateFlow(false)
    var visible = false
        private set
    fun initialize(context: Context) {
        if (::repository.isInitialized) return
        repository = TvRemoteRepository(TvCredentialStore(context.applicationContext), scope)
        media = TvRemoteMediaController(context.applicationContext, repository, scope)
        media.localPlayback.value = localOwners.isNotEmpty()
        discovery = TvRemoteDiscovery(context.applicationContext, repository)
        scope.launch {
            repository.state.collect {
                discovery.setEnabled(repository.selected != null && !it.disconnected)
            }
        }
    }
    fun foreground(visible: Boolean) {
        this.visible = visible
        if (!::repository.isInitialized) return
        if (visible) { repository.reconnect(); discovery.refresh(); media.allowPromotion() }
    }
    fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("nuvio.tv.remote.OPEN", false) == true) {
            sheet.value = true
            intent.removeExtra("nuvio.tv.remote.OPEN")
        }
    }
    fun localPlayback(owner: Any, active: Boolean) {
        if (active) localOwners.add(owner) else localOwners.remove(owner)
        if (::media.isInitialized) media.localPlayback.value = localOwners.isNotEmpty()
    }
    fun userInteraction() { if (::media.isInitialized) media.allowPromotion() }
    fun restoreObservation() {
        if (!initialized) return
        repository.reconnect()
        media.allowPromotion()
    }
}

/** NSD provides address hints only. The repository retains the certificate pinned at pairing. */
internal class TvRemoteDiscovery(context: Context, private val repository: TvRemoteRepository) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var listener: NsdManager.DiscoveryListener? = null
    private var enabled = false
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var epoch = 0
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (value) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { main.post { if (enabled) { refresh(); repository.reconnect() } } }
                override fun onLost(network: Network) { main.post { if (enabled) { repository.reconnect(); refresh() } } }
            }
            networkCallback = callback
            runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            refresh()
        } else {
            networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
            networkCallback = null
            stop()
        }
    }
    private fun stop() {
        epoch++
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        pending.clear()
    }
    fun refresh() {
        stop()
        if (!enabled) return
        val currentEpoch = epoch
        val next = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { main.post { if (epoch == currentEpoch) stop() } }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceLost(service: NsdServiceInfo) { }
            override fun onServiceFound(service: NsdServiceInfo) { main.post {
                if (enabled && epoch == currentEpoch && pending.size < 16) { pending.add(service); resolveNext() }
            } }
        }
        listener = next
        runCatching { nsd.discoverServices("_nuvio-remote._tcp.", NsdManager.PROTOCOL_DNS_SD, next) }
    }
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || pending.isEmpty()) return
        val info = pending.removeFirst()
        val currentEpoch = epoch
        resolving = true
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, code: Int) { main.post { resolving = false; resolveNext() } }
            override fun onServiceResolved(service: NsdServiceInfo) { main.post {
                resolving = false
                if (enabled && epoch == currentEpoch) {
                    val id = service.attributes["id"]?.toString(Charsets.UTF_8)
                    val address = service.host?.hostAddress
                    if (id != null && address != null) repository.discovered(id, address, service.port)
                }
                resolveNext()
            } }
        }
        if (runCatching { nsd.resolveService(info, callback) }.isFailure) { resolving = false; resolveNext() }
    }
}
