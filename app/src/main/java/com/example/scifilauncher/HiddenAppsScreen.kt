package com.example.scifilauncher

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HiddenAppsScreen(
    themeColor: Color,
    isDark: Boolean,
    batteryMode: BatterySaverMode,
    apps: List<AppItem>,
    hiddenApps: Set<String>,
    onHiddenAppsChange: (Set<String>) -> Unit,
    onBack: () -> Unit
) {
    TerminalScaffold(
        themeColor = themeColor,
        code = "HIDDEN",
        title = "Hidden apps",
        subtitle = "Toggle an app on to hide it from the app drawer entirely.",
        backLabel = "‹  SECURITY",
        onBack = onBack
    ) {
        TerminalCard(label = "INSTALLED APPS (${apps.size})", themeColor = themeColor) {
            apps.forEach { app ->
                val isHidden = hiddenApps.contains(app.packageName)
                Row(
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = app.label,
                        color = TerminalStyle.ink,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Switch(
                        checked = isHidden,
                        colors = SwitchDefaults.colors(checkedTrackColor = themeColor),
                        onCheckedChange = { checked ->
                            val newSet = hiddenApps.toMutableSet()
                            if (checked) newSet.add(app.packageName)
                            else newSet.remove(app.packageName)
                            onHiddenAppsChange(newSet)
                        }
                    )
                }
            }
        }
    }
}
