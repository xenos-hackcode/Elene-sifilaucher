package com.example.scifilauncher

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDark) Color(0xFF050505) else Color(0xFFF5F5F5))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 40.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "< BACK",
                color = themeColor,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(bottom = 12.dp)
            )
            Text(
                text = "DOWNLOAD",
                color = themeColor,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "Two roles for each of these: CONTROLLER is this device, viewing/" +
                        "controlling something you're already paired with (same screen the " +
                        "Quick Settings tile opens). CONTROLLED is the app you hand to the " +
                        "other PC or phone so it can be viewed/controlled - always their " +
                        "choice to install it, never silent.",
                color = Color.Gray,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            DownloadSection(
                title = "LINK TO PC",
                themeColor = themeColor,
                isDark = isDark,
                onOpenController = onOpenLaptopControl,
                onShareControlled = { shareLink(LINK_TO_PC_URL, "Xenos Link to PC") },
                controlledNote = "A Windows program (.exe) - no install needed, just run it. " +
                        "It can optionally be set up to run in the background and start at login."
            )

            Spacer(Modifier.height(20.dp))

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

            Spacer(Modifier.height(20.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Gray.copy(alpha = 0.08f))
                    .padding(14.dp)
            ) {
                Text(
                    text = "STANDALONE CONTROLLER APP",
                    color = themeColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "A separate, lightweight Android app that can act as CONTROLLER for " +
                            "both Link to PC and Link to Phone - for someone who wants to " +
                            "view/control a paired device without installing the full Xenos " +
                            "launcher. Handles both roles in one small app.",
                    color = Color.Gray,
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Gray.copy(alpha = 0.08f))
            .padding(14.dp)
    ) {
        Text(
            text = title,
            color = themeColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(12.dp))

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
                    color = if (isDark) Color.White else Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "View/control something you're already paired with",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(">", color = themeColor, fontSize = 15.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(10.dp))

        Text(
            "CONTROLLED (share this app)",
            color = if (isDark) Color.White else Color.Black,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(4.dp))
        Text(controlledNote, color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
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
