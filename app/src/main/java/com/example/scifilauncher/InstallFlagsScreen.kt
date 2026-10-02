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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class InstallFilter { ALL, FLAGGED }

/** Every new app install this launcher has seen, with a lightweight risk read (sideloaded?
 * asks for anything sensitive?) - not a malware scanner, just the same at-a-glance checks a
 * careful person would do by hand. */
@Composable
fun InstallFlagsScreen(
    themeColor: Color,
    isDark: Boolean,
    entries: List<InstallFlagEntry>,
    onBack: () -> Unit
) {
    var filter by remember { mutableStateOf(InstallFilter.ALL) }
    val filtered = remember(entries, filter) {
        if (filter == InstallFilter.ALL) entries else entries.filter { it.isFlagged }
    }
    val sdf = remember { SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()) }

    TerminalScaffold(
        themeColor = themeColor,
        code = "INSTALLS",
        title = "New app installs",
        subtitle = "Every new app install this launcher has seen, with a lightweight risk read - not a malware scanner, just the same at-a-glance checks a careful person would do by hand.",
        backLabel = "‹  BACK",
        onBack = onBack
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InstallFilterChip("ALL", filter == InstallFilter.ALL, themeColor) { filter = InstallFilter.ALL }
            InstallFilterChip("FLAGGED", filter == InstallFilter.FLAGGED, themeColor) { filter = InstallFilter.FLAGGED }
        }
        TerminalCard(label = "INSTALLS (${filtered.size})", themeColor = themeColor) {
            if (filtered.isEmpty()) {
                Text(
                    text = if (filter == InstallFilter.FLAGGED) "Nothing flagged." else "No installs seen yet.",
                    color = TerminalStyle.muted,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                filtered.forEach { entry ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (entry.isFlagged) Color(0xFFFFC107).copy(alpha = 0.10f)
                                else Color.Gray.copy(alpha = 0.08f)
                            )
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = entry.appLabel,
                                color = if (entry.isFlagged) Color(0xFFFFC107) else themeColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = sdf.format(Date(entry.timestamp)),
                                color = TerminalStyle.muted,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            text = entry.packageName,
                            color = TerminalStyle.muted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        if (entry.sideloaded) {
                            Text(
                                text = "Sideloaded - not installed from an app store",
                                color = TerminalStyle.ink,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        if (entry.flaggedPermissions.isNotEmpty()) {
                            Text(
                                text = "Requests: " + entry.flaggedPermissions.joinToString(", ") {
                                    it.substringAfterLast('.')
                                },
                                color = TerminalStyle.ink,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        if (!entry.isFlagged) {
                            Text(
                                text = "Nothing unusual",
                                color = TerminalStyle.muted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstallFilterChip(label: String, active: Boolean, themeColor: Color, onClick: () -> Unit) {
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
