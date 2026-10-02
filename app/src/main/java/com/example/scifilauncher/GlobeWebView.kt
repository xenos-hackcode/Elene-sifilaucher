package com.example.scifilauncher

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

const val GLOBE_URL = "https://appassets.androidx.net/assets/globe/index.html"

/** Which interaction mode the wallpaper variant of the globe uses - the real globe.js already
 * runs BOTH a constant idle auto-spin (globeRoot.rotation.y += 0.0006 every frame) AND
 * OrbitControls drag-to-rotate simultaneously, so LIVE needs no changes at all; VIDEO just blocks
 * touch from ever reaching the page so only the auto-spin shows; IMAGE additionally freezes the
 * render loop after its first real frame, for a genuinely static picture, not a slowed video. */
enum class GlobeWallpaperMode { LIVE, VIDEO, IMAGE }

@SuppressLint("SetJavaScriptEnabled")
fun createGlobeWebView(
    context: Context,
    forceSoftwareLayer: Boolean = false,
    wallpaperMode: GlobeWallpaperMode? = null,
    onDoubleClick: (lat: Double, lng: Double) -> Unit = { _, _ -> }
): WebView {
    val assetLoader = WebViewAssetLoader.Builder()
        .setDomain("appassets.androidx.net")
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        setBackgroundColor(android.graphics.Color.BLACK)
        if (forceSoftwareLayer) {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val response = assetLoader.shouldInterceptRequest(request.url)
                Log.d(
                    "GlobeScreen",
                    "shouldInterceptRequest url=${request.url} isForMainFrame=${request.isForMainFrame} -> ${if (response != null) "HANDLED" else "NULL (falls through to real network)"}"
                )
                return response
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                Log.e("GlobeScreen", "Resource load failed: ${request.url} - ${error.description}")
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (wallpaperMode == null) return
                // "White and black" - a CSS filter on the canvas element, not a rewrite of the
                // real textures/materials in globe.js, so this stays a thin wrapper rather than
                // forking the whole globe implementation for one color variant.
                view.evaluateJavascript(
                    "document.body.style.filter='grayscale(1) contrast(1.15) brightness(1.05)';",
                    null
                )
                if (wallpaperMode == GlobeWallpaperMode.IMAGE) {
                    // Let it render a few real frames first (so it's a genuine settled picture,
                    // not caught mid-load), then stop scheduling any more - a real freeze, not a
                    // paused video file.
                    view.postDelayed({
                        view.evaluateJavascript("window.requestAnimationFrame = function(){ return 0; };", null)
                    }, 1200)
                }
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d(
                    "GlobeJS",
                    "${message.messageLevel()}: ${message.message()} (${message.sourceId()}:${message.lineNumber()})"
                )
                return true
            }
        }

        addJavascriptInterface(
            object {
                @JavascriptInterface
                fun onDoubleClick(lat: Double, lng: Double) {
                    post { onDoubleClick(lat, lng) }
                }
            },
            "XenosGlobeBridge"
        )

        if (wallpaperMode == GlobeWallpaperMode.VIDEO || wallpaperMode == GlobeWallpaperMode.IMAGE) {
            // Consuming the touch here means it never reaches the page at all, so
            // OrbitControls' own touchstart/touchmove listeners never fire - the auto-spin
            // (a rAF-driven animation, unrelated to touch) keeps running untouched.
            setOnTouchListener { _, _ -> true }
        }

        loadUrl(GLOBE_URL)
    }
}
