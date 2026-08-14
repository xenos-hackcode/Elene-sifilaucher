package com.example.scifilauncher

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface

data class NearbyBleDevice(val name: String, val address: String, val rssi: Int)
data class NearbyLanDevice(val ip: String, val hostname: String?)

private const val RECENT_WINDOW_MS = 24L * 60 * 60 * 1000  // 24h - "recent" cutoff for the filter

private fun formatLastSeen(lastSeenMs: Long, isLive: Boolean): String {
    if (isLive) return "LIVE"
    val minutes = (System.currentTimeMillis() - lastSeenMs) / 60000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 1440 -> "${minutes / 60}h ago"
        else -> "${minutes / 1440}d ago"
    }
}

/** Best-effort local discovery, honestly labeled as such: Bluetooth LE devices actually
 * broadcasting nearby, and a ping sweep of this phone's own WiFi subnet. Devices that don't
 * advertise (BLE) or don't answer a ping (LAN) simply won't show up - there is no way to see
 * every device on a network from an unprivileged position on it.
 *
 * Each scan merges into a persisted history (NearbyDeviceHistory) rather than just showing the
 * current scan's raw results - that's what makes "last seen 5m ago" possible at all, and lets
 * ALL/RECENT actually mean something (RECENT = seen in the history within the last 24h, ALL =
 * every device ever recorded, whether or not it answered this specific scan). */
