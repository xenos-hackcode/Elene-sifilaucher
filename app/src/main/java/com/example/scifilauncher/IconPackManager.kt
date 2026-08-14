package com.example.scifilauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import org.xmlpull.v1.XmlPullParser

data class IconPackInfo(val packageName: String, val label: String)

private const val ICON_PACK_PREFS = "icon_pack_prefs"
private const val KEY_SELECTED_PACK = "selected_pack"

/** Real, third-party icon pack apps (Icon Pack Studio, X Icon Changer, and every icon pack
 * published for Nova/Apex/etc.) declare an intent-filter for one or more of these actions so
 * compatible launchers can find them - a long-standing de facto Android standard, not something
 * invented for this app. Detecting these means any icon pack the user already has installed (or
 * builds themselves in one of those apps) shows up here automatically, no manual setup. */
private val ICON_PACK_ACTIONS = listOf(
    "com.novalauncher.THEME",
    "org.adw.launcher.THEMES",
    "com.anddoes.launcher.THEME",
    "com.teslacoilsw.launcher.THEME",
    "com.gau.go.launcherex.theme",
    "com.dlto.atom.launcher.THEME",
    "app.olauncher.THEME"
)

object IconPackManager {
    fun detectInstalledPacks(context: Context): List<IconPackInfo> {
        val pm = context.packageManager
        val found = linkedMapOf<String, IconPackInfo>()
        ICON_PACK_ACTIONS.forEach { action ->
            val results = runCatching { pm.queryIntentActivities(Intent(action), 0) }.getOrDefault(emptyList())
            results.forEach { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg !in found) {
                    found[pkg] = IconPackInfo(pkg, runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg))
                }
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    fun loadSelectedPack(context: Context): String? =
        context.getSharedPreferences(ICON_PACK_PREFS, Context.MODE_PRIVATE).getString(KEY_SELECTED_PACK, null)

    fun saveSelectedPack(context: Context, packageName: String?) {
        context.getSharedPreferences(ICON_PACK_PREFS, Context.MODE_PRIVATE).edit().apply {
            if (packageName == null) remove(KEY_SELECTED_PACK) else putString(KEY_SELECTED_PACK, packageName)
        }.apply()
        cachedPackPkg = null
        cachedMap = emptyMap()
    }

    // appfilter.xml is parsed once per pack selection (not per-icon) and cached in memory -
    // packs can list thousands of <item> entries, re-parsing per app icon would be real,
    // measurable jank across a 100+ app grid.
    private var cachedPackPkg: String? = null
    private var cachedMap: Map<String, String> = emptyMap()

    private fun mapFor(context: Context, iconPackPkg: String): Map<String, String> {
        if (cachedPackPkg == iconPackPkg) return cachedMap
        // Real evidence 2026-08-10: exported packs from Icon Pack Studio (and likely other
        // exporter tools) don't ship appfilter.xml as a compiled res/xml/ resource at all - it's
        // packaged as a res/raw/ resource (plain-text XML, not the compiled binary format
        // Resources.getXml() expects) and/or a plain assets/appfilter.xml file. Compiled res/xml/
        // is tried first since that's the standard most hand-built packs use; raw and assets are
        // real fallbacks for exporter-tool output, not speculative.
        val map = parseFromCompiledXml(context, iconPackPkg)
            .ifEmpty { parseFromRawResource(context, iconPackPkg) }
            .ifEmpty { parseFromAsset(context, iconPackPkg) }
        cachedPackPkg = iconPackPkg
        cachedMap = map
        return map
    }

    private fun parseFromCompiledXml(context: Context, iconPackPkg: String): Map<String, String> = runCatching {
        val res = context.packageManager.getResourcesForApplication(iconPackPkg)
        val xmlId = res.getIdentifier("appfilter", "xml", iconPackPkg)
        if (xmlId == 0) return@runCatching emptyMap<String, String>()
        parseAppfilter(res.getXml(xmlId))
    }.getOrDefault(emptyMap())

    private fun parseFromRawResource(context: Context, iconPackPkg: String): Map<String, String> = runCatching {
        val res = context.packageManager.getResourcesForApplication(iconPackPkg)
        val rawId = res.getIdentifier("appfilter", "raw", iconPackPkg)
        if (rawId == 0) return@runCatching emptyMap<String, String>()
        res.openRawResource(rawId).use { parseAppfilter(plainTextParser(it)) }
    }.getOrDefault(emptyMap())

    private fun parseFromAsset(context: Context, iconPackPkg: String): Map<String, String> = runCatching {
        val packContext = context.createPackageContext(iconPackPkg, 0)
        packContext.assets.open("appfilter.xml").use { parseAppfilter(plainTextParser(it)) }
    }.getOrDefault(emptyMap())

    private fun plainTextParser(input: java.io.InputStream): XmlPullParser {
        val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(input, "UTF-8")
        return parser
    }

    private fun parseAppfilter(parser: XmlPullParser): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                val component = parser.getAttributeValue(null, "component")
                val drawable = parser.getAttributeValue(null, "drawable")
                if (component != null && drawable != null) result[component] = drawable
            }
            event = parser.next()
        }
        return result
    }

    /** Resolves a themed icon for [componentName] from the selected pack, falling back to
     * [fallback] (the app's real icon) when the pack doesn't cover that app - no real-world
     * pack maps every installed app, so every other icon-pack-supporting launcher falls back
     * the same way rather than showing a blank/broken icon. */
    fun resolveIcon(context: Context, iconPackPkg: String?, componentName: ComponentName, fallback: Bitmap): Bitmap {
        if (iconPackPkg == null) return fallback
        val map = mapFor(context, iconPackPkg)
        val key = "ComponentInfo{${componentName.packageName}/${componentName.className}}"
        val drawableName = map[key] ?: return fallback
        return runCatching {
            val res = context.packageManager.getResourcesForApplication(iconPackPkg)
            val resId = res.getIdentifier(drawableName, "drawable", iconPackPkg)
            if (resId == 0) return@runCatching fallback
            val drawable = ResourcesCompat.getDrawable(res, resId, null) ?: return@runCatching fallback
            drawable.toBitmap()
        }.getOrDefault(fallback)
    }
}
