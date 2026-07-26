package com.example.scifilauncher

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Local, on-device DNS sinkhole: blocks known ad/tracker domains by intercepting DNS
 * queries on the VPN interface and refusing to resolve matches, forwarding everything
 * else to a real upstream resolver. Traffic never leaves the device or routes through
 * any external server - this is a filter, not a tunnel. Only DNS (UDP/53) is handled;
 * all other traffic is left alone by design rather than reimplementing full IP routing.
 */
class TrackerBlockVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val blockedDomains: Set<String> by lazy { loadBlocklist() }

    // Restart attempts after the interface dies unexpectedly (not a deliberate stop) are capped
    // to avoid spin-looping forever if something's persistently broken (e.g. draining battery
    // retrying every few milliseconds) - 3 restarts within a rolling minute, then give up and
    // leave a record of it rather than retrying silently forever.
    private val recentRestarts = mutableListOf<Long>()

    companion object {
        var isRunning: Boolean = false
            private set
        private const val TAG = "TrackerBlockVpn"
        private const val UPSTREAM_DNS = "1.1.1.1"
        private const val FALLBACK_DNS = "8.8.8.8"
        private const val MAX_RESTARTS_PER_MINUTE = 3
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand() called")
        startVpn()
        return START_STICKY
    }

    // VpnService.onRevoke() fires when the system or another app takes over as the active VPN,
    // or the user manually disables this one from system settings - without handling it, the
    // service's own isRunning/vpnInterface state goes stale and it doesn't know it stopped
    // actually protecting anything.
    override fun onRevoke() {
        Log.w(TAG, "onRevoke() - VPN was taken over or disabled externally")
        SystemEventLog.record(this, "VPN", "Revoked (system or another app took over)")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun loadBlocklist(): Set<String> {
        return runCatching {
            resources.openRawResource(R.raw.tracker_blocklist)
                .bufferedReader()
                .lineSequence()
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun isBlocked(domain: String): Boolean {
        val d = domain.lowercase().trimEnd('.')
        if (blockedDomains.contains(d)) return true
        return blockedDomains.any { d.endsWith(".$it") }
    }

    private fun startVpn() {
        Log.d(TAG, "startVpn() called, blocklist size=${blockedDomains.size}")
        if (vpnInterface != null) {
            Log.d(TAG, "VPN already established, skipping")
            return
        }

        val builder = Builder()
            .setSession("SciFi Tracker Block")
            .addAddress("10.0.0.2", 32)
            .addDnsServer("10.0.0.1")
            .addRoute("10.0.0.1", 32)
            .setMtu(1500)

        vpnInterface = runCatching { builder.establish() }
            .onFailure { Log.e(TAG, "builder.establish() threw", it) }
            .getOrNull()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface (establish() returned null)")
            return
        }
        Log.i(TAG, "VPN interface established successfully")

        isRunning = true
        job = scope.launch {
            runCatching { runLoop(vpnInterface!!) }
                .onFailure { e ->
                    Log.e(TAG, "VPN loop stopped", e)
                    val wasDeliberateStop = !isRunning
                    if (!wasDeliberateStop) attemptRestart(e)
                }
        }
    }

    /** The interface died on its own (not via stopVpn()/onRevoke()) - try to bring it back up,
     * but only up to MAX_RESTARTS_PER_MINUTE times so a persistent failure can't spin-loop
     * forever draining the battery. */
    private fun attemptRestart(cause: Throwable) {
        val now = System.currentTimeMillis()
        recentRestarts.removeAll { now - it > 60_000L }
        if (recentRestarts.size >= MAX_RESTARTS_PER_MINUTE) {
            SystemEventLog.record(
                this, "VPN",
                "Gave up after ${recentRestarts.size} restarts in the last minute (last error: ${cause.message})"
            )
            stopVpn()
            return
        }
        recentRestarts.add(now)
        SystemEventLog.record(this, "VPN", "Interface died (${cause.message}), restarting (${recentRestarts.size}/$MAX_RESTARTS_PER_MINUTE)")
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        isRunning = false
        startVpn()
    }

    private fun stopVpn() {
        isRunning = false
        job?.cancel()
        job = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    private fun runLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buffer = ByteArray(32767)

        while (isRunning) {
            val length = input.read(buffer)
            if (length <= 0) continue
            handlePacket(buffer.copyOf(length), output)
        }
    }

    private fun handlePacket(packet: ByteArray, output: FileOutputStream) {
        if (packet.isEmpty()) return
        val version = (packet[0].toInt() shr 4) and 0xF
        if (version != 4) return

        val ihl = (packet[0].toInt() and 0xF) * 4
        if (packet.size < ihl + 8) return

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return // UDP only

        val udpStart = ihl
        val destPort = ((packet[udpStart + 2].toInt() and 0xFF) shl 8) or (packet[udpStart + 3].toInt() and 0xFF)
        if (destPort != 53) return

        val dnsStart = udpStart + 8
        if (packet.size <= dnsStart + 12) return

        val domain = parseDnsQueryName(packet, dnsStart) ?: return

        if (isBlocked(domain)) {
            Log.d(TAG, "Blocked: $domain")
            buildBlockedResponse(packet, ihl, dnsStart)?.let { output.write(it) }
        } else {
            forwardToUpstream(packet, ihl, udpStart, dnsStart, output)
        }
    }

    private fun parseDnsQueryName(packet: ByteArray, dnsStart: Int): String? {
        var pos = dnsStart + 12 // skip DNS header
        val sb = StringBuilder()
        while (pos < packet.size) {
            val len = packet[pos].toInt() and 0xFF
            if (len == 0) break
            pos += 1
            if (pos + len > packet.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(packet, pos, len, Charsets.US_ASCII))
            pos += len
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    private fun forwardToUpstream(
        packet: ByteArray,
        ihl: Int,
        udpStart: Int,
        dnsStart: Int,
        output: FileOutputStream
    ) {
        val udpLength = ((packet[udpStart + 4].toInt() and 0xFF) shl 8) or (packet[udpStart + 5].toInt() and 0xFF)
        val dnsPayload = packet.copyOfRange(dnsStart, minOf(packet.size, udpStart + udpLength))

        // 1.1.1.1 is reliable but not infallible - a single upstream with no fallback means one
        // resolver hiccup looks like "the whole internet is down". Try the primary, then 8.8.8.8
        // once before giving up on this particular query.
        val reply = queryUpstream(UPSTREAM_DNS, dnsPayload) ?: queryUpstream(FALLBACK_DNS, dnsPayload)
        if (reply != null) {
            runCatching { output.write(buildReplyPacket(packet, ihl, reply)) }
        }
    }

    private fun queryUpstream(server: String, dnsPayload: ByteArray): ByteArray? = runCatching {
        val socket = DatagramSocket()
        protect(socket)
        socket.soTimeout = 5000

        socket.send(DatagramPacket(dnsPayload, dnsPayload.size, InetSocketAddress(server, 53)))

        val replyBuf = ByteArray(1024)
        val replyPacket = DatagramPacket(replyBuf, replyBuf.size)
        socket.receive(replyPacket)
        socket.close()

        replyBuf.copyOf(replyPacket.length)
    }.onFailure { Log.d(TAG, "DNS forward to $server failed", it) }.getOrNull()

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
