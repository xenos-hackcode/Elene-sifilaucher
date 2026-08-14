package com.example.scifilauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Where turning a capability on/off actually happens - kept as its own field, not just
 * folded into the explanation text, because the user specifically wants this distinction
 * visible at a glance: the phone's own Settings app vs. this app's own Settings/Security. */
enum class ControlSurface(val label: String) {
    PHONE_SETTINGS("PHONE SETTINGS"),
    APP_SETTINGS("OUR SETTINGS"),
    NOT_TOGGLABLE("ALWAYS ON")
}

data class CapabilityInfo(
    val name: String,
    val explanation: String,
    val controlSurface: ControlSurface,
    val whereExactly: String
)

private val CAPABILITIES = listOf(
    CapabilityInfo(
        "Microphone", "Lets you talk to Xenos by voice, and captures your spoken passphrase for 2-Step Verify.",
        ControlSurface.PHONE_SETTINGS, "Settings > Apps > SciFiLauncher > Permissions > Microphone"
    ),
    CapabilityInfo(
        "Camera", "Powers the QR scanner (laptop pairing, quick controls) and takes a photo of anyone who fails to unlock this phone.",
        ControlSurface.PHONE_SETTINGS, "Settings > Apps > SciFiLauncher > Permissions > Camera"
    ),
    CapabilityInfo(
        "Contacts", "Lets Xenos work out who a message or reply is meant for when you ask him to read or respond to notifications.",
        ControlSurface.PHONE_SETTINGS, "Settings > Apps > SciFiLauncher > Permissions > Contacts"
    ),
    CapabilityInfo(
        "SMS", "Sends a Sequence Mode anti-theft alert by text message to resolved family contacts, alongside a WhatsApp attempt - SMS doesn't need WhatsApp installed, the Accessibility service, or any internet connection to go through.",
        ControlSurface.PHONE_SETTINGS, "Settings > Apps > SciFiLauncher > Permissions > SMS"
    ),
    CapabilityInfo(
        "Location", "Used by Sequence Mode to record the device's location if it's ever remotely locked or wiped, and by Nearby Devices to scan your WiFi network.",
        ControlSurface.PHONE_SETTINGS, "Settings > Apps > SciFiLauncher > Permissions > Location"
    ),
    CapabilityInfo(
        "Nearby devices (Bluetooth)", "Lets the Bluetooth quick-toggle actually read and turn on Bluetooth, and lets Nearby Devices discover Bluetooth devices around you.",
        ControlSurface.PHONE_SETTINGS, "Settings > Apps > SciFiLauncher > Permissions > Nearby devices"
    ),
    CapabilityInfo(
        "Notifications (this app's own)", "Needed for this app to show its own notifications - a flagged new install, a scheduled action completing, screen recording status.",
        ControlSurface.PHONE_SETTINGS, "Settings > Apps > SciFiLauncher > Permissions > Notifications"
    ),
    CapabilityInfo(
        "Files & storage", "Powers the built-in File Manager and saves screen recordings to your Movies folder.",
        ControlSurface.PHONE_SETTINGS, "Settings > Apps > SciFiLauncher > Permissions > Files and media"
    ),
    CapabilityInfo(
        "Full network access / view WiFi & network state", "Lets Xenos reach his backend AI service, and lets tracker blocking and Nearby Devices see your network state. Android doesn't let this be toggled per-app.",
        ControlSurface.NOT_TOGGLABLE, "Not a Settings toggle - granted at install"
    ),
    CapabilityInfo(
        "Request deleting other apps", "Lets this app's own uninstall button, and voice-scheduled uninstalls, remove other apps - always through this app's own confirmation, never Android's uninstall dialog.",
        ControlSurface.APP_SETTINGS, "Every uninstall is approved individually - see Security > Requests"
    ),
    CapabilityInfo(
        "Notification access", "Lets the custom Notifications panel actually see your phone's notifications - a special permission Android only grants from its own Settings, never a normal permission popup.",
        ControlSurface.PHONE_SETTINGS, "Settings > Apps > Special app access > Notification access"
    ),
    CapabilityInfo(
        "Display over other apps", "Powers the Eye Comfort Shield screen tint and Xenos's floating bubble when you're inside another app.",
        ControlSurface.PHONE_SETTINGS, "Settings > Apps > SciFiLauncher > Display over other apps"
    ),
    CapabilityInfo(
        "Modify system settings", "Lets the brightness slider in Quick Settings actually change your screen brightness.",
        ControlSurface.PHONE_SETTINGS, "Settings > Apps > Special app access > Modify system settings"
    ),
    CapabilityInfo(
        "Run a foreground service / screenshot & screen recording", "Required by Android whenever Screen Record is active, and whenever this app captures the screen, so it isn't killed mid-recording. Also keeps the device awake while actively recording.",
        ControlSurface.NOT_TOGGLABLE, "Tied to using Screen Record in Quick Settings - not a separate toggle"
    ),
    CapabilityInfo(
        "Set Cedal SMS to ignore battery optimization", "Lets this app open Android's real \"let it run in background\" dialog on behalf of Cedal SMS, so it can keep relaying messages reliably.",
        ControlSurface.APP_SETTINGS, "Security > \"Watch new installs\" flow, or Android's own Battery settings for Cedal SMS"
    ),
    CapabilityInfo(
        "Alarms & reminders", "Powers scheduled actions - \"uninstall this in 2 hours\", \"download that in 30 minutes\" - so they fire at the right time even if the app isn't open.",
        ControlSurface.PHONE_SETTINGS, "Settings > Apps > Special app access > Alarms & reminders (Device Owner apps are auto-granted this)"
    ),
    CapabilityInfo(
        "Fingerprint / biometric hardware", "Used for the fingerprint check on this app's own lock screen, app locks, and 2-Step Verify.",
        ControlSurface.APP_SETTINGS, "Security > 2-Step Verify, app lock PIN setup (your fingerprint itself is enrolled at the phone level)"
    ),
    CapabilityInfo(
        "Device Owner / Device Admin", "The deepest permission this app has - manages the whole device: Kiosk mode, Sequence Mode remote lock/wipe, silently installing or uninstalling apps, enforcing the lock screen. This is what makes this your phone's management app, not just a regular launcher.",
        ControlSurface.NOT_TOGGLABLE, "Set once during initial device setup - not a Settings toggle"
    ),
    CapabilityInfo(
        "VPN (local only)", "Powers on-device tracker/ad blocking - filters known tracker domains locally. Nothing is routed through a remote server.",
        ControlSurface.APP_SETTINGS, "Security > Tracker blocking"
    ),
    CapabilityInfo(
        "Accessibility service", "Lets Xenos read, scroll, click, and highlight things on screen when you ask him to - and lets him talk to you from a floating bubble while you're inside another app.",
        ControlSurface.PHONE_SETTINGS, "Settings > Accessibility > SciFi Xenos"
    ),
    CapabilityInfo(
        "Run at startup", "No special permission needed for this - it's your phone's Home app, so Android starts it automatically on every boot, same as any launcher.",
        ControlSurface.NOT_TOGGLABLE, "Inherent to being the default launcher"
    ),
    CapabilityInfo(
        "Prevent the phone from sleeping", "Held only while Screen Record is actively running, so an active recording isn't cut off if the screen would otherwise dim or sleep. Released the moment recording stops.",
        ControlSurface.NOT_TOGGLABLE, "Tied to using Screen Record in Quick Settings - not a separate toggle"
    )
)

