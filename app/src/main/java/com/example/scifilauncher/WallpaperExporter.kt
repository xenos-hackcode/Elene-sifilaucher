package com.example.scifilauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.random.Random

/** Shared native frame renderer for gallery stills, PNGs and MP4 export. */
object WallpaperExporter {

    fun canExport(id: String): Boolean = id in EXPORTABLE_IDS

    private val EXPORTABLE_IDS = setOf(
        "matrix", "starfield_calm", "starfield_fast", "scanlines_calm", "scanlines_fast",
        "pulse_calm", "pulse_fast", "grid_calm", "grid_fast", "coderain_calm", "coderain_fast",
        "solid", "vignette", "noise_static", "horizon", "xenos_glitch_image", "xenos_glitch_video"
    )

    /** ids with real time-based motion worth encoding into a video - everything else in the
     * catalog (Image entries, plus any id not listed here) is genuinely static, so exporting a
     * video for those would just be a still frame with a pointless file size, not a real gain. */
    val ANIMATED_IDS = setOf(
        "matrix", "starfield_calm", "starfield_fast", "scanlines_calm", "scanlines_fast",
        "pulse_calm", "pulse_fast", "grid_calm", "grid_fast", "coderain_calm", "coderain_fast",
        "xenos_glitch_video"
    )

