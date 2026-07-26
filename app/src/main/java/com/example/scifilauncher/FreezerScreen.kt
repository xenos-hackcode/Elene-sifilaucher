package com.example.scifilauncher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

@Composable
fun FreezerScreen(
    themeColor: Color,
    isDark: Boolean,
    batteryMode: BatterySaverMode,
    apps: List<AppItem>,          // only frozen/hidden apps
    onBack: () -> Unit,
    onAppClick: (String) -> Unit  // packageName
) {
    val bg = if (isDark) Color(0xFF050505) else Color(0xFFF5F5F5)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
    ) {
        // Optional: reuse your Matrix background if you want
        MatrixBackground(
            themeColor = themeColor,
            isDark = isDark,
            batteryMode = batteryMode
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 40.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onBack) {
                    Text("Back")
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Freezer",
                    color = themeColor
                )
            }

            Spacer(Modifier.height(16.dp))

            if (apps.isEmpty()) {
                Text(
                    text = "No frozen apps. Hide an app to see it here.",
                    color = if (isDark) Color.LightGray else Color.DarkGray
                )
            } else {
                LazyColumn {
                    items(apps) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAppClick(app.packageName) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            app.iconBitmap?.let { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = app.label,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(Modifier.width(12.dp))
                            }

                            Text(
                                text = app.label,
                                color = if (isDark) Color.White else Color.Black
                            )
                        }
                    }
                }
            }
        }
    }
}