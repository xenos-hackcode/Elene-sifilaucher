package com.example.scifilauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    val textColor = if (isDark) Color.White else Color.Black
    val filtered = remember(entries, filter) {
        if (filter == InstallFilter.ALL) entries else entries.filter { it.isFlagged }
    }
    val sdf = remember { SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()) }

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
                text = "NEW APP INSTALLS",
                color = themeColor,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InstallFilterChip("ALL", filter == InstallFilter.ALL, themeColor) { filter = InstallFilter.ALL }
                InstallFilterChip("FLAGGED", filter == InstallFilter.FLAGGED, themeColor) { filter = InstallFilter.FLAGGED }
            }
            Spacer(Modifier.height(16.dp))

            if (filtered.isEmpty()) {
                Text(
                    text = if (filter == InstallFilter.FLAGGED) "Nothing flagged." else "No installs seen yet.",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered) { entry ->
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
                                    color = Color.Gray,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Text(
                                text = entry.packageName,
                                color = Color.Gray,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            if (entry.sideloaded) {
                                Text(
                                    text = "Sideloaded - not installed from an app store",
                                    color = textColor,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (entry.flaggedPermissions.isNotEmpty()) {
                                Text(
                                    text = "Requests: " + entry.flaggedPermissions.joinToString(", ") {
                                        it.substringAfterLast('.')
                                    },
                                    color = textColor,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (!entry.isFlagged) {
                                Text(
                                    text = "Nothing unusual",
                                    color = Color.Gray,
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
