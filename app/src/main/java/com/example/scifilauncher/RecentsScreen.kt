package com.example.scifilauncher

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RecentsScreen(
    modifier: Modifier = Modifier,
    themeColor: Color,
    isDark: Boolean,
    batteryMode: BatterySaverMode,
    recentApps: List<AppItem>,
    onBackToDashboard: () -> Unit,
    onOpenAppsFromRecents: () -> Unit,   // NEW
    onAppClick: (String) -> Unit
) {
    val context = LocalContext.current

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        MatrixBackground(
            themeColor = themeColor,
            isDark = isDark,
            batteryMode = batteryMode
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RECENTS",
                    color = themeColor,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 72.dp)
            ) {
                LazyVerticalGrid(
                    modifier = Modifier.fillMaxSize(),
                    columns = GridCells.Adaptive(minSize = 72.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(recentApps, key = { it.packageName }) { app ->
                        RecentAppTile(
                            app = app,
                            themeColor = themeColor,
                            onAppClick = onAppClick
                        )
                    }
                }
            }
        }

        // NOS nav along bottom
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlitchNavLetter(
                letter = "N",
                color = themeColor,
                onClick = { onBackToDashboard() }
            )

            GlitchNavLetter(
                letter = "O",
                color = themeColor,
                onClick = {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                }
            )

            GlitchNavLetter(
                letter = "S",
                color = themeColor,
                onClick = { onOpenAppsFromRecents() }
            )
        }
    }
}

@Composable
private fun RecentAppTile(
    app: AppItem,
    themeColor: Color,
    onAppClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .width(72.dp)
            .clickable { onAppClick(app.packageName) },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            bitmap = app.iconBitmap.asImageBitmap(),
            contentDescription = app.label,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = app.label,
            color = themeColor,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
    }
}
