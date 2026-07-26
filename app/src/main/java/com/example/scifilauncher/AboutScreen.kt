package com.example.scifilauncher

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.clickable

private const val PRIVACY_POLICY_URL = "https://xenos-hackcode.github.io/scifilauncher-privacy/"
private const val GITHUB_URL = "https://github.com/xenos-hackcode"

@Composable
fun AboutScreen(
    themeColor: Color,
    isDark: Boolean,
    batteryMode: BatterySaverMode,
    fontSize: Float,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val baseFontSize = fontSize.sp

    Box(modifier = Modifier.fillMaxSize()) {
        PanelBackdrop(isDark = isDark)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 40.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "< DASH",
                color = themeColor,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .clickable { onBack() }
            )

            Text(
                text = "SciFiLauncher – Xenos",
                color = themeColor,
                fontSize = (baseFontSize.value + 6).sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Version 1.0 · Developer: Xenos",
                color = Color.Gray,
                fontSize = (baseFontSize.value - 1).sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "A custom Android home-screen launcher built around Elene, an on-device " +
                        "voice-first AI assistant. Below is a full, honest list of what the app " +
                        "can currently do.",
                color = Color.White,
                fontSize = baseFontSize,
                fontFamily = FontFamily.Monospace
            )

            Spacer(Modifier.height(4.dp))

            PanelSection(title = "LAUNCHER", themeColor = themeColor) {
                AboutBullet("Can be set as your default home screen, with a full app drawer, paging, favorites, and recents.", baseFontSize)
                AboutBullet("Hide apps, lock apps behind a PIN, or freeze (suspend) apps you don't want running.", baseFontSize)
                AboutBullet("Multiple color themes with optional auto-cycling, dark/light mode, and adjustable font size.", baseFontSize)
                AboutBullet("Custom on-screen keyboards (Xenos, Cedal styles) alongside the normal system keyboard.", baseFontSize)
                AboutBullet("Interface language: English, Yoruba, Mandarin, Korean, French, Spanish, or German.", baseFontSize, showDivider = false)
            }

            PanelSection(title = "ELENE (VOICE ASSISTANT)", themeColor = themeColor) {
                AboutBullet("Voice-first: tap the bubble and speak. Replies are read aloud (ElevenLabs voice, with an on-device fallback if that's unreachable).", baseFontSize)
                AboutBullet("Opens apps, opens the launcher's own pages, or opens real Android system settings (kept clearly separate from the app's own settings).", baseFontSize)
                AboutBullet("With Accessibility permission enabled, can scroll, go back/home, open recents, and tap or highlight things on screen by name.", baseFontSize)
                AboutBullet("Turns the flashlight on/off, turns Bluetooth on in one tap (turning it off needs one manual tap - Android doesn't allow apps to do that silently), and tells you the time in other timezones, computed on-device.", baseFontSize)
                AboutBullet("Can scan and summarize devices on your wifi network.", baseFontSize)
                AboutBullet("Remembers recent turns of your conversation, and remembers topics you've asked it not to bring up again.", baseFontSize)
                AboutBullet("Addresses you as \"Emperor\" by default, or \"Xenos\" when you mention someone else is around.", baseFontSize, showDivider = false)
            }

            PanelSection(title = "SECURITY CENTER", themeColor = themeColor) {
                AboutBullet("App PIN lock with a recovery question, plus per-app locking, hiding, and freezing.", baseFontSize)
                AboutBullet("Sequence Mode: an anti-theft system that can require biometric confirmation on suspicious access, trigger an OS-level lockdown via Device Admin, capture last-known location, and optionally wipe the device if never recovered.", baseFontSize)
                AboutBullet("Local tracker & ad blocking via an on-device DNS filter - no root and no external server involved.", baseFontSize)
                AboutBullet("Cedal Shared System (off by default): lets this app exchange basic version/config info with other apps you've installed that are also made by Cedal, verified by matching digital signature so no other app can use the channel. No personal data is shared.", baseFontSize, showDivider = false)
            }

            PanelSection(title = "WHAT IT DOESN'T DO", themeColor = themeColor) {
                AboutBullet("No root access, no ability to power off or reboot the phone, and no access to other apps' private data - all of this is blocked by Android for every app, including this one.", baseFontSize, showDivider = false)
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "Contact: cedalstar@gmail.com",
                color = Color.Gray,
                fontSize = (baseFontSize.value - 1).sp,
                fontFamily = FontFamily.Monospace
            )

            Spacer(Modifier.height(12.dp))

            PanelSection(title = "MORE", themeColor = themeColor) {
                PanelRow(
                    label = "Privacy policy",
                    themeColor = themeColor,
                    onClick = {
                        openUrlInPreferredBrowser(context, PRIVACY_POLICY_URL)
                    }
                )
                PanelRow(
                    label = "Email developer",
                    themeColor = themeColor,
                    onClick = {
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:cedalstar@gmail.com")
                            putExtra(Intent.EXTRA_SUBJECT, "Feedback: SciFiLauncher – Xenos")
                        }
                        context.startActivity(intent)
                    }
                )
                PanelRow(
                    label = "GitHub",
                    themeColor = themeColor,
                    showDivider = false,
                    onClick = {
                        openUrlInPreferredBrowser(context, GITHUB_URL)
                    }
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AboutBullet(text: String, fontSize: androidx.compose.ui.unit.TextUnit, showDivider: Boolean = true) {
    Column {
        Text(
            text = "•  $text",
            color = Color.White,
            fontSize = fontSize,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
        if (showDivider) PanelDivider(Color.Gray)
    }
}
