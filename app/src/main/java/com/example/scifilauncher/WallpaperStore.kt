package com.example.scifilauncher

import android.content.Context

// Launcher backgrounds: still images, looping video-style animations, and interactive scenes.
enum class WallpaperCategory { IMAGE, VIDEO, LIVE }

data class WallpaperOption(
    val id: String,
    val label: String,
    val category: WallpaperCategory
)

/** Designs available from Settings > Wallpaper. */
val WALLPAPER_CATALOG: List<WallpaperOption> = listOf(
    // VIDEO - looping animations, no interaction. "Calm"/"Fast" are real distinct speeds of the
    // same renderer (see VideoLoopBackgrounds.kt's `speed` parameter), not duplicate labels.
    WallpaperOption(id = "matrix", label = "Matrix", category = WallpaperCategory.VIDEO),
    WallpaperOption(id = "starfield_calm", label = "Starfield - Calm", category = WallpaperCategory.VIDEO),
    WallpaperOption(id = "starfield_fast", label = "Starfield - Fast", category = WallpaperCategory.VIDEO),
    WallpaperOption(id = "scanlines_calm", label = "Scanlines - Calm", category = WallpaperCategory.VIDEO),
    WallpaperOption(id = "scanlines_fast", label = "Scanlines - Fast", category = WallpaperCategory.VIDEO),
    WallpaperOption(id = "pulse_calm", label = "Pulse - Calm", category = WallpaperCategory.VIDEO),
    WallpaperOption(id = "pulse_fast", label = "Pulse - Fast", category = WallpaperCategory.VIDEO),
    WallpaperOption(id = "grid_calm", label = "Grid Tunnel - Calm", category = WallpaperCategory.VIDEO),
    WallpaperOption(id = "grid_fast", label = "Grid Tunnel - Fast", category = WallpaperCategory.VIDEO),
    WallpaperOption(id = "coderain_calm", label = "Code Rain - Calm", category = WallpaperCategory.VIDEO),
    WallpaperOption(id = "coderain_fast", label = "Code Rain - Fast", category = WallpaperCategory.VIDEO),
    WallpaperOption(id = "xenos_glitch_video", label = "Xenos Glitch (pulsing stems)", category = WallpaperCategory.VIDEO),
    WallpaperOption(id = "globe_video", label = "Globe (auto-rotate, B&W)", category = WallpaperCategory.VIDEO),

    // IMAGE - genuinely still, no animation at all.
    WallpaperOption(id = "solid", label = "Solid Glow", category = WallpaperCategory.IMAGE),
    WallpaperOption(id = "vignette", label = "Vignette", category = WallpaperCategory.IMAGE),
    WallpaperOption(id = "noise_static", label = "Noise", category = WallpaperCategory.IMAGE),
    WallpaperOption(id = "horizon", label = "Horizon Line", category = WallpaperCategory.IMAGE),
    WallpaperOption(id = "xenos_glitch_image", label = "Xenos Glitch", category = WallpaperCategory.IMAGE),
    WallpaperOption(id = "globe_image", label = "Globe (static, B&W)", category = WallpaperCategory.IMAGE),

    // LIVE - genuinely reacts to you. Eye/Xenos need the front camera (see WelcomeFaceSkeleton.kt);
    // the rest use only sensors/touch/mic, no camera involved at all.
    WallpaperOption(id = "shake", label = "Shake (Starfield blow-up)", category = WallpaperCategory.LIVE),
    WallpaperOption(id = "tilt", label = "Tilt (Starfield drift)", category = WallpaperCategory.LIVE),
    WallpaperOption(id = "touch", label = "Touch (Horizon scan)", category = WallpaperCategory.LIVE),
    WallpaperOption(id = "voice", label = "Voice", category = WallpaperCategory.LIVE),
    WallpaperOption(id = "xenos_glitch_touch", label = "Xenos Glitch (touch pulse)", category = WallpaperCategory.LIVE),
    WallpaperOption(id = "globe_live", label = "Globe (drag to rotate, B&W)", category = WallpaperCategory.LIVE),
    WallpaperOption(id = "welcome_eye", label = "Eye", category = WallpaperCategory.LIVE),
    WallpaperOption(id = "xenos_face", label = "Xenos", category = WallpaperCategory.LIVE)
)

private const val PREFS = "wallpaper_prefs"
private const val KEY_SELECTED = "selected_wallpaper_id"
private const val DEFAULT_WALLPAPER_ID = "matrix"

object WallpaperStore {
    fun selectedId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED, DEFAULT_WALLPAPER_ID) ?: DEFAULT_WALLPAPER_ID

    fun setSelected(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED, id).apply()
    }
}
