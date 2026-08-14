package com.example.phonelinkagent

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class PhoneLinkAgentStatus { IDLE, WAITING_FOR_CONTROLLER, ACTIVE, PAUSED }

/**
 * Setup + live status for this phone being controlled by another (paired) phone. Disclosure
 * screen is mandatory and comes before anything else - no pairing token or permission prompt is
 * reachable until it's acknowledged. Accessibility Service and MediaProjection consent are both
 * real system-gated grants (deep-link to Settings / the system's own consent dialog) - this
 * screen never bypasses either.
 */
@Composable
fun PhoneLinkAgentScreen(
    isDark: Boolean,
    disclosureAcknowledged: Boolean,
    onAcknowledgeDisclosure: () -> Unit,
    accessibilityEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    batteryUnrestricted: Boolean,
    onRequestBatteryUnrestricted: () -> Unit,
    token: String,
    status: PhoneLinkAgentStatus,
    onStart: () -> Unit,
    onResume: () -> Unit,
    onLogOut: () -> Unit
) {
    val themeColor = Color(0xFF00E5A0)
    val bgColor = if (isDark) Color(0xFF060911) else Color(0xFFEFEFEF)
    val textColor = if (isDark) Color.White else Color.Black

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .systemBarsPadding()
            .padding(start = 18.dp, end = 18.dp, bottom = 18.dp, top = 40.dp)
    ) {
        Text(
            text = "PHONE LINK AGENT",
            color = themeColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(16.dp))

        when {
            !disclosureAcknowledged -> DisclosureStep(themeColor, textColor, onAcknowledgeDisclosure)
            !accessibilityEnabled -> AccessibilityStep(themeColor, textColor, onOpenAccessibilitySettings)
            !batteryUnrestricted -> BatteryStep(themeColor, textColor, onRequestBatteryUnrestricted)
            status == PhoneLinkAgentStatus.IDLE -> PairingStep(themeColor, textColor, token, onStart)
            else -> LiveStatusStep(themeColor, textColor, token, status, onResume, onLogOut)
        }
    }
}

@Composable
private fun DisclosureStep(themeColor: Color, textColor: Color, onAcknowledge: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("⚠", color = themeColor, fontSize = 40.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "This allows a linked device to view and control this phone",
            color = textColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Once set up, whoever holds the pairing code can see this phone's screen live " +
                "and tap, swipe, and type on it remotely - the same as if they were holding it. " +
                "Setup requires you to separately grant real Android permissions (Accessibility " +
                "Service, screen-sharing) - nothing here is silent or automatic. You can " +
                "disconnect and invalidate the pairing code at any time from the LOG OUT button " +
                "in this app.",
            color = Color.Gray,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onAcknowledge,
            colors = ButtonDefaults.buttonColors(containerColor = themeColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("I UNDERSTAND, CONTINUE", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AccessibilityStep(themeColor: Color, textColor: Color, onOpenSettings: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Accessibility Service needed",
            color = textColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "The linked device's taps and swipes are carried out through this app's " +
                "Accessibility Service. It has to be turned on manually in Android's own " +
                "Settings; this app can't do it for you.",
            color = Color.Gray,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onOpenSettings,
            colors = ButtonDefaults.buttonColors(containerColor = themeColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("OPEN ACCESSIBILITY SETTINGS", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BatteryStep(themeColor: Color, textColor: Color, onRequest: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "One more permission needed",
            color = textColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Without this, Android (especially on some phone brands) can kill this app " +
                "in the background even while it's actively linked - cutting off the session " +
                "the moment you swipe this app away or lock the screen. This exempts it from " +
                "battery optimization so a live session actually stays live.",
            color = Color.Gray,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onRequest,
            colors = ButtonDefaults.buttonColors(containerColor = themeColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("ALLOW BACKGROUND ACTIVITY", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PairingStep(themeColor: Color, textColor: Color, token: String, onStart: () -> Unit) {
    val qrBitmap = remember(token) { generateQrBitmap(token) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Ready to link",
            color = textColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Scan this on the controlling phone's \"Link to Phone\" screen, or enter the " +
                "code manually. Keep it private - it's the only thing gating who can view/control " +
                "this phone.",
            color = Color.Gray,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(16.dp))
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .padding(8.dp)
            )
            Spacer(Modifier.height(12.dp))
        }
        Text(text = token, color = themeColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onStart,
            colors = ButtonDefaults.buttonColors(containerColor = themeColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("START", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LiveStatusStep(
    themeColor: Color,
    textColor: Color,
    token: String,
    status: PhoneLinkAgentStatus,
    onResume: () -> Unit,
    onLogOut: () -> Unit
) {
    val statusText = when (status) {
        PhoneLinkAgentStatus.ACTIVE -> "Connected - linked device can see and control this phone"
        PhoneLinkAgentStatus.PAUSED -> "Paused - screen was turned off. Check the notification to resume."
        else -> "Waiting for the linked device to connect..."
    }
    val statusColor = when (status) {
        PhoneLinkAgentStatus.ACTIVE -> Color(0xFFFF5252)
        PhoneLinkAgentStatus.PAUSED -> Color(0xFFFFA726)
        else -> Color.Gray
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(statusText, color = statusColor, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(text = token, color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(24.dp))
        if (status == PhoneLinkAgentStatus.PAUSED) {
            Button(
                onClick = onResume,
                colors = ButtonDefaults.buttonColors(containerColor = themeColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("RESUME NOW", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
        }
        Button(
            onClick = onLogOut,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252).copy(alpha = 0.85f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("LOG OUT", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}
