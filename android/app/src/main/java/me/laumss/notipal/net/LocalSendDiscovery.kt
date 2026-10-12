package me.laumss.notipal.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import me.laumss.notipal.BuildConfig
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread


object LocalSendDiscovery {
    private const val TAG = "LocalSendDiscovery"
    const val MULTICAST_ADDR = "224.0.0.167"
    const val MULTICAST_PORT = 53317
    private const val ANNOUNCE_INTERVAL_MS = 5_000L
    private const val ANNOUNCE_POLL_MS = 500L
    
    private const val DICTATION_PEER_TTL_MS = 15_000L
    private const val PEER_CLEANUP_INTERVAL_MS = 5_000L
    private const val MAX_FINGERPRINT_LENGTH = 256
    private const val MAX_ALIAS_LENGTH = 128
    private const val SOCKET_RETRY_INITIAL_MS = 1_000L
    private const val SOCKET_RETRY_MAX_MS = 15_000L

    
    data class DictationPeer(
        val alias: String,
        val ip: String,
        val fingerprint: String,
        
        val llmPort: Int,
        val authRequired: Boolean = false,
        val announcementSequence: Long = 0L,
        
        val lastSeen: Long = SystemClock.elapsedRealtime(),
    )

    fun interface DictationPeerListener {
        
        fun onPeerSeen(peer: DictationPeer, endpointChanged: Boolean)
    }

    private val lifecycleLock = Any()

    @Volatile private var discoveryRunning = false
    @Volatile private var dictationListeningRequested = false
    @Volatile private var localSendListeningRequested = false
    @Volatile private var localSendAnnounceEnabled = false
    @Volatile private var socketGeneration = 0L

    @Volatile private var multicastSocket: MulticastSocket? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var appContext: Context? = null

    
    @Volatile private var announcementProvider: (() -> JSONObject)? = null
    
    @Volatile private var localSendAnnouncementHandler: ((String, JSONObject) -> Unit)? = null

    private val dictationPeers = ConcurrentHashMap<String, DictationPeer>()
    private val dictationSequences = ConcurrentHashMap<String, Long>()
    private val dictationPeerListeners = CopyOnWriteArraySet<DictationPeerListener>()

    

    @JvmStatic
    fun startDictationListening(context: Context) {
        synchronized(lifecycleLock) {
            appContext = context.applicationContext
            dictationListeningRequested = true
            ensureNetworkCallbackLocked()
            ensureDiscoveryRunning()
        }
    }

    @JvmStatic
    fun stopDictationListening() {
        synchronized(lifecycleLock) {
            dictationListeningRequested = false
            stopIfNoDemand()
        }
    }

    @JvmStatic
    fun addDictationPeerListener(listener: DictationPeerListener) {
        dictationPeerListeners += listener
    }

    @JvmStatic
    fun removeDictationPeerListener(listener: DictationPeerListener) {
        dictationPeerListeners -= listener
    }

    
    @JvmStatic
    fun startLocalSendDiscovery(
        context: Context,
        announcementProvider: () -> JSONObject,
        onLocalSendAnnouncement: (senderIp: String, payload: JSONObject) -> Unit,
    ) {
        synchronized(lifecycleLock) {
            appContext = context.applicationContext
            this.announcementProvider = announcementProvider
            this.localSendAnnouncementHandler = onLocalSendAnnouncement
            localSendListeningRequested = true
            ensureNetworkCallbackLocked()
            ensureDiscoveryRunning()
        }
    }

    
    @JvmStatic
    fun stopLocalSendDiscovery() {
        synchronized(lifecycleLock) {
            localSendListeningRequested = false
            localSendAnnounceEnabled = false
            announcementProvider = null
            localSendAnnouncementHandler = null
            stopIfNoDemand()
        }
    }

    @JvmStatic
    fun setLocalSendAnnouncing(enabled: Boolean) {
        localSendAnnounceEnabled = enabled
    }

    
    @JvmStatic
    fun restartForNetworkChange() {
        restartForNetworkChange("external")
    }

    

