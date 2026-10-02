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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Internal errors/bugs from this app itself - separate from Requests, which is about
 * actions taken regarding other apps. This is "what went wrong in here". */
@Composable
fun AppLogScreen(
    themeColor: Color,
    isDark: Boolean,
    entries: List<ErrorLogEntry>,
    onBack: () -> Unit
) {
    var filterTag by remember { mutableStateOf<String?>(null) }
    val tags = remember(entries) { entries.map { it.tag }.distinct() }
    val filtered = remember(entries, filterTag) {
        if (filterTag == null) entries else entries.filter { it.tag == filterTag }
    }
    val sdf = remember { SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()) }

    TerminalScaffold(
        themeColor = themeColor,
        code = "LOG",
        title = "App log",
        subtitle = "Internal errors from this app itself - separate from Requests, which is about actions taken regarding other apps.",
        backLabel = "‹  BACK",
        onBack = onBack
    ) {
        if (tags.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LogFilterChip("ALL", filterTag == null, themeColor) { filterTag = null }
                tags.forEach { tag ->
                    LogFilterChip(tag, filterTag == tag, themeColor) { filterTag = tag }
                }
            }
        }
        TerminalCard(label = "ENTRIES (${filtered.size})", themeColor = themeColor) {
            if (filtered.isEmpty()) {
                Text(
                    text = "No errors logged. That's a good sign.",
                    color = TerminalStyle.muted,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                filtered.forEach { entry ->
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = entry.tag,
                                color = themeColor,
                                fontSize = 12.sp,
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
                            text = entry.message,
                            color = TerminalStyle.ink,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogFilterChip(label: String, active: Boolean, themeColor: Color, onClick: () -> Unit) {
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
