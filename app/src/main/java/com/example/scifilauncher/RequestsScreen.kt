package com.example.scifilauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Every device-owner-level action this app has ever asked to take, and how it was
 * resolved - accepted, denied, snoozed, or still pending. */
@Composable
fun RequestsScreen(
    themeColor: Color,
    isDark: Boolean,
    entries: List<ActionRequestEntry>,
    onBack: () -> Unit
) {
    var filter by remember { mutableStateOf<ActionRequestStatus?>(null) }
    val filtered = remember(entries, filter) {
        if (filter == null) entries else entries.filter { it.status == filter }
    }

    TerminalScaffold(
        themeColor = themeColor,
        code = "REQUESTS",
        title = "Requests",
        subtitle = "Every device-owner-level action this app has ever asked to take, and how it was resolved.",
        backLabel = "‹  BACK",
        onBack = onBack
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip("ALL", filter == null, themeColor) { filter = null }
            FilterChip("PENDING", filter == ActionRequestStatus.PENDING, themeColor) { filter = ActionRequestStatus.PENDING }
            FilterChip("APPROVED", filter == ActionRequestStatus.APPROVED, themeColor) { filter = ActionRequestStatus.APPROVED }
            FilterChip("DENIED", filter == ActionRequestStatus.DENIED, themeColor) { filter = ActionRequestStatus.DENIED }
            FilterChip("SNOOZED", filter == ActionRequestStatus.SNOOZED, themeColor) { filter = ActionRequestStatus.SNOOZED }
        }
        TerminalCard(label = "REQUESTS (${filtered.size})", themeColor = themeColor) {
            if (filtered.isEmpty()) {
                Text(
                    text = "No requests yet.",
                    color = TerminalStyle.muted,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                filtered.forEach { entry ->
                    RequestRow(entry, themeColor, TerminalStyle.ink)
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, active: Boolean, themeColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) themeColor else Color.Gray.copy(alpha = 0.2f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (active) Color.Black else Color.White,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun RequestRow(entry: ActionRequestEntry, themeColor: Color, textColor: Color) {
    val statusColor = when (entry.status) {
        ActionRequestStatus.APPROVED -> Color(0xFF00E676)
        ActionRequestStatus.DENIED -> Color.Red
        ActionRequestStatus.SNOOZED -> Color(0xFFFFC107)
        ActionRequestStatus.PENDING -> themeColor
    }
    val sdf = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    Column(modifier = Modifier.padding(vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = entry.actionLabel,
                color = textColor,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = entry.status.name,
                color = statusColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        if (entry.target != null) {
            Text(
                text = entry.target,
                color = Color.Gray,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Text(
            text = entry.reason,
            color = Color.Gray,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = sdf.format(Date(entry.timestamp)),
            color = Color.Gray,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
