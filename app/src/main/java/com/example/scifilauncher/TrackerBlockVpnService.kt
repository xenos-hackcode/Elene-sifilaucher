package com.example.scifilauncher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/** DNS-only local filter. Does not encrypt or route browsing through a remote VPN server. */
class TrackerBlockVpnService : VpnService() {
    private class Session(val pfd: ParcelFileDescriptor) {
        val resources = VpnSessionResources().apply { register(pfd) }
        var job: Job? = null
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private val blockedDomains: Set<String> by lazy { loadBlocklist() }

    companion object {
        const val ACTION_STOP = "com.example.scifilauncher.STOP_DNS_FILTER"
        private const val ACTION_START = "com.example.scifilauncher.START_DNS_FILTER"
        private const val CHANNEL = "dns_filter"
        private const val NOTIFICATION_ID = 8170
        private const val TAG = "TrackerBlockVpn"
        private const val UPSTREAM_DNS = "1.1.1.1"
        private const val FALLBACK_DNS = "8.8.8.8"
        // DNS-over-HTTPS, tried before the plaintext UDP path above - RFC 8484, standard
        // "application/dns-message" wire format. Falls back to plaintext UDP only if DoH itself
        // fails (e.g. no route to the DoH host yet), so a resolver hiccup doesn't look like "the
        // whole internet is down".
        private const val DOH_PRIMARY = "https://cloudflare-dns.com/dns-query"
        private const val DOH_FALLBACK = "https://dns.google/dns-query"
        private val mutableRunning = MutableStateFlow(false)
        val running = mutableRunning.asStateFlow()
        val isRunning: Boolean get() = mutableRunning.value

        fun start(context: Context) {
            ContextCompat.startForegroundService(context,
                Intent(context, TrackerBlockVpnService::class.java).setAction(ACTION_START))
        }
        fun stop(context: Context) {
            // The VPN framework can keep the Service bound after stopService(), delaying
            // onDestroy indefinitely. Teardown must run inside an explicit command instead.
            context.startService(Intent(context, TrackerBlockVpnService::class.java).setAction(ACTION_STOP))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            showNotification()
            if (session == null) startVpn()
        } catch (e: Exception) {
            Log.e(TAG, "Could not start DNS filter", e)
            stopVpn()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun showNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Tracker & ad blocker",
                NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(this, NOTIFICATION_ID,
            Intent(this, TrackerBlockVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID,
            Intent(this, NetworkProtectionActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Tracker & ad blocker active")
            .setContentText("Local DNS filtering ? Tap to manage")
            .setContentIntent(open).setOngoing(true)
            .addAction(0, "Disconnect", stop).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    override fun onRevoke() {
        // onRevoke may be called off the main thread. Serialize all lifecycle changes.
        mainHandler.post { stopVpn(); stopSelf() }
    }

    override fun onDestroy() {
        stopVpn()
        scope.cancel()
        super.onDestroy()
    }

    private fun loadBlocklist(): Set<String> = resources.openRawResource(R.raw.tracker_blocklist)
        .bufferedReader().useLines { lines ->
            lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        }

    private fun isBlocked(domain: String): Boolean {
        var suffix = domain.lowercase().trimEnd('.')
        while (true) {
            if (blockedDomains.contains(suffix)) return true
            val dot = suffix.indexOf('.')
            if (dot < 0) return false
            suffix = suffix.substring(dot + 1)
        }
    }

    private fun startVpn() {
        check(blockedDomains.isNotEmpty()) { "Blocklist unavailable" }
        val builder = Builder().setSession("Xenos ? local DNS filter")
            .addAddress("10.0.0.2", 32).addDnsServer("10.0.0.1")
            .addRoute("10.0.0.1", 32).setMtu(1500).setBlocking(false)
        // An HTTP proxy is a hint to compatible apps, never a reason to route all IP
        // traffic into this DNS-only implementation.
        if (Build.VERSION.SDK_INT >= 29) {
            val raw = getSharedPreferences("lock_prefs", MODE_PRIVATE)
                .getString("traffic_proxy_address", "").orEmpty().trim()
            if (raw.isNotEmpty()) {
                val host = raw.substringBeforeLast(':', "")
                val port = raw.substringAfterLast(':').toIntOrNull()
                require(host.isNotBlank() && port != null && port in 1..65535) { "Invalid HTTP proxy address" }
                builder.setHttpProxy(android.net.ProxyInfo.buildDirectProxy(host, port))
            }
        }
        // Apps the user has excepted (Security > App exceptions) never enter this VPN's tunnel at
        // all, so nothing of theirs is DNS-filtered - addDisallowedApplication throws if the
        // package was uninstalled since the exception was set, which just means it's moot.
        TrackerExceptionsStore.excludedPackages(this).forEach { pkg ->
            runCatching { builder.addDisallowedApplication(pkg) }
                .onFailure { Log.w(TAG, "Could not exclude $pkg from tracker-block VPN", it) }
        }
        val current = Session(builder.establish() ?: error("VPN permission was not granted"))
        session = current
        mutableRunning.value = true
        current.job = scope.launch {
            try {
                runLoop(current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current.resources.isOpen) Log.e(TAG, "DNS filter stopped", e)
            } finally {
                current.resources.close()
                mainHandler.post {
                    if (session === current) { stopVpn(); stopSelf() }
                }
            }
        }
    }

    private fun stopVpn() {
        val previous = session
        session = null
        // Close the TUN and EVERY in-flight UDP socket before publishing Off.
        previous?.resources?.close()
        previous?.job?.cancel()
        mutableRunning.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private suspend fun runLoop(current: Session) = coroutineScope {
        val buffer = ByteArray(32767)
        val permits = Semaphore(16)
        while (isActive && current.resources.isOpen) {
            val count = try {
                Os.read(current.pfd.fileDescriptor, buffer, 0, buffer.size)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) {
                    delay(20)
                    continue
                }
                throw e
            }
            if (count <= 0) { delay(20); continue }
            val packet = buffer.copyOf(count)
            // Acquire before launching, so a flood cannot allocate unlimited suspended jobs.
            permits.acquire()
            launch {
                try { handlePacket(packet, current) } finally { permits.release() }
            }
        }
    }

    private fun writeReply(current: Session, packet: ByteArray) {
        current.resources.whileOpen {
            runCatching { Os.write(current.pfd.fileDescriptor, packet, 0, packet.size) }
        }
    }

    private fun handlePacket(packet: ByteArray, session: Session) {
        if (!session.resources.isOpen || packet.size < 28) return
        val version = (packet[0].toInt() shr 4) and 0xF
        if (version != 4) return

        val ihl = (packet[0].toInt() and 0xF) * 4
        if (ihl < 20 || packet.size < ihl + 8) return
        if ((packet[6].toInt() and 0x3f) != 0 || packet[7].toInt() != 0) return

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return // UDP only

        val udpStart = ihl
        val destPort = ((packet[udpStart + 2].toInt() and 0xFF) shl 8) or (packet[udpStart + 3].toInt() and 0xFF)
        if (destPort != 53) return

        val dnsStart = udpStart + 8
        if (packet.size <= dnsStart + 12) return
        val udpLength = ((packet[udpStart + 4].toInt() and 255) shl 8) or (packet[udpStart + 5].toInt() and 255)
        if (udpLength < 20 || udpStart + udpLength != packet.size) return

        val domain = parseDnsQueryName(packet, dnsStart) ?: return

        if (isBlocked(domain)) {
            Log.d(TAG, "Blocked: $domain")
            TrackerBlockStats.recordBlock(this)
            buildBlockedResponse(packet, ihl, dnsStart)?.let { writeReply(session, it) }
        } else {
            forwardToUpstream(packet, ihl, udpStart, dnsStart, session)
        }
    }

    private fun parseDnsQueryName(packet: ByteArray, dnsStart: Int): String? {
        var pos = dnsStart + 12 // skip DNS header
        val sb = StringBuilder()
        while (pos < packet.size) {
            val len = packet[pos].toInt() and 0xFF
            if (len == 0) return sb.toString().takeIf { it.isNotEmpty() }
            if (len > 63) return null
            pos += 1
            if (pos + len > packet.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(packet, pos, len, Charsets.US_ASCII))
            pos += len
        }
        return null
    }

    private fun forwardToUpstream(packet: ByteArray, ihl: Int, udpStart: Int, dnsStart: Int, session: Session) {
        val dnsPayload = packet.copyOfRange(dnsStart, packet.size)
        val reply = queryUpstreamDoH(DOH_PRIMARY, dnsPayload)
            ?: queryUpstreamDoH(DOH_FALLBACK, dnsPayload)
            ?: queryUpstream(UPSTREAM_DNS, dnsPayload, session)
            ?: queryUpstream(FALLBACK_DNS, dnsPayload, session)
        if (reply != null) writeReply(session, buildReplyPacket(packet, ihl, reply))
    }

    // Custom SocketFactory so every socket OkHttp opens for a DoH request is protect()-ed - without
    // that, the HTTPS request would itself get captured by this VPN's own tunnel and never reach
    // the internet (a routing loop), same reason queryUpstream()'s DatagramSocket calls protect().
    // Not tied to a Session/VpnSessionResources - protect() only excludes a socket from routing at
    // the OS level, independent of this service's own session lifecycle, and each query already
    // has its own short (2s) timeout bounding how long a stale connection can linger.
    private val dohClient by lazy {
        val socketFactory = object : javax.net.SocketFactory() {
            override fun createSocket(): java.net.Socket =
                java.net.Socket().also { check(protect(it)) { "Cannot exclude DoH socket from VPN" } }
            override fun createSocket(host: String, port: Int) = createSocket().apply { connect(InetSocketAddress(host, port)) }
            override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int) =
                createSocket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port)) }
            override fun createSocket(address: java.net.InetAddress, port: Int) = createSocket().apply { connect(InetSocketAddress(address, port)) }
            override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int) =
                createSocket().apply { bind(InetSocketAddress(localAddress, localPort)); connect(InetSocketAddress(address, port)) }
        }
        okhttp3.OkHttpClient.Builder()
            .socketFactory(socketFactory)
            .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
    private val dnsMessageMediaType = "application/dns-message".toMediaType()

    private fun queryUpstreamDoH(url: String, dnsPayload: ByteArray): ByteArray? = runCatching {
        val request = okhttp3.Request.Builder()
            .url(url)
            .post(dnsPayload.toRequestBody(dnsMessageMediaType))
            .header("Accept", "application/dns-message")
            .header("Content-Type", "application/dns-message")
            .build()
        dohClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            resp.body?.bytes()
        }
    }.onFailure { Log.d(TAG, "DoH query to $url failed", it) }.getOrNull()

