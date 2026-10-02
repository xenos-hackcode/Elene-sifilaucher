package com.example.scifilauncher

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val LINK_TO_PC_URL = "https://storage.googleapis.com/cedal-fd4a2-link-downloads/XenosLinkToPC.exe"
private const val LINK_TO_PHONE_URL = "https://storage.googleapis.com/cedal-fd4a2-link-downloads/XenosLinkToPhone.apk"
private const val CONTROLLER_APP_URL = "https://storage.googleapis.com/cedal-fd4a2-link-downloads/XenosController.apk"

/** The place to actually GET the two "give this to someone else" agent apps, separate from
 * the Quick Settings tiles (which are the CONTROLLER role - viewing/controlling something
 * you're already paired with). This screen is about the other half of each pairing: sharing
 * the CONTROLLED-side app to whoever's PC/phone you want access to, plus a quick way back into
 * the controller screens themselves so both roles live in one place. */
@Composable
fun DownloadScreen(
    themeColor: Color,
    isDark: Boolean,
    onOpenLaptopControl: () -> Unit,
    onOpenPhoneControl: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    fun shareLink(url: String, label: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, label)
            putExtra(
                Intent.EXTRA_TEXT,
                "$label - download and run this to let me view/control this device:\n\n$url"
            )
        }
        context.startActivity(Intent.createChooser(intent, "Share $label"))
    }

    TerminalScaffold(
        themeColor = themeColor,
        code = "DOWNLOAD",
        title = "Download",
        subtitle = "Two roles for each of these: CONTROLLER is this device, viewing/controlling something you're already paired with. CONTROLLED is the app you hand to the other PC or phone - always their choice to install it, never silent.",
        backLabel = "‹  SETTINGS",
        onBack = onBack
    ) {
        DownloadSection(
            title = "LINK TO PC",
            themeColor = themeColor,
            isDark = isDark,
            onOpenController = onOpenLaptopControl,
            onShareControlled = { shareLink(LINK_TO_PC_URL, "Xenos Link to PC") },
            controlledNote = "A Windows program (.exe) - no install needed, just run it. " +
                    "It can optionally be set up to run in the background and start at login."
        )

        DownloadSection(
            title = "LINK TO PHONE",
            themeColor = themeColor,
            isDark = isDark,
            onOpenController = onOpenPhoneControl,
            onShareControlled = { shareLink(LINK_TO_PHONE_URL, "Xenos Link to Phone") },
            controlledNote = "A separate Android app (.apk) - the other phone installs it " +
                    "like any sideloaded app, with its own clear disclosure screen before " +
                    "it does anything."
        )

        TerminalCard(label = "STANDALONE CONTROLLER APP", themeColor = themeColor) {
            Text(
                text = "A separate, lightweight Android app that can act as CONTROLLER for " +
                        "both Link to PC and Link to Phone - for someone who wants to " +
                        "view/control a paired device without installing the full Xenos " +
                        "launcher. Handles both roles in one small app.",
                color = TerminalStyle.muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { shareLink(CONTROLLER_APP_URL, "Xenos Controller") },
                colors = ButtonDefaults.buttonColors(containerColor = themeColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("SHARE CONTROLLER APP LINK", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DownloadSection(
    title: String,
    themeColor: Color,
    isDark: Boolean,
    onOpenController: () -> Unit,
    onShareControlled: () -> Unit,
    controlledNote: String
) {
    TerminalCard(label = title, themeColor = themeColor) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Gray.copy(alpha = 0.10f))
                .clickable(onClick = onOpenController)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "CONTROLLER (this device)",
                    color = TerminalStyle.ink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "View/control something you're already paired with",
                    color = TerminalStyle.muted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(">", color = themeColor, fontSize = 15.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(10.dp))

        Text(
            "CONTROLLED (share this app)",
            color = TerminalStyle.ink,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(4.dp))
        Text(controlledNote, color = TerminalStyle.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onShareControlled,
            colors = ButtonDefaults.buttonColors(containerColor = themeColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("SHARE DOWNLOAD LINK", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}