    private fun hasDiscoveryDemand(): Boolean =
        dictationListeningRequested || localSendListeningRequested

    
    private fun ensureDiscoveryRunning() {
        if (discoveryRunning && multicastSocket != null) return
        val ctx = appContext ?: return
        closeSocketLocked()
        discoveryRunning = true
        val generation = ++socketGeneration
        acquireMulticastLock(ctx)
        thread(isDaemon = true, name = "LSDiscovery-Recv") { discoveryLoop(generation) }
        thread(isDaemon = true, name = "LSDiscovery-Announce") { announceLoop(generation) }
    }

    
    private fun stopIfNoDemand() {
        if (hasDiscoveryDemand()) return
        discoveryRunning = false
        closeSocketLocked()
        releaseMulticastLock()
        unregisterNetworkCallbackLocked()
        dictationPeers.clear()
        dictationSequences.clear()
    }

    
    private fun closeSocketLocked() {
        socketGeneration++
        try { multicastSocket?.close() } catch (_: Exception) {}
        multicastSocket = null
    }

    
    private fun ensureNetworkCallbackLocked() {
        if (networkCallback != null) return
        val ctx = appContext ?: return
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                restartForNetworkChange("available")
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                restartForNetworkChange("link=${linkProperties.interfaceName.orEmpty()}")
            }