@Composable
fun NearbyDevicesScreen(
    themeColor: Color,
    isDark: Boolean,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var hasScannedOnce by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("ALL") }

    var bleHistory by remember { mutableStateOf(NearbyDeviceHistory.loadAll(context).filter { it.type == NearbyDeviceType.BLUETOOTH }) }
    var lanHistory by remember { mutableStateOf(NearbyDeviceHistory.loadAll(context).filter { it.type == NearbyDeviceType.WIFI }) }
    var liveBleIds by remember { mutableStateOf(setOf<String>()) }
    var liveLanIds by remember { mutableStateOf(setOf<String>()) }
    var expandedId by remember { mutableStateOf<String?>(null) }

    fun startScan() {
        if (!hasPermissions) {
            onRequestPermissions()
            return
        }
        if (scanning) return
        scanning = true
        hasScannedOnce = true
        scope.launch {
            val bleJob = async(Dispatchers.Default) { scanBle(context) }
            val lanJob = async(Dispatchers.Default) { scanLan() }
            val bleResults = bleJob.await()
            val lanResults = lanJob.await()

            val now = System.currentTimeMillis()
            bleResults.forEach { NearbyDeviceHistory.recordSeen(context, it.address, it.name, NearbyDeviceType.BLUETOOTH, now) }
            lanResults.forEach { NearbyDeviceHistory.recordSeen(context, it.ip, it.hostname ?: it.ip, NearbyDeviceType.WIFI, now) }

            liveBleIds = bleResults.map { it.address }.toSet()
            liveLanIds = lanResults.map { it.ip }.toSet()
            val all = NearbyDeviceHistory.loadAll(context)
            bleHistory = all.filter { it.type == NearbyDeviceType.BLUETOOTH }
            lanHistory = all.filter { it.type == NearbyDeviceType.WIFI }
            scanning = false
        }
    }

    val now = System.currentTimeMillis()
    val visibleBle = bleHistory
        .filter { filter == "ALL" || it.id in liveBleIds || (now - it.lastSeenMs) < RECENT_WINDOW_MS }
        .sortedByDescending { if (it.id in liveBleIds) Long.MAX_VALUE else it.lastSeenMs }
    val visibleLan = lanHistory
        .filter { filter == "ALL" || it.id in liveLanIds || (now - it.lastSeenMs) < RECENT_WINDOW_MS }
        .sortedByDescending { if (it.id in liveLanIds) Long.MAX_VALUE else it.lastSeenMs }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDark) Color(0xFF060911) else Color(0xFFEFEFEF))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "◀",
                color = themeColor,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp)
            )
            Text(
                text = "NEARBY DEVICES",
                color = themeColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Best-effort scan: Bluetooth devices broadcasting nearby, plus devices " +
                    "answering on your current WiFi network (this phone's own address excluded). " +
                    "Silent/hidden devices won't appear.",
            color = Color.Gray,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { startScan() },
            enabled = !scanning,
            colors = ButtonDefaults.buttonColors(containerColor = themeColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (scanning) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (!hasPermissions) "GRANT PERMISSIONS TO SCAN" else if (scanning) "SCANNING..." else "SCAN NOW",
                color = Color.Black,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ALL", "RECENT").forEach { option ->
                val active = filter == option
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) themeColor else Color.Gray.copy(alpha = 0.2f))
                        .clickable { filter = option }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        option,
                        color = if (active) Color.Black else Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("BLUETOOTH (${visibleBle.size})", color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(6.dp))
        if (hasScannedOnce && !scanning && visibleBle.isEmpty()) {
            Text("None found.", color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
            items(visibleBle) { d ->
                val isLive = d.id in liveBleIds
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(themeColor.copy(alpha = 0.08f))
                        .clickable { expandedId = if (expandedId == d.id) null else d.id }
                        .padding(10.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(d.name, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            formatLastSeen(d.lastSeenMs, isLive),
                            color = if (isLive) themeColor else Color.Gray,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isLive) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                    if (expandedId == d.id) {
                        Spacer(Modifier.height(4.dp))
                        Text("MAC: ${d.id}", color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("ON YOUR WIFI (${visibleLan.size})", color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(6.dp))
        if (hasScannedOnce && !scanning && visibleLan.isEmpty()) {
            Text("None found.", color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(visibleLan) { d ->
                val isLive = d.id in liveLanIds
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(themeColor.copy(alpha = 0.08f))
                        .clickable { expandedId = if (expandedId == d.id) null else d.id }
                        .padding(10.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(d.name, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            formatLastSeen(d.lastSeenMs, isLive),
                            color = if (isLive) themeColor else Color.Gray,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isLive) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                    if (expandedId == d.id) {
                        Spacer(Modifier.height(4.dp))
                        Text("IP: ${d.id}", color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Suppress("MissingPermission")
private suspend fun scanBle(context: Context): List<NearbyBleDevice> {
    val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
    if (!adapter.isEnabled) return emptyList()
    val scanner = adapter.bluetoothLeScanner ?: return emptyList()
    val found = linkedMapOf<String, NearbyBleDevice>()
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device: BluetoothDevice = result.device
            val name = runCatching { device.name }.getOrNull() ?: "Unnamed device"
            found[device.address] = NearbyBleDevice(name, device.address, result.rssi)
        }
    }
    return runCatching {
        scanner.startScan(callback)
        delay(6000L)
        scanner.stopScan(callback)
        found.values.sortedByDescending { it.rssi }
    }.getOrDefault(emptyList())
}

/** Pings every host in this phone's own /24 in parallel. No ICMP privilege is required on
 * Android - InetAddress.isReachable() falls back to a TCP echo probe when raw ICMP isn't
 * permitted, which is enough to detect most live hosts on a home network.
 *
 * Excludes this phone's own address from the results - found live 2026-08-10: isReachable()
 * on your own IP typically succeeds (loopback-style success), so without this check the phone
 * showed up as one of the "nearby" devices on its own network, which isn't what "nearby" means
 * here. */
private suspend fun scanLan(): List<NearbyLanDevice> = withContext(Dispatchers.IO) {
    val localAddress = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
    }.getOrNull() ?: return@withContext emptyList()

    val base = localAddress.hostAddress?.substringBeforeLast('.') ?: return@withContext emptyList()

    (1..254).map { host ->
        async {
            runCatching {
                val addr = InetAddress.getByName("$base.$host")
                if (addr.hostAddress == localAddress.hostAddress) return@async null
                if (addr.isReachable(400)) {
                    NearbyLanDevice(addr.hostAddress ?: "$base.$host", addr.canonicalHostName.takeIf { it != addr.hostAddress })
                } else null
            }.getOrNull()
        }
    }.awaitAll().filterNotNull()
}
