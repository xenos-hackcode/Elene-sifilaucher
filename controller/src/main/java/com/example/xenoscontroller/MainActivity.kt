package com.example.xenoscontroller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val PREFS = "xenos_controller_prefs"
private val THEME_COLOR = Color(0xFF00E5A0)

/** Standalone "controller" role, split out of the main SciFiLauncher/Xenos app - lets someone
 * view/control a paired PC or phone (already running the Link to PC exe or Link to Phone agent
 * app) without installing the full launcher. Deliberately minimal: no Device Admin, no
 * Accessibility Service, no HOME category - it never captures or controls THIS device, it only
 * ever sends commands to and renders frames from something else already running the agent side.
 * Reuses the exact same wire protocol/backend relay as the main app's own controller screens. */
class MainActivity : ComponentActivity() {

    // Same pattern as the main app's own QR scanning: one shared launcher, the caller supplies
    // a callback for where the scanned text should go.
    private var qrScanResultCallback: ((String) -> Unit)? = null

    private val qrScanLauncher = registerForActivityResult(
        com.journeyapps.barcodescanner.ScanContract()
    ) { result ->
        val text = result.contents
        val callback = qrScanResultCallback
        qrScanResultCallback = null
        if (!text.isNullOrBlank()) callback?.invoke(text)
    }

    private fun launchQrScan(onResult: (String) -> Unit) {
        qrScanResultCallback = onResult
        runCatching {
            qrScanLauncher.launch(
                com.journeyapps.barcodescanner.ScanOptions()
                    .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        }.onFailure { qrScanResultCallback = null }
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun loadLaptopToken(): String? = prefs().getString("laptop_token", null)
    private fun saveLaptopToken(token: String) { prefs().edit().putString("laptop_token", token).apply() }
    private fun forgetLaptopToken() { prefs().edit().remove("laptop_token").apply() }

    private fun loadPhoneToken(): String? = prefs().getString("phone_token", null)
    private fun savePhoneToken(token: String) { prefs().edit().putString("phone_token", token).apply() }
    private fun forgetPhoneToken() { prefs().edit().remove("phone_token").apply() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val isDark = isSystemInDarkTheme()
            var screen by rememberSaveable { mutableStateOf("home") }
            var laptopToken by rememberSaveable { mutableStateOf(loadLaptopToken()) }
            var phoneToken by rememberSaveable { mutableStateOf(loadPhoneToken()) }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (screen) {
                        "laptop" -> LaptopControlScreen(
                            themeColor = THEME_COLOR,
                            isDark = isDark,
                            savedToken = laptopToken,
                            onSaveToken = { token -> saveLaptopToken(token); laptopToken = token },
                            onForgetToken = { forgetLaptopToken(); laptopToken = null },
                            onScanQr = { onResult -> launchQrScan(onResult) },
                            onBack = { screen = "home" }
                        )
                        "phone" -> PhoneControlScreen(
                            themeColor = THEME_COLOR,
                            isDark = isDark,
                            savedToken = phoneToken,
                            onSaveToken = { token -> savePhoneToken(token); phoneToken = token },
                            onForgetToken = { forgetPhoneToken(); phoneToken = null },
                            onScanQr = { onResult -> launchQrScan(onResult) },
                            onBack = { screen = "home" }
                        )
                        else -> HomeScreen(
                            isDark = isDark,
                            onOpenLaptop = { screen = "laptop" },
                            onOpenPhone = { screen = "phone" }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(isDark: Boolean, onOpenLaptop: () -> Unit, onOpenPhone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDark) Color(0xFF050505) else Color(0xFFF5F5F5))
            .systemBarsPadding()
            .padding(20.dp)
    ) {
        Text(
            text = "XENOS CONTROLLER",
            color = THEME_COLOR,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "View and control a PC or phone that's already running the matching " +
                    "Xenos agent app. This app only ever connects OUT to something else - it " +
                    "never captures or controls this device.",
            color = Color.Gray,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(24.dp))

        listOf("LINK TO PC" to onOpenLaptop, "LINK TO PHONE" to onOpenPhone).forEach { (label, action) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Gray.copy(alpha = 0.1f))
                    .clickable(onClick = action)
                    .padding(16.dp)
            ) {
                Text(
                    text = label,
                    color = if (isDark) Color.White else Color.Black,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                Text(">", color = THEME_COLOR, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }
}
