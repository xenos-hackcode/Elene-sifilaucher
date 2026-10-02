package com.example.scifilauncher

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.webkit.WebView
import android.widget.FrameLayout

/** A lean copy of GlobeActivity's own proven-working pattern (plain Activity hosting the WebView
 * directly, NOT Compose's AndroidView) - reused here specifically because embedding this same
 * WebView/WebGL setup via AndroidView is a confirmed, already-documented compositing bug on this
 * device (see GlobeActivity's own class doc: renders fine as a real Activity, stays solid black
 * inside Compose). A real wallpaper needs to sit BEHIND other Dashboard UI, which a separate
 * Activity fundamentally can't do - so this is the practical compromise: selecting a Globe
 * wallpaper launches this real, working full-screen globe instead of a broken inline background.
 * Tapping anywhere (or back) returns to the Dashboard. */
class GlobeWallpaperActivity : Activity() {
    private var wallpaperView: WebView? = null
    private var mode: GlobeWallpaperMode = GlobeWallpaperMode.LIVE
    private val snapshotHandler = Handler(Looper.getMainLooper())
    private var snapshotting = false

    companion object {
        const val EXTRA_MODE = "mode"
        private const val SNAPSHOT_INTERVAL_MS = 600L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val modeName = intent.getStringExtra(EXTRA_MODE) ?: GlobeWallpaperMode.LIVE.name
        mode = runCatching { GlobeWallpaperMode.valueOf(modeName) }.getOrDefault(GlobeWallpaperMode.LIVE)

        val webView: WebView = createGlobeWebView(this, wallpaperMode = mode)
        wallpaperView = webView
        val root = FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        if (mode != GlobeWallpaperMode.LIVE) {
            // LIVE keeps the real drag-to-rotate; VIDEO/IMAGE already block touch on the WebView
            // itself for the globe's own interaction, so a tap here is purely "leave this screen".
            root.setOnClickListener { finish() }
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        wallpaperView?.onResume()
        snapshotting = true
        // Give the page a moment to actually paint a real frame before the first capture.
        snapshotHandler.postDelayed(snapshotRunnable, 900L)
    }

    override fun onPause() {
        snapshotting = false
        snapshotHandler.removeCallbacks(snapshotRunnable)
        wallpaperView?.onPause()
        super.onPause()
    }

    // Captures the actual rendered window (the only place this WebGL globe renders correctly on
    // this device - see the class doc) into a bitmap the Dashboard background can display when
    // you're back there, instead of a hand-drawn placeholder. Not a live feed - just refreshed
    // every time you actually view the globe.
    private val snapshotRunnable = object : Runnable {
        override fun run() {
            if (!snapshotting) return
            val bitmap = Bitmap.createBitmap(window.decorView.width.coerceAtLeast(1), window.decorView.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            runCatching {
                PixelCopy.request(window, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS) {
                        GlobeSnapshotStore.slotFor(mode).value = bitmap
                    }
                }, snapshotHandler)
            }
            if (snapshotting) snapshotHandler.postDelayed(this, SNAPSHOT_INTERVAL_MS)
        }
    }

    override fun onDestroy() {
        wallpaperView?.let { view ->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.stopLoading()
            view.destroy()
        }
        wallpaperView = null
        super.onDestroy()
    }
}