    private fun queryUpstream(server: String, payload: ByteArray, session: Session): ByteArray? {
        if (!session.resources.isOpen) return null
        val socket = DatagramSocket()
        if (!session.resources.register(socket)) return null
        return try {
            check(protect(socket)) { "Cannot exclude DNS socket from VPN" }
            socket.soTimeout = 2000
            // Connected UDP rejects replies from unrelated senders.
            socket.connect(InetSocketAddress(server, 53))
            socket.send(DatagramPacket(payload, payload.size))
            val reply = DatagramPacket(ByteArray(4096), 4096)
            socket.receive(reply)
            val data = reply.data.copyOf(reply.length)
            if (data.size < 12 || payload.size < 12 || data[0] != payload[0] || data[1] != payload[1]
                || (data[2].toInt() and 0x80) == 0) null else data
        } catch (_: Exception) {
            null
        } finally {
            session.resources.release(socket)
        }
    }

    private fun buildBlockedResponse(packet: ByteArray, ihl: Int, dnsStart: Int): ByteArray? {
        val dnsQuery = packet.copyOfRange(dnsStart, packet.size)
        if (dnsQuery.size < 12) return null
        val dnsReply = dnsQuery.copyOf()
        dnsReply[2] = (dnsReply[2].toInt() or 0x80).toByte() // QR = response
        dnsReply[3] = (dnsReply[3].toInt() or 0x03).toByte() // RCODE = NXDOMAIN
        return buildReplyPacket(packet, ihl, dnsReply)
    }

