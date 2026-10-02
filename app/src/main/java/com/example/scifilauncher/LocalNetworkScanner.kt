package com.example.scifilauncher

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Approximate live device count on the currently-connected WiFi network - a bounded ping sweep of
 * a private IPv4 subnet with prefix /24 through /30, not a precise count
 * from the router itself. Real limits, told to the user up front: only works for the network
 * you're on RIGHT NOW (can't retroactively count for a past network), counts responding devices
 * not verified people, and many public/guest networks with client isolation enabled will make
 * every other device unreachable regardless of how many are actually connected. */
object LocalNetworkScanner {
    private const val SCAN_TIMEOUT_MS = 400

    /** Null if WiFi or a supported private subnet is unavailable. Otherwise the count of OTHER
     * reachable hosts in that subnet (this device and network/broadcast addresses excluded). */
    suspend fun countOtherDevicesOnCurrentNetwork(context: Context): Int? = withContext(Dispatchers.IO) {
        val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = connectivityManager?.activeNetwork ?: return@withContext null
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return@withContext null
        if (!capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return@withContext null
        if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return@withContext null

        val link = connectivityManager.getLinkProperties(activeNetwork) ?: return@withContext null
        val local = link.linkAddresses.firstOrNull {
            it.address is Inet4Address && it.address.isSiteLocalAddress
        } ?: return@withContext null
        // Bound discovery to a small private subnet; never guess a /24 for a different mask.
        val prefix = local.prefixLength
        if (prefix !in 24..30) return@withContext null
        val ownIp = local.address.address.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
        val hostCount = 1L shl (32 - prefix)
        val base = ownIp and (0xffffffffL xor (hostCount - 1))
        val networkInterface = runCatching { NetworkInterface.getByInetAddress(local.address) }.getOrNull()
            ?: return@withContext null
        val permits = Semaphore(16)
        val jobs = (1L until hostCount - 1).filter { base + it != ownIp }.map { offset ->
            async {
                val addrInt = base + offset
                val bytes = byteArrayOf(
                    ((addrInt shr 24) and 0xFF).toByte(),
                    ((addrInt shr 16) and 0xFF).toByte(),
                    ((addrInt shr 8) and 0xFF).toByte(),
                    (addrInt and 0xFF).toByte()
                )
                permits.withPermit {
                    runCatching { InetAddress.getByAddress(bytes).isReachable(networkInterface, 0, SCAN_TIMEOUT_MS) }.getOrDefault(false)
                }
            }
        }
        jobs.awaitAll().count { it }
    }
}
