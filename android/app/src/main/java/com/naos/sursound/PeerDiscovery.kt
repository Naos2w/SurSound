package com.naos.sursound

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.util.UUID

/** Opt-in discovery of active receivers; addresses are untrusted until explicitly paired. */
@Suppress("DEPRECATION")
internal class PeerDiscovery(context: Context, private val onPeers: (List<String>) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val main = Handler(Looper.getMainLooper())
    private var browser: Browser? = null
    private var advertiser: Advertiser? = null

    fun browse() {
        stopBrowsing()
        val b = Browser()
        browser = b
        b.start()
    }

    fun stopBrowsing() {
        browser?.close()
        browser = null
        onPeers(emptyList())
    }

    fun advertise() {
        if (advertiser != null) return
        val a = Advertiser()
        advertiser = a
        a.start()
    }

    fun stopAdvertising() {
        advertiser?.close()
        advertiser = null
    }

    fun close() {
        stopBrowsing()
        stopAdvertising()
    }

    private inner class Browser : NsdManager.DiscoveryListener {
        private var closed = false
        private var started = false
        private var resolving = false
        private val pending = ArrayDeque<NsdServiceInfo>()
        private val found = mutableSetOf<String>()
        private val resolved = linkedMapOf<String, String>()
        private var multicast: WifiManager.MulticastLock? = null
        private val timeout = Runnable { if (browser === this) stopBrowsing() }

        fun start() {
            try {
                multicast = wifi?.createMulticastLock("SurSoundDiscovery")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
                nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, this)
                main.postDelayed(timeout, 15_000)
            } catch (_: Exception) { close() }
        }

        fun close() {
            if (closed) return
            closed = true
            main.removeCallbacks(timeout)
            if (started) runCatching { nsd.stopServiceDiscovery(this) }
            multicast?.let { runCatching { if (it.isHeld) it.release() } }
            multicast = null
            found.clear()
            pending.clear()
            resolved.clear()
        }

        override fun onDiscoveryStarted(serviceType: String) {
            main.post {
                started = true
                if (closed) runCatching { nsd.stopServiceDiscovery(this) }
            }
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { main.post { close() } }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { main.post { close() } }
        override fun onDiscoveryStopped(serviceType: String) { main.post { close() } }

        override fun onServiceFound(service: NsdServiceInfo) {
            main.post {
                if (closed || found.size >= 24 || !found.add(service.serviceName)) return@post
                pending.addLast(service)
                resolveNext()
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            main.post {
                if (closed) return@post
                found.remove(service.serviceName)
                pending.removeAll { it.serviceName == service.serviceName }
                resolved.remove(service.serviceName)
                onPeers(resolved.values.distinct())
            }
        }

        private fun resolveNext() {
            if (closed || resolving || pending.isEmpty()) return
            val service = pending.removeFirst()
            if (service.serviceName !in found) { resolveNext(); return }
            resolving = true
            try {
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        main.post { resolving = false; resolveNext() }
                    }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        main.post {
                            resolving = false
                            if (!closed && service.serviceName in found && info.port == LanTestSession.PORT) {
                                val ip = info.host?.hostAddress
                                if (ip != null && runCatching { LanPacketCodec.localPeer(ip) }.isSuccess) {
                                    resolved[service.serviceName] = ip
                                    onPeers(resolved.values.distinct())
                                }
                            }
                            resolveNext()
                        }
                    }
                })
            } catch (_: Exception) { resolving = false; resolveNext() }
        }
    }

    private inner class Advertiser : NsdManager.RegistrationListener {
        private var closed = false
        private var registered = false
        fun start() {
            try {
                val info = NsdServiceInfo().apply {
                    serviceName = "SurSound-" + UUID.randomUUID().toString().take(8)
                    serviceType = TYPE
                    port = LanTestSession.PORT
                }
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, this)
            } catch (_: Exception) { close() }
        }
        fun close() {
            if (closed) return
            closed = true
            if (registered) runCatching { nsd.unregisterService(this) }
        }
        override fun onServiceRegistered(info: NsdServiceInfo) {
            main.post {
                registered = true
                if (closed) runCatching { nsd.unregisterService(this) }
            }
        }
        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) { main.post { close() } }
        override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
    }

    companion object { private const val TYPE = "_sursound._udp." }
}