    /** Swaps src/dst IP+port from the original request and attaches [dnsPayload],
     * recomputing IP/UDP headers (including the IP checksum) for a valid reply packet. */
    private fun buildReplyPacket(original: ByteArray, ihl: Int, dnsPayload: ByteArray): ByteArray {
        val udpStart = ihl
        val udpLength = 8 + dnsPayload.size
        val totalLength = ihl + udpLength
        val out = ByteArray(totalLength)

        System.arraycopy(original, 0, out, 0, ihl)
        for (i in 0 until 4) {
            out[12 + i] = original[16 + i] // src = original dst
            out[16 + i] = original[12 + i] // dst = original src
        }
        out[2] = ((totalLength shr 8) and 0xFF).toByte()
        out[3] = (totalLength and 0xFF).toByte()
        out[10] = 0
        out[11] = 0
        val ipChecksum = computeChecksum(out, 0, ihl)
        out[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        out[11] = (ipChecksum and 0xFF).toByte()

        out[ihl] = original[udpStart + 2]     // src port = original dst port (53)
        out[ihl + 1] = original[udpStart + 3]
        out[ihl + 2] = original[udpStart]     // dst port = original src port
        out[ihl + 3] = original[udpStart + 1]
        out[ihl + 4] = ((udpLength shr 8) and 0xFF).toByte()
        out[ihl + 5] = (udpLength and 0xFF).toByte()
        out[ihl + 6] = 0 // UDP checksum left as 0 (optional over IPv4)
        out[ihl + 7] = 0

        System.arraycopy(dnsPayload, 0, out, ihl + 8, dnsPayload.size)
        return out
    }

    private fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (length % 2 == 1) {
            sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.toInt().inv() and 0xFFFF
    }
}
