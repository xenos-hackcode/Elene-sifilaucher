package com.example.scifilauncher

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** Renders [content] as a QR code bitmap. Uses zxing-core's encoder directly - already a
 * dependency for scanning (journeyapps wrapper), the encoder side needs nothing new. */
fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
    bitmap
}.getOrNull()
