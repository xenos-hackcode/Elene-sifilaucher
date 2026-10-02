package com.example.scifilauncher

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

const val MY_LOCATION_URL_BASE = "https://appassets.androidx.net/assets/mylocation/index.html"

private fun redactMapboxToken(url: String): String =
    Regex("([?&](?:access_token|token)=)[^&]+").replace(url) { match ->
        "${match.groupValues[1]}<redacted>"
    }

/** Same WebView setup shape as createGlobeWebView (GlobeWebView.kt) - local HTML/JS served via
 * WebViewAssetLoader, everything else (Mapbox GL JS itself, real map tiles) falls through to a
 * genuine network request, unlike the fully-offline Globe. This screen needs real internet
 * access to function at all - it's a live map, not a decorative one. */
@SuppressLint("SetJavaScriptEnabled")
fun createMyLocationWebView(context: Context, mapboxToken: String): WebView {
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

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                Log.e("MyLocationScreen", "Resource load failed: ${redactMapboxToken(request.url.toString())} - ${error.description}")
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d(
                    "MyLocationJS",
                    "${message.messageLevel()}: ${message.message()} (${redactMapboxToken(message.sourceId())}:${message.lineNumber()})"
                )
                return true
            }
        }

        // Token passed as a URL query param (read by mylocation.js via location.search) rather
        // than a JS bridge call after load - simpler, no race with the page's own init code.
        val encodedToken = android.net.Uri.encode(mapboxToken)
        loadUrl("$MY_LOCATION_URL_BASE?token=$encodedToken")
    }
}
