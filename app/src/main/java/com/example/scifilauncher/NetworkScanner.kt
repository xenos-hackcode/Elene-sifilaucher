package com.example.scifilauncher

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

data class ScannedDevice(
    val ip: String,
    val hostname: String?,
    val guessedType: String?
)

private val COMMON_PORTS = intArrayOf(80, 443, 62078, 5353, 8080, 22)

/**
 * Best-effort local network scan: pings every host on the current /24 subnet and reports
 * which ones respond, with a hostname/device-type guess where possible. There's no reliable,
 * non-root way to read the router's ARP table on modern Android, so this can miss devices
 * that don't answer ICMP or any of the common ports - it's a "who's likely around" scan,
 * not an authoritative device list.
 */
suspend fun scanLocalNetwork(context: Context): List<ScannedDevice> = withContext(Dispatchers.IO) {
    val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val ipInt = wifiManager?.connectionInfo?.ipAddress
    if (ipInt == null || ipInt == 0) return@withContext emptyList()

    val selfIp = intToIp(ipInt)
    val prefix = selfIp.substringBeforeLast('.') + "."

    val jobs = (1..254).map { host ->
        async {
            val ip = "$prefix$host"
            if (ip == selfIp) return@async null
            probeDevice(ip)
        }
    }
    jobs.awaitAll().filterNotNull()
}

private fun intToIp(ipInt: Int): String {
    return listOf(
        ipInt and 0xff,
        ipInt shr 8 and 0xff,
        ipInt shr 16 and 0xff,
        ipInt shr 24 and 0xff
    ).joinToString(".")
}

private fun probeDevice(ip: String): ScannedDevice? {
    val reachable = runCatching { InetAddress.getByName(ip).isReachable(200) }.getOrDefault(false)
        || COMMON_PORTS.any { port ->
            runCatching {
                Socket().use { it.connect(InetSocketAddress(ip, port), 150) }
                true
            }.getOrDefault(false)
        }

    if (!reachable) return null

    val hostname = runCatching {
        InetAddress.getByName(ip).canonicalHostName.takeIf { it != ip }
    }.getOrNull()

    return ScannedDevice(ip = ip, hostname = hostname, guessedType = guessDeviceType(hostname))
}

private fun guessDeviceType(hostname: String?): String? {
    if (hostname.isNullOrBlank()) return null
    val h = hostname.lowercase()
    return when {
        "iphone" in h -> "iPhone"
        "ipad" in h -> "iPad"
        "android" in h || "pixel" in h || "galaxy" in h -> "Android phone"
        "macbook" in h || "imac" in h || "mac-" in h -> "Mac"
        "laptop" in h || "-pc" in h || "desktop" in h -> "computer"
        "tv" in h || "roku" in h || "chromecast" in h || "firetv" in h -> "TV/streaming device"
        else -> null
    }
}
