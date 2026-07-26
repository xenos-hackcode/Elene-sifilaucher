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

/** Best-effort local discovery, honestly labeled as such: Bluetooth LE devices actually
 * broadcasting nearby, and a ping sweep of this phone's own WiFi subnet. Devices that don't
 * advertise (BLE) or don't answer a ping (LAN) simply won't show up - there is no way to see
 * every device on a network from an unprivileged position on it. */
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
    var bleResults by remember { mutableStateOf(listOf<NearbyBleDevice>()) }
    var lanResults by remember { mutableStateOf(listOf<NearbyLanDevice>()) }
    var hasScannedOnce by remember { mutableStateOf(false) }

    fun startScan() {
        if (!hasPermissions) {
            onRequestPermissions()
            return
        }
        if (scanning) return
        scanning = true
        hasScannedOnce = true
        bleResults = emptyList()
        lanResults = emptyList()
        scope.launch {
            val bleJob = async(Dispatchers.Default) { scanBle(context) }
            val lanJob = async(Dispatchers.Default) { scanLan() }
            bleResults = bleJob.await()
            lanResults = lanJob.await()
            scanning = false
        }
    }

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
                    "answering on your current WiFi network. Silent/hidden devices won't appear.",
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

        Spacer(Modifier.height(20.dp))
        Text("BLUETOOTH (${bleResults.size})", color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(6.dp))
        if (hasScannedOnce && !scanning && bleResults.isEmpty()) {
            Text("None found.", color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
            items(bleResults) { d ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(themeColor.copy(alpha = 0.08f))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(d.name, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Text("${d.rssi} dBm", color = themeColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("ON YOUR WIFI (${lanResults.size})", color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(6.dp))
        if (hasScannedOnce && !scanning && lanResults.isEmpty()) {
            Text("None found.", color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(lanResults) { d ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(themeColor.copy(alpha = 0.08f))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(d.hostname ?: "Unknown device", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Text(d.ip, color = themeColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
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
 * permitted, which is enough to detect most live hosts on a home network. */
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
                if (addr.isReachable(400)) {
                    NearbyLanDevice(addr.hostAddress ?: "$base.$host", addr.canonicalHostName.takeIf { it != addr.hostAddress })
                } else null
            }.getOrNull()
        }
    }.awaitAll().filterNotNull()
}
