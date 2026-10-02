package com.example.scifilauncher

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FreezerScreen(
    themeColor: Color,
    isDark: Boolean,
    batteryMode: BatterySaverMode,
    apps: List<AppItem>,          // only frozen/hidden apps
    onBack: () -> Unit,
    onAppClick: (String) -> Unit  // packageName
) {
    TerminalScaffold(
        themeColor = themeColor,
        code = "FREEZER",
        title = "Freezer",
        subtitle = "Frozen apps stop running entirely until unfrozen - tap one to manage it.",
        backLabel = "‹  SECURITY",
        onBack = onBack
    ) {
        TerminalCard(label = "FROZEN APPS (${apps.size})", themeColor = themeColor) {
            if (apps.isEmpty()) {
                Text(
                    text = "No frozen apps. Hide an app to see it here.",
                    color = TerminalStyle.muted,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                apps.forEach { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAppClick(app.packageName) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        app.iconBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = app.label,
                                modifier = Modifier.size(36.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            text = app.label,
                            color = TerminalStyle.ink,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}
