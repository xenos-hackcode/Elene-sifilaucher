package com.example.scifilauncher

import android.content.Context
import android.net.wifi.WifiManager

data class WifiConnectionStatus(
    val connected: Boolean,
    val ssid: String?,
    val rssiDbm: Int?,
    val linkSpeedMbps: Int?,
    val ipAddress: String?,
    val gatewayIp: String?
)

@Suppress("DEPRECATION")
fun currentWifiStatus(context: Context): WifiConnectionStatus {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: return WifiConnectionStatus(false, null, null, null, null, null)
    val info = runCatching { wifiManager.connectionInfo }.getOrNull()
    val ssid = info?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    val ip = info?.ipAddress?.takeIf { it != 0 }?.let { intToIpString(it) }
    // Real bug fixed 2026-08-14: the "Router" row in Security > Network Protection used to show
    // a hardcoded "Sky" literal regardless of which network was actually connected - found live
    // when the user was on a friend's "EE" network and it still said "Sky". This reads the real
    // DHCP gateway IP for whatever network is actually connected right now instead.
    val gateway = runCatching { wifiManager.dhcpInfo?.gateway }.getOrNull()
        ?.takeIf { it != 0 }?.let { intToIpString(it) }
    return WifiConnectionStatus(
        connected = ssid != null,
        ssid = ssid,
        rssiDbm = info?.rssi,
        linkSpeedMbps = info?.linkSpeed,
        ipAddress = ip,
        gatewayIp = gateway
    )
}

private fun intToIpString(ipInt: Int): String = listOf(
    ipInt and 0xff,
    ipInt shr 8 and 0xff,
    ipInt shr 16 and 0xff,
    ipInt shr 24 and 0xff
).joinToString(".")

sealed class WifiPasswordResult {
    data class Found(val password: String) : WifiPasswordResult()
    data class Unavailable(val reason: String) : WifiPasswordResult()
}

/**
 * Reads the saved password for the currently-connected network via
 * `WifiManager.getConfiguredNetworks()`'s `preSharedKey` field - a Device Owner app retains
 * access to this call where ordinary apps get an empty list since Android 10. Confirmed live on
 * this device (`adb shell dumpsys wifi`) that this specific exception does NOT recover a real
 * human-readable password for a network added through the normal system Wi-Fi UI - Android's own
 * privileged dumpsys output redacts it to a literal "*" placeholder, and the API field can also
 * come back as an unquoted 64-character hex string, which is the *derived* PSK (a one-way
 * PBKDF2 of the real passphrase + SSID) - a real value, but not the human-readable password, and
 * not reversible back into one. Both cases are detected and reported honestly instead of
 * displaying the wrong thing as if it were the real password.
 */
@Suppress("DEPRECATION")
fun currentWifiPassword(context: Context): WifiPasswordResult {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: return WifiPasswordResult.Unavailable("Couldn't read Wi-Fi state.")
    val currentSsid = runCatching { wifiManager.connectionInfo?.ssid?.trim('"') }.getOrNull()
        ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        ?: return WifiPasswordResult.Unavailable("Not connected to a network.")
    val configured = runCatching { wifiManager.configuredNetworks }.getOrNull()
        ?: return WifiPasswordResult.Unavailable("Couldn't read saved networks.")
    val match = configured.firstOrNull { it.SSID?.trim('"') == currentSsid }
        ?: return WifiPasswordResult.Unavailable("No saved config found for this network.")
    val raw = match.preSharedKey
    return when {
        raw.isNullOrBlank() -> WifiPasswordResult.Unavailable("No saved password on file for this network.")
        raw == "*" -> WifiPasswordResult.Unavailable(
            "Android hides this - it's only readable for networks this app added itself, not ones set up through system Wi-Fi settings."
        )
        !raw.startsWith("\"") && raw.length == 64 && raw.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } ->
            WifiPasswordResult.Unavailable(
                "Only a derived key is stored for this network, not the readable password - it can't be recovered from here."
            )
        else -> WifiPasswordResult.Found(raw.trim('"'))
    }
}
