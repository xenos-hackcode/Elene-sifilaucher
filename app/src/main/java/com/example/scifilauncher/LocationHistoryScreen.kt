package com.example.scifilauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LocationHistoryScreen(
    themeColor: Color,
    isDark: Boolean,
    entries: List<LocationEntry>,
    onOpenInMaps: (LocationEntry) -> Unit,
    onClearHistory: () -> Unit,
    onBack: () -> Unit
) {
    val sdf = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())

    TerminalScaffold(
        themeColor = themeColor,
        code = "LOCATION",
        title = "Location history",
        subtitle = "A running log captured roughly every 15 minutes while Location History is on (Security), using whatever fix the phone already has - not a fresh GPS request each time.",
        backLabel = "‹  BACK",
        onBack = onBack
    ) {
        if (entries.isNotEmpty()) {
            Text(
                text = "CLEAR HISTORY",
                color = Color(0xFFE57373),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable(onClick = onClearHistory)
            )
        }
        TerminalCard(label = "ENTRIES (${entries.size})", themeColor = themeColor) {
            if (entries.isEmpty()) {
                Text(
                    text = "No entries yet.",
                    color = TerminalStyle.muted,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                entries.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Gray.copy(alpha = 0.08f))
                            .clickable { onOpenInMaps(entry) }
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = sdf.format(Date(entry.timestamp)),
                                color = themeColor,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "%.5f, %.5f".format(entry.lat, entry.lon),
                                color = TerminalStyle.ink,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            text = "MAP →",
                            color = themeColor.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}
