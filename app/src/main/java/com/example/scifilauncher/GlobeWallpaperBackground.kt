package com.example.scifilauncher

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import kotlin.math.min

/** Embedding the real globe.js WebView inline via Compose's AndroidView is a confirmed, already-
 * documented compositing bug on this device (renders solid black - see GlobeActivity's own class
 * doc, and GlobeWallpaperActivity which reuses that same proven-working plain-Activity pattern
 * instead). A wallpaper needs to sit BEHIND other Dashboard UI, which a separate Activity
 * fundamentally can't do, so this is the practical compromise: the real interactive/animated
 * globe auto-launches full-screen on arrival (see GlobeWallpaperActivity), and while you're back
 * here the Dashboard background shows the actual last real frame it captured via PixelCopy while
 * you were viewing it (GlobeSnapshotStore) - a genuine snapshot of the real globe, not a live
 * feed, refreshed every time you visit. Only falls back to the hand-drawn placeholder sketch
 * before any snapshot has ever been captured (first launch on this install). */
@Composable
fun GlobePlaceholderWithAutoLaunch(mode: GlobeWallpaperMode) {
    val context = LocalContext.current
    val snapshot by GlobeSnapshotStore.slotFor(mode)
    fun launch() {
        context.startActivity(
            Intent(context, GlobeWallpaperActivity::class.java)
                .putExtra(GlobeWallpaperActivity.EXTRA_MODE, mode.name)
        )
    }
    // Only auto-launch the real globe on the very first visit (no snapshot captured yet for this
    // mode) - once a real frame exists, this background just shows it like an actual wallpaper,
    // and viewing/refreshing the live globe again is a deliberate tap, not forced on every visit.
    LaunchedEffect(mode) { if (GlobeSnapshotStore.slotFor(mode).value == null) launch() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020202))
            .clickable { launch() },
        contentAlignment = Alignment.Center
    ) {
        val currentSnapshot = snapshot
        if (currentSnapshot != null) {
            Image(
                bitmap = currentSnapshot.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = min(size.width, size.height) * 0.28f
                val c = Offset(size.width / 2f, size.height * 0.42f)
                drawCircle(color = Color.White.copy(alpha = 0.6f), radius = r, center = c, style = Stroke(width = 1.5f))
                drawLine(Color.White.copy(alpha = 0.45f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
                drawOval(color = Color.White.copy(alpha = 0.45f), topLeft = Offset(c.x - r * 0.4f, c.y - r), size = Size(r * 0.8f, r * 2f), style = Stroke(width = 1f))
            }
        }
        Text(
            text = "TAP TO VIEW GLOBE",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
