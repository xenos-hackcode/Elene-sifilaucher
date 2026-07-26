package com.example.scifilauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shown before any device-owner-level action (install, uninstall, force-stop, permission
 * grant, etc.) actually happens. Every request is logged regardless of outcome - see
 * ActionLog/RequestsScreen. Never auto-approves: if nothing happens, the caller snoozes it
 * on a timer rather than treating silence as consent.
 *
 * [allowDelay] adds a "do this later instead" picker - only meaningful for actions that have
 * a real scheduled-execution path behind them (see MainActivity.scheduleAction), not every
 * action passed through here. */
@Composable
fun DeviceActionConfirmationPanel(
    themeColor: Color,
    isDark: Boolean,
    actionLabel: String,
    reason: String,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onAskLater: () -> Unit,
    onVoiceTap: () -> Unit,
    allowDelay: Boolean = false,
    onYesDelayed: (delayMinutes: Int) -> Unit = {}
) {
    var hoursInput by remember { mutableStateOf("") }
    var minutesInput by remember { mutableStateOf("") }
    val delayMinutes = (hoursInput.toIntOrNull() ?: 0) * 60 + (minutesInput.toIntOrNull() ?: 0)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .clip(RoundedCornerShape(16.dp))
                .background(if (isDark) Color(0xFF0B0F18) else Color(0xFFEFEFEF))
                .padding(20.dp)
        ) {
            Text(
                text = "PERMISSION REQUEST",
                color = themeColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = actionLabel,
                color = Color.White,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = reason,
                color = Color.Gray,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Approving this always requires your fingerprint - saying or tapping \"yes\" opens that prompt, it doesn't approve by itself.",
                color = themeColor.copy(alpha = 0.6f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )

            if (allowDelay) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "DO THIS LATER INSTEAD? (optional)",
                    color = themeColor.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = hoursInput,
                        onValueChange = { v -> hoursInput = v.filter { it.isDigit() }.take(3) },
                        label = { Text("hrs", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = minutesInput,
                        onValueChange = { v -> minutesInput = v.filter { it.isDigit() }.take(3) },
                        label = { Text("mins", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ConfirmButton(
                    label = if (delayMinutes > 0) "YES, IN ${formatMinutesShort(delayMinutes)} (FINGERPRINT)" else "YES (FINGERPRINT)",
                    color = themeColor,
                    modifier = Modifier.weight(1f),
                    filled = true,
                    onClick = { if (delayMinutes > 0) onYesDelayed(delayMinutes) else onYes() }
                )
                ConfirmButton("NO", Color.Gray, Modifier.weight(1f), filled = false, onClick = onNo)
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = "ASK AGAIN LATER",
                color = themeColor.copy(alpha = 0.7f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAskLater)
                    .padding(vertical = 8.dp)
            )

            Text(
                text = "🎤 SPEAK YOUR ANSWER",
                color = themeColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onVoiceTap)
                    .padding(vertical = 8.dp)
            )
        }
    }
}

private fun formatMinutesShort(totalMinutes: Int): String {
    val hrs = totalMinutes / 60
    val mins = totalMinutes % 60
    return when {
        hrs > 0 && mins > 0 -> "${hrs}h ${mins}m"
        hrs > 0 -> "${hrs}h"
        else -> "${mins}m"
    }
}

@Composable
private fun ConfirmButton(
    label: String,
    color: Color,
    modifier: Modifier,
    filled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (filled) color else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (filled) Color.Black else color,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}
