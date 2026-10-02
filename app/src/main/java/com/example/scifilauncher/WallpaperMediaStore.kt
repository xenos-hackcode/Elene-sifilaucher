package com.example.scifilauncher

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.OutputStream

/** Only publishes a complete file; failed writes remove their pending MediaStore entry. */
internal object WallpaperMediaStore {
    fun save(context: Context, label: String, video: Boolean, write: (OutputStream) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val resolver = context.contentResolver
        val name = label.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim().ifBlank { "XENOS" }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name + "-" + System.currentTimeMillis() + if (video) ".mp4" else ".png")
            put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) "Movies" else "Pictures") + "/SciFiLauncher Wallpapers")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: return false
        var complete = false
        try {
            checkNotNull(resolver.openOutputStream(uri)).use(write)
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0)
            complete = true
            return true
        } finally {
            if (!complete) runCatching { resolver.delete(uri, null, null) }
        }
    }
}