    fun exportPng(context: Context, id: String, label: String, themeColorArgb: Int, width: Int = 1080, height: Int = 1920): Boolean = runCatching {
        val bitmap = renderFrame(id, themeColorArgb, width, height, 0f)
        val resolver = context.contentResolver
        val safeLabel = label.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim().ifBlank { "wallpaper" }
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$safeLabel-${System.currentTimeMillis()}.png")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SciFiLauncher Wallpapers")
        }
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
        val ok = resolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) } ?: false
        bitmap.recycle()
        ok
    }.getOrDefault(false)

    /** [t] is 0..1 progress through one animation cycle of this id's own animation -
     * called once per frame by [WallpaperVideoExporter] (and with t=0f for the static PNG path
     * above), so real MP4 export and the still-frame export share one drawing implementation
     * instead of two that could quietly drift apart. */
    fun renderFrame(id: String, themeColor: Int, width: Int, height: Int, t: Float): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#FF020202"))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun withAlpha(alpha: Float): Int = Color.argb((alpha * 255).toInt().coerceIn(0, 255), Color.red(themeColor), Color.green(themeColor), Color.blue(themeColor))
        fun hudCorners() {
            paint.color = withAlpha(0.5f)
            paint.strokeWidth = 3f
            val inset = 40f; val arm = 50f
            canvas.drawLine(inset, inset, inset + arm, inset, paint); canvas.drawLine(inset, inset, inset, inset + arm, paint)
            canvas.drawLine(width - inset, inset, width - inset - arm, inset, paint); canvas.drawLine(width - inset, inset, width - inset, inset + arm, paint)
            canvas.drawLine(inset, height - inset, inset + arm, height - inset, paint); canvas.drawLine(inset, height - inset, inset, height - inset - arm, paint)
            canvas.drawLine(width - inset, height - inset, width - inset - arm, height - inset, paint); canvas.drawLine(width - inset, height - inset, width - inset, height - inset - arm, paint)
        }

        when {
            id == "solid" -> {
                paint.shader = RadialGradient(width / 2f, height / 2f, 1200f, withAlpha(0.25f), Color.parseColor("#FF020202"), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                paint.shader = null
                hudCorners()
            }
            id == "vignette" -> {
                paint.shader = RadialGradient(width / 2f, height / 2f, 1600f, intArrayOf(Color.parseColor("#FF020202"), withAlpha(0.12f), Color.BLACK), null, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                paint.shader = null
                hudCorners()
            }
            id == "noise_static" -> {
                val rand = Random(1)
                repeat(500) {
                    paint.color = withAlpha(rand.nextFloat() * 0.4f)
                    canvas.drawCircle(rand.nextFloat() * width, rand.nextFloat() * height, 3.5f, paint)
                }
                hudCorners()
            }
            id == "horizon" -> {
                val horizon = height * 0.62f
                paint.color = withAlpha(0.6f); paint.strokeWidth = 4f
                canvas.drawLine(0f, horizon, width.toFloat(), horizon, paint)
                paint.color = withAlpha(0.2f); paint.strokeWidth = 2f
                var gx = -width.toFloat()
                while (gx < width * 2) { canvas.drawLine(width / 2f, horizon, gx, height.toFloat(), paint); gx += 120f }
                var gy = horizon; var step = 40f
                while (gy < height) { canvas.drawLine(0f, gy, width.toFloat(), gy, paint); gy += step; step *= 1.35f }
                hudCorners()
            }
            id == "matrix" -> {
                paint.color = withAlpha(0.5f); paint.strokeWidth = 3f
                val cols = 26
                repeat(cols) { i ->
                    val x = i * (width / cols.toFloat()) + width / cols / 2f
                    val phase = Random(i.toLong()).nextFloat()
                    val yHead = ((phase + t) % 1f) * height * 1.4f - height * 0.2f
                    canvas.drawLine(x, yHead - height * 0.3f, x, yHead, paint)
                }
                hudCorners()
            }
            id.startsWith("starfield") -> {
                val rand = Random(2)
                val cx = width / 2f; val cy = height / 2f
                repeat(140) {
                    val depth = rand.nextFloat() * 0.8f + 0.2f
                    val baseX = rand.nextFloat(); val baseY = rand.nextFloat()
                    val progress = (t + depth) % 1f
                    val x = cx + (baseX - 0.5f) * width * progress * 2f
                    val y = cy + (baseY - 0.5f) * height * progress * 2f
                    paint.color = withAlpha((1f - progress).coerceIn(0.15f, 1f))
                    val s = (2f + progress * 5f) * depth
                    canvas.drawRect(x - s / 2f, y - s / 2f, x + s / 2f, y + s / 2f, paint)
                }
                hudCorners()
            }
            id.startsWith("scanlines") -> {
                paint.color = withAlpha(0.06f); paint.strokeWidth = 1f
                var y = 0f
                while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, paint); y += 12f }
                paint.color = withAlpha(0.5f); paint.strokeWidth = 5f
                val sweepY = t * (height + 200f) - 100f
                canvas.drawLine(0f, sweepY, width.toFloat(), sweepY, paint)
                hudCorners()
            }
            id.startsWith("pulse") -> {
                paint.color = withAlpha(0.4f); paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
                val maxR = minOf(width, height) * 0.4f
                for (i in 0..2) {
                    val ringT = (t + i / 3f) % 1f
                    paint.color = withAlpha((1f - ringT) * 0.4f)
                    canvas.drawCircle(width / 2f, height / 2f, maxR * ringT, paint)
                }
                paint.style = Paint.Style.FILL
                paint.color = withAlpha(0.5f + t * 0.3f)
                canvas.drawCircle(width / 2f, height / 2f, maxR * 0.15f, paint)
                hudCorners()
            }
            id.startsWith("grid") -> {
                val horizon = height * 0.4f
                paint.color = withAlpha(0.25f); paint.strokeWidth = 2f
                var x = -width.toFloat()
                while (x < width * 2) { canvas.drawLine(width / 2f, horizon, x, height.toFloat(), paint); x += 80f }
                var rowT = t * 4f
                while (rowT < 4f) {
                    val y = horizon + (height - horizon) * (1f - 1f / (1f + rowT))
                    if (y in horizon..height.toFloat()) canvas.drawLine(0f, y, width.toFloat(), y, paint)
                    rowT += 0.3f
                }
                hudCorners()
            }
            id.startsWith("coderain") -> {
                val rand = Random(3)
                paint.strokeWidth = 4f
                repeat(28) { i ->
                    val x = i * (width / 28f) + width / 28f / 2f
                    val offset = rand.nextFloat()
                    val yHead = ((t + offset) % 1f) * height * 2f - height * 0.5f
                    paint.color = withAlpha(0.35f)
                    canvas.drawLine(x, yHead - height * 0.35f, x, yHead, paint)
                    paint.color = withAlpha(1f)
                    canvas.drawCircle(x, yHead, 5f, paint)
                }
                hudCorners()
            }
            id == "xenos_glitch_image" || id == "xenos_glitch_video" -> {
                // Matches the real reference layout: two long trunk lines crossing near-full
                // width above/below the wordmark, plus corner + bottom-third branch clusters -
                // see XenosGlitchBackground.kt's generateStems for the same shape in Compose.
                val rand = Random(42)
                paint.strokeWidth = 2f
                // For the VIDEO entry, the stems breathe in/out over one triangle-wave cycle of
                // t (0->1->0), matching the Compose version's reverse-repeating pulse; the IMAGE
                // entry ignores this (t is always 0 there) and stays at a fixed alpha.
                val stemAlpha = if (id == "xenos_glitch_video") 0.25f + (1f - kotlin.math.abs(t * 2f - 1f)) * 0.5f else 0.5f
                paint.color = withAlpha(stemAlpha)
                fun zigzag(sx: Float, sy: Float, ex: Float, ey: Float, segments: Int, jitter: Float) {
                    var px = sx; var py = sy
                    for (i in 1..segments) {
                        val t = i / segments.toFloat()
                        val nx = if (i == segments) ex else sx + (ex - sx) * t + (rand.nextFloat() - 0.5f) * jitter
                        val ny = if (i == segments) ey else sy + (ey - sy) * t + (rand.nextFloat() - 0.5f) * jitter
                        canvas.drawLine(px, py, nx, ny, paint)
                        px = nx; py = ny
                    }
                }
                fun cluster(ax: Float, ay: Float, angleCenter: Float, angleSpread: Float, count: Int, minLen: Float, maxLen: Float) {
                    repeat(count) {
                        val angle = angleCenter + (rand.nextFloat() - 0.5f) * angleSpread
                        val len = minLen + rand.nextFloat() * (maxLen - minLen)
                        zigzag(ax, ay, ax + kotlin.math.cos(angle) * len, ay + kotlin.math.sin(angle) * len, rand.nextInt(3, 6), len * 0.12f)
                    }
                }
                zigzag(-40f, height * 0.20f, width + 40f, height * 0.22f, 5, 30f)
                zigzag(-40f, height * 0.46f, width + 40f, height * 0.44f, 5, 30f)
                cluster(width * 0.18f, 0f, 1.3f, 0.9f, 4, height * 0.12f, height * 0.3f)
                cluster(width * 0.82f, 0f, 1.85f, 0.9f, 4, height * 0.12f, height * 0.3f)
                cluster(width * 0.5f, height * 0.34f, -1.57f, 1.4f, 3, height * 0.05f, height * 0.12f)
                listOf(0.05f, 0.28f, 0.5f, 0.7f, 0.92f).forEach { ax ->
                    cluster(ax * width, height * 0.95f, -1.57f, 1.6f, rand.nextInt(2, 4), height * 0.1f, height * 0.32f)
                }
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = android.graphics.Typeface.MONOSPACE
                paint.isFakeBoldText = true
                paint.textSize = width * 0.14f
                paint.color = android.graphics.Color.argb(90, 40, 120, 70)
                canvas.drawText("XENOS", width / 2f - 5f, height / 2f + 5f, paint)
                paint.color = themeColor
                canvas.drawText("XENOS", width / 2f, height / 2f, paint)
                val gapPaint = Paint().apply { color = android.graphics.Color.rgb(2, 2, 2) }
                val bandTop = height / 2f - width * 0.14f * 0.75f
                repeat(4) { i ->
                    val gapY = bandTop + width * 0.14f * 0.9f * (i + 0.5f) / 4f
                    canvas.drawRect(width / 2f - width * 0.14f * 1.9f, gapY - 3f, width / 2f + width * 0.14f * 1.9f, gapY + 3f, gapPaint)
                }
                hudCorners()
            }
            else -> hudCorners()
        }
        return bmp
    }
}