            override fun onLost(network: Network) {
                restartForNetworkChange("lost")
            }
        }
        try {
            cm.registerNetworkCallback(
                android.net.NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                callback,
            )
            networkCallback = callback
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Wi-Fi network callback registered")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "network callback failed: ${e.message}")
        }
    }

    private fun restartForNetworkChange(reason: String) {
        synchronized(lifecycleLock) {
            if (!hasDiscoveryDemand()) return
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "network changed ($reason), rebinding discovery")
            closeSocketLocked()
            ensureDiscoveryRunning()
        }
    }

    
    private fun unregisterNetworkCallbackLocked() {
        val callback = networkCallback ?: return
        networkCallback = null
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        try { cm?.unregisterNetworkCallback(callback) } catch (_: Exception) {}
    }

    private fun openSocket(): MulticastSocket? {
        var openedSocket: MulticastSocket? = null
        return try {
            val group = InetAddress.getByName(MULTICAST_ADDR)
            val groupAddress = InetSocketAddress(group, MULTICAST_PORT)
            val interfaces = multicastInterfaces()
            MulticastSocket(null).also { openedSocket = it }.apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(MULTICAST_PORT))
                timeToLive = 1

                var joined = 0
                interfaces.forEach { iface ->
                    runCatching {
                        joinGroup(groupAddress, iface)
                        joined++
                    }.onFailure {
                        if (BuildConfig.ENABLE_DEBUG) {
                            Log.d(TAG, "join via ${iface.name} failed: ${it.message}")
                        }
                    }
                }
                if (joined == 0) {
                    @Suppress("DEPRECATION")
                    joinGroup(group)
                }

                
                
                preferredWifiInterface()?.let { networkInterface = it }
                if (BuildConfig.ENABLE_DEBUG) {
                    val names = interfaces.joinToString { it.name }.ifEmpty { "default" }
                    Log.i(TAG, "multicast joined on $names; egress=${networkInterface?.name}")
                }
            }
        } catch (e: Exception) {
            try { openedSocket?.close() } catch (_: Exception) {}
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "Multicast bind failed: ${e.message}")
            null
        }
    }

    private fun discoveryLoop(generation: Long) {
        var retryDelayMs = SOCKET_RETRY_INITIAL_MS
        val buf = ByteArray(8192)
        while (isCurrentGeneration(generation)) {
            val socket = openSocket()
            if (socket != null) {
                synchronized(lifecycleLock) {
                    if (!isCurrentGeneration(generation)) {
                        try { socket.close() } catch (_: Exception) {}
                        return
                    }
                    multicastSocket = socket
                }
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "Discovery listening on $MULTICAST_ADDR:$MULTICAST_PORT (gen=$generation)")
                }
                try {
                    while (isCurrentGeneration(generation)) {
                        val pkt = DatagramPacket(buf, buf.size)
                        try {
                            socket.receive(pkt)
                        } catch (e: Exception) {
                            if (isCurrentGeneration(generation) && BuildConfig.ENABLE_DEBUG) {
                                Log.d(TAG, "receive interrupted; recreating socket: ${e.message}")
                            }
                            break
                        }
                        if (!isCurrentGeneration(generation)) break
                        retryDelayMs = SOCKET_RETRY_INITIAL_MS
                        val senderIp = pkt.address?.hostAddress ?: continue
                        try {
                            dispatchMulticastPacket(senderIp, String(pkt.data, 0, pkt.length, Charsets.UTF_8))
                        } catch (e: Exception) {
                            if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "packet handler: ${e.message}")
                        }
                    }
                } finally {
                    synchronized(lifecycleLock) {
                        if (multicastSocket === socket) multicastSocket = null
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
            }
            if (!waitForSocketRetry(generation, retryDelayMs)) break
            retryDelayMs = minOf(retryDelayMs * 2, SOCKET_RETRY_MAX_MS)
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Discovery receive loop exited (gen=$generation)")
    }

    private fun isCurrentGeneration(generation: Long): Boolean =
        discoveryRunning && generation == socketGeneration && hasDiscoveryDemand()

    private fun waitForSocketRetry(generation: Long, delayMs: Long): Boolean {
        val retryAt = SystemClock.elapsedRealtime() + delayMs
        while (isCurrentGeneration(generation)) {
            val remaining = retryAt - SystemClock.elapsedRealtime()
            if (remaining <= 0) return true
            try {
                Thread.sleep(minOf(remaining, ANNOUNCE_POLL_MS))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun announceLoop(generation: Long) {
        val group = try { InetAddress.getByName(MULTICAST_ADDR) } catch (_: Exception) { return }
        var lastAnnounceAt = 0L
        var lastPeerCleanupAt = 0L
        var announceCount = 0
        while (discoveryRunning && generation == socketGeneration) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastPeerCleanupAt >= PEER_CLEANUP_INTERVAL_MS) {
                pruneExpiredDictationPeers(now)
                lastPeerCleanupAt = now
            }
            if (localSendAnnounceEnabled && now - lastAnnounceAt >= ANNOUNCE_INTERVAL_MS) {
                val socket = multicastSocket
                val payload = announcementProvider?.invoke()
                if (socket != null && payload != null) {
                    val data = payload.toString().toByteArray(Charsets.UTF_8)
                    val targets = (listOf(group) + lanBroadcastAddresses())
                        .distinctBy { it.hostAddress }
                    var sent = 0
                    targets.forEach { target ->
                        try {
                            socket.send(DatagramPacket(data, data.size, target, MULTICAST_PORT))
                            sent++
                        } catch (e: Exception) {
                            if (discoveryRunning && BuildConfig.ENABLE_DEBUG) {
                                Log.d(TAG, "announce to ${target.hostAddress} failed: ${e.message}")
                            }
                        }
                    }
                    if (sent > 0) {
                        lastAnnounceAt = now
                        announceCount++
                        if (BuildConfig.ENABLE_DEBUG && (announceCount <= 3 || announceCount % 12 == 0)) {
                            Log.i(TAG, "LocalSend announce #$announceCount sent to " +
                                targets.joinToString { it.hostAddress })
                        }
                    }
                }
            }
            try { Thread.sleep(ANNOUNCE_POLL_MS) } catch (_: InterruptedException) { break }
        }
    }

    
    @JvmStatic
    fun sendUnicast(ip: String, payload: JSONObject) {
        try {
            val data = payload.toString().toByteArray(Charsets.UTF_8)
            multicastSocket?.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), MULTICAST_PORT))
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "unicast to $ip failed: ${e.message}")
        }
    }

    

    private fun dispatchMulticastPacket(senderIp: String, msg: String) {
        if (isLocalAddress(senderIp)) return
        val dto = try { JSONObject(msg) } catch (_: Exception) { return }
        if (dto.optString("service") == "dictation") {
            handleDictationAnnouncement(senderIp, dto)
        } else {
            localSendAnnouncementHandler?.invoke(senderIp, dto)
        }
    }

    private fun handleDictationAnnouncement(senderIp: String, dto: JSONObject) {
        
        
        val sender = runCatching { InetAddress.getByName(senderIp) }.getOrNull()
        if (sender !is Inet4Address || sender.isAnyLocalAddress || sender.isMulticastAddress) return
        if (dto.has("protocolVersion")) {
            val version = dto.opt("protocolVersion") as? Number ?: return
            if (version.toDouble() != 1.0) return
        }
        if (dto.has("transport") && dto.opt("transport") != "http+sse") return
        if (dto.has("authMode")) {
            val authMode = dto.opt("authMode") as? String ?: return
            if (authMode != "none" && authMode != "jwt") return
        }
        if (dto.has("sentAt") && dto.opt("sentAt") !is Number) return
        if (dto.has("expiresAt") && dto.opt("expiresAt") !is Number) return
        val localhostOnly = readBoolean(dto, "localhostOnly") ?: return
        if (localhostOnly) return
        val fp = (dto.opt("fingerprint") as? String)?.takeIf { isValidFingerprint(it) } ?: return
        val port = readDictationPort(dto) ?: return
        val authRequired = readBoolean(dto, "authRequired") ?: return
        val authMode = (dto.opt("authMode") as? String)
        if (authMode != null && authMode != if (authRequired) "jwt" else "none") return
        val sequence = readOptionalLong(dto, "sequence") ?: 0L
        val now = SystemClock.elapsedRealtime()
        if (sequence > 0L) {
            val previousSequence = dictationSequences[fp]
            
            
            val previousPeer = dictationPeers[fp]
            if (previousSequence != null && sequence <= previousSequence &&
                previousPeer != null && now - previousPeer.lastSeen < 1_000L) return
            dictationSequences[fp] = sequence
        }
        val alias = (dto.opt("alias") as? String)
            ?.takeIf { it.isNotBlank() && it.length <= MAX_ALIAS_LENGTH && it.none(Char::isISOControl) }
            ?: "RikkaHub"
        val peer = DictationPeer(
            alias = alias,
            ip = senderIp,
            fingerprint = fp,
            llmPort = port,
            authRequired = authRequired,
            announcementSequence = sequence,
            lastSeen = now,
        )
        val previous = dictationPeers.put(fp, peer)
        val endpointChanged = previous == null ||
            previous.ip != peer.ip ||
            previous.llmPort != peer.llmPort
        if (endpointChanged && BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "AIRelay peer ${peer.alias} @ ${peer.ip}:${peer.llmPort} auth=${peer.authRequired} fp=${fp.take(8)}")
        }
        dictationPeerListeners.forEach {
            try { it.onPeerSeen(peer, endpointChanged) } catch (_: Exception) {}
        }
    }

    private fun isValidFingerprint(value: String): Boolean =
        value.isNotEmpty() && value.length <= MAX_FINGERPRINT_LENGTH &&
            value == value.trim() && value.none { it.isISOControl() || it.isWhitespace() }

    
    private fun readDictationPort(dto: JSONObject): Int? {
        val value = when {
            dto.has("dictationLlmPort") -> dto.opt("dictationLlmPort")
            dto.has("port") -> dto.opt("port")
            else -> return null
        }
        val port = when (value) {
            is Number -> {
                val candidate = value.toLong()
                if (value.toDouble() != candidate.toDouble() || candidate !in 1L..65535L) return null
                candidate.toInt()
            }
            else -> return null
        }
        return port
    }

    private fun readBoolean(dto: JSONObject, key: String): Boolean? {
        if (!dto.has(key)) return false
        return dto.opt(key) as? Boolean
    }

    private fun readOptionalLong(dto: JSONObject, key: String): Long? {
        if (!dto.has(key)) return null
        val value = dto.opt(key) as? Number ?: return null
        val result = value.toLong()
        return result.takeIf { it >= 0L && value.toDouble() == it.toDouble() }
    }

    private fun pruneExpiredDictationPeers(now: Long) {
        dictationPeers.forEach { (fingerprint, peer) ->
            if (now - peer.lastSeen > DICTATION_PEER_TTL_MS && dictationPeers.remove(fingerprint, peer)) {
                dictationSequences.remove(fingerprint)
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "AIRelay peer expired fp=${fingerprint.take(8)}")
                }
            }
        }
    }

    

    private fun acquireMulticastLock(ctx: Context) {
        if (multicastLock?.isHeld == true) return
        try {
            val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wifi.createMulticastLock("InklingDiscovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "MulticastLock failed: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try { multicastLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        multicastLock = null
    }

    private fun preferredWifiInterface(): NetworkInterface? {
        val ctx = appContext ?: return null
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            val network = cm.activeNetwork ?: return null
            val capabilities = cm.getNetworkCapabilities(network) ?: return null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            val name = cm.getLinkProperties(network)?.interfaceName ?: return null
            NetworkInterface.getByName(name)
        } catch (_: Exception) {
            null
        }
    }

    
    private fun multicastInterfaces(): List<NetworkInterface> {
        val result = mutableListOf<NetworkInterface>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val usable = runCatching {
                    iface.isUp && !iface.isLoopback && !iface.isPointToPoint &&
                        iface.interfaceAddresses.any { address ->
                            (address.address as? Inet4Address)?.isSiteLocalAddress == true
                        }
                }.getOrDefault(false)
                if (usable) result += iface
            }
        } catch (_: Exception) {}
        return result
    }

    
    private fun lanBroadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        multicastInterfaces().forEach { iface ->
            iface.interfaceAddresses.forEach { address ->
                val ipv4 = address.address as? Inet4Address
                val broadcast = address.broadcast
                if (ipv4?.isSiteLocalAddress == true && broadcast != null) {
                    result += broadcast
                }
            }
        }
        return result.distinctBy { it.hostAddress }
    }

    private fun isLocalAddress(ip: String): Boolean = ip in localIps()

    private fun localIps(): Set<String> {
        val result = mutableSetOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { result += it }
                    }
                }
            }
        } catch (_: Exception) {}
        return result
    }
}
