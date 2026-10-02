package com.example.scifilauncher

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf

/** In-memory cache of the last real rendered frame of each Globe wallpaper mode, captured via
 * PixelCopy while GlobeWallpaperActivity is actually on screen (see its own doc: WebGL only
 * renders correctly as a real Activity window on this device, never embedded in Compose). The
 * Dashboard background can't render the live WebView itself, but it CAN show this genuine
 * captured frame instead of a hand-drawn placeholder sketch - a real snapshot of the actual globe,
 * refreshed every time you view it, even though it can't keep rotating live behind your icons. */
object GlobeSnapshotStore {
    val live = mutableStateOf<Bitmap?>(null)
    val video = mutableStateOf<Bitmap?>(null)
    val image = mutableStateOf<Bitmap?>(null)

    fun slotFor(mode: GlobeWallpaperMode) = when (mode) {
        GlobeWallpaperMode.LIVE -> live
        GlobeWallpaperMode.VIDEO -> video
        GlobeWallpaperMode.IMAGE -> image
    }
}
