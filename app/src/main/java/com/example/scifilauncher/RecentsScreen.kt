package com.example.scifilauncher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A recent app entry ready to render - the real RecentAppEntry (open history) joined against
 * the matching installed AppItem (icon/label), since a package can vanish from history's own
 * tracking data while the app itself gets uninstalled. */
data class RecentDisplayItem(
    val app: AppItem,
    val lastOpenedMs: Long,
    val openCount: Int
)

@Composable
fun RecentsScreen(
    modifier: Modifier = Modifier,
    themeColor: Color,
    isDark: Boolean,
    batteryMode: BatterySaverMode,
    recentItems: List<RecentDisplayItem>,
    onBackToDashboard: () -> Unit,
    onAppClick: (String) -> Unit,
    onClearRecents: () -> Unit = {}
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
                .padding(top = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RECENTS",
                    color = themeColor,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (recentItems.isNotEmpty()) {
                    Text(
                        text = "CLEAR",
                        color = themeColor,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable { onClearRecents() }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (recentItems.isEmpty()) {
                Text(
                    text = "Nothing opened yet.",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 24.dp)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 72.dp)
            ) {
                LazyVerticalGrid(
                    modifier = Modifier.fillMaxSize(),
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(recentItems, key = { it.app.packageName }) { item ->
                        val thumbnail = remember(item.app.packageName) {
                            RecentAppHistory.loadThumbnail(context, item.app.packageName)
                        }
                        RecentAppCard(
                            item = item,
                            thumbnail = thumbnail,
                            themeColor = themeColor,
                            isDark = isDark,
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
                onClick = { onBackToDashboard() }
            )

            GlitchNavLetter(
                letter = "S",
                color = themeColor,
                onClick = { /* already viewing recents */ }
            )
        }
    }
}

private fun formatLastOpened(ms: Long): String {
    val minutesAgo = (System.currentTimeMillis() - ms) / 60_000L
    return when {
        minutesAgo < 1 -> "just now"
        minutesAgo < 60 -> "${minutesAgo}m ago"
        minutesAgo < 1440 -> "${minutesAgo / 60}h ago"
        else -> SimpleDateFormat("HH:mm dd MMM", Locale.getDefault()).format(Date(ms))
    }
}

@Composable
private fun RecentAppCard(
    item: RecentDisplayItem,
    thumbnail: android.graphics.Bitmap?,
    themeColor: Color,
    isDark: Boolean,
    onAppClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isDark) Color(0xFF0A0E14) else Color(0xFFE8E8E8))
            .clickable { onAppClick(item.app.packageName) }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(Color.Black.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null) {
                // Real screenshot of this app's last known state - not just its icon.
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = item.app.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Image(
                    bitmap = item.app.iconBitmap.asImageBitmap(),
                    contentDescription = item.app.label,
                    modifier = Modifier.size(48.dp)
                )
            }
        }
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = item.app.label,
                color = themeColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = formatLastOpened(item.lastOpenedMs),
                color = Color.Gray,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
            Text(
                text = "opened ${item.openCount}x",
                color = Color.Gray,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
    }
}