@Composable
fun CapabilitiesScreen(
    themeColor: Color,
    isDark: Boolean,
    onBack: () -> Unit,
    onOpenDownload: () -> Unit
) {
    var infoFor by remember { mutableStateOf<CapabilityInfo?>(null) }

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
                text = "WHAT THIS APP CAN DO",
                color = themeColor,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "Every permission this app holds, what it's actually used for, and where " +
                        "turning it on/off happens - the phone's real Settings app, or this app's " +
                        "own Settings/Security. Tap (i) on anything for the full explanation.",
                color = Color.Gray,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(themeColor.copy(alpha = 0.14f))
                    .clickable(onClick = onOpenDownload)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Download",
                        color = if (isDark) Color.White else Color.Black,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Link to PC / Link to Phone - get or share the agent apps",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    text = ">",
                    color = themeColor,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(CAPABILITIES) { cap ->
                    CapabilityRow(cap, themeColor, isDark) { infoFor = cap }
                }
            }
        }

        infoFor?.let { cap ->
            AlertDialog(
                onDismissRequest = { infoFor = null },
                confirmButton = {
                    TextButton(onClick = { infoFor = null }) {
                        Text("CLOSE", color = themeColor, fontFamily = FontFamily.Monospace)
                    }
                },
                title = {
                    Text(cap.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column {
                        Text(cap.explanation, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "CONTROLLED VIA: ${cap.controlSurface.label}",
                            color = themeColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(cap.whereExactly, color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }
    }
}

@Composable
private fun CapabilityRow(cap: CapabilityInfo, themeColor: Color, isDark: Boolean, onInfoClick: () -> Unit) {
    val badgeColor = when (cap.controlSurface) {
        ControlSurface.PHONE_SETTINGS -> Color(0xFF64B5F6)
        ControlSurface.APP_SETTINGS -> themeColor
        ControlSurface.NOT_TOGGLABLE -> Color.Gray
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Gray.copy(alpha = 0.08f))
            .clickable(onClick = onInfoClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = cap.name,
                color = if (isDark) Color.White else Color.Black,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(badgeColor.copy(alpha = 0.18f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(cap.controlSurface.label, color = badgeColor, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Text(
            text = "(i)",
            color = themeColor,
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}
