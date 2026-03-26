package com.example.scifilauncher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FavoriteAppsScreen(
    themeColor: Color,
    isDark: Boolean,
    batteryMode: BatterySaverMode,
    apps: List<AppItem>,
    favoriteApps: Set<String>,
    onFavoriteAppsChange: (Set<String>) -> Unit,
    onBack: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        MatrixBackground(
            themeColor = themeColor,
            isDark = isDark,
            batteryMode = batteryMode
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "< SETTINGS",
                color = themeColor,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .clickable { onBack() }
            )

            Text(
                text = "FAVORITE APPS",
                color = themeColor,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                apps.forEach { app ->
                    val isFav = favoriteApps.contains(app.packageName)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = app.label,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Switch(
                            checked = isFav,
                            onCheckedChange = { checked ->
                                val current = favoriteApps.toMutableSet()
                                if (checked) {
                                    if (current.size < 6) current.add(app.packageName)
                                } else {
                                    current.remove(app.packageName)
                                }
                                onFavoriteAppsChange(current)
                            }
                        )
                    }
                }
            }
        }
    }
}
