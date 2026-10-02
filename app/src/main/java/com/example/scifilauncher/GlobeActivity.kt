package com.example.scifilauncher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * Real, plain-View-hosted globe screen - not a Compose composable. Confirmed live (isolation
 * test, see planner/done.md) that this exact WebView/WebGL setup renders correctly here while
 * staying solid black when embedded via Jetpack Compose's AndroidView on this device - a genuine
 * Compose+hardware-accelerated-WebView compositing bug, not anything wrong with the Three.js
 * scene, asset loading, or WebGL itself (all independently confirmed working via console
 * evidence before this was built). This is the real screen now, not a diagnostic fallback.
 */
class GlobeActivity : Activity() {
    companion object {
        @Volatile
        private var activeInstance: GlobeActivity? = null

        // A held pinch can re-trigger far faster than a human taps a chip; each trigger is a real
        // billed Mapbox request, so this is deliberately much longer than HandGestureService's
        // general GESTURE_COOLDOWN_MS (tuned for responsive swipe/scroll, not for a per-call cost).
        private const val DETAIL_ZOOM_COOLDOWN_MS = 900L

        /** Hand-gesture pinch (HandGestureService.dispatchZoom) routes here whenever the Globe
         * screen is active. If the DETAIL panel (Mapbox satellite close-up) is open on top of the
         * globe, pinch should zoom THAT - the same +/- chips a tap would trigger - rather than the
         * 3D camera underneath it, which the user can't even see right now. Falls back to the
         * normal camera zoom otherwise. */
        fun zoomActiveGlobe(zoomIn: Boolean): Boolean {
            val activity = activeInstance ?: return false
            activity.runOnUiThread {
                val detailZoom = activity.detailZoomTrigger
                if (detailZoom != null) {
                    detailZoom(zoomIn)
                } else {
                    activity.webView?.evaluateJavascript(
                        "window.zoomGlobe && window.zoomGlobe(${if (zoomIn) "true" else "false"});",
                        null
                    )
                }
            }
            return true
        }
    }

    private data class Country(val name: String, val cca2: String, val lat: Double, val lng: Double)

    private var webView: android.webkit.WebView? = null
    private var locationLabel: TextView? = null
    private var countryNameLabel: TextView? = null
    private var rootContainer: FrameLayout? = null
    private var searchPickerView: View? = null
    private var detailPanelView: View? = null
    private var networkInfoPanelView: View? = null
    /** Non-null only while the DETAIL panel is open - lets the companion object's
     * zoomActiveGlobe() (called from HandGestureService's pinch handling) drive the panel's own
     * zoom chips instead of the 3D globe camera underneath it. */
    private var detailZoomTrigger: ((Boolean) -> Unit)? = null
    private var selectedCountry: Country? = null
    /** BORDERS toggle state (Globe map stage 2) - off by default per Codex's agreed constraint;
     * globe.js only fetches/builds the border line geometry the first time this flips on. */
    private var bordersVisible = false
    /** Whichever location was most recently selected via COUNTRY, NETWORK, or a double-tap - what
     * the DETAIL panel (Mapbox Static Images, Stage A) fetches for. */
    private var selectedLat: Double? = null
    private var selectedLng: Double? = null
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val countries: List<Country> by lazy { loadCountries() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))

        val themeColor = currentThemeColorArgb()

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootContainer = container

        webView = createGlobeWebView(this) { lat, lng ->
            showLocation(lat, lng)
        }.also { view ->
            container.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        container.addView(buildBackButton(themeColor))
        container.addView(buildTopRightControls(themeColor))
        locationLabel = buildLocationLabel(themeColor).also { container.addView(it) }
        countryNameLabel = buildCountryNameLabel().also { container.addView(it) }

        setContentView(container)
    }

    override fun onResume() {
        super.onResume()
        activeInstance = this
    }

    override fun onPause() {
        if (activeInstance === this) activeInstance = null
        super.onPause()
    }

    private fun currentThemeColorArgb(): Int {
        val idx = getSharedPreferences("theme_prefs", MODE_PRIVATE).getInt("theme_index", 0)
        return CedalThemes[idx % CedalThemes.size].primary.let {
            Color.argb(
                (it.alpha * 255).toInt(), (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildBackButton(themeColor: Int): TextView = TextView(this).apply {
        text = "◂ BACK"
        setTextColor(themeColor)
        textSize = 14f
        typeface = Typeface.MONOSPACE
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply {
            setColor(Color.argb(128, 0, 0, 0))
            cornerRadius = dp(8).toFloat()
        }
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { finish() }

        val topInset = statusBarInsetPx()
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(dp(16), dp(16) + topInset, dp(16), dp(16))
        }
    }

    /** MODEL (picks between the 3 real globe.js modes - see setGlobeModel there) and COLOR
     * (recolors the dotted modes' dots + rim - reserved for a later use beyond the picker itself,
     * built as a real working mechanism now regardless) stacked top-right, mirroring BACK's
     * top-left position. */
    private fun buildTopRightControls(themeColor: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        addView(pillButton(themeColor, "MODEL") { anchor -> showModelMenu(anchor) }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = dp(10)
        })
        addView(pillButton(themeColor, "COUNTRY") { showCountryPicker() }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = dp(10)
        })
        addView(pillButton(themeColor, "DETAIL") { showDetailPanel() }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = dp(10)
        })
        addView(buildBordersToggle(themeColor).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = dp(10)
        })
        addView(pillButton(themeColor, "NETWORK") { showNetworkPicker() })

        val topInset = statusBarInsetPx()
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(dp(16), dp(16) + topInset, dp(16), dp(16))
        }
    }

    private fun pillButton(themeColor: Int, label: String, onClick: (TextView) -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(themeColor)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.argb(128, 0, 0, 0))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick(this) }
        }

    /** A real toggle (unlike the other pill buttons, which open pickers/panels) - background
     * color reflects on/off state directly, since there's no separate panel to show that. */
    private fun buildBordersToggle(themeColor: Int): TextView {
        lateinit var button: TextView
        fun applyState() {
            button.background = GradientDrawable().apply {
                setColor(if (bordersVisible) Color.argb(160, 76, 175, 80) else Color.argb(128, 0, 0, 0))
                cornerRadius = dp(8).toFloat()
            }
        }
        button = pillButton(themeColor, "BORDERS") {
            bordersVisible = !bordersVisible
            webView?.evaluateJavascript("window.setBordersVisible && window.setBordersVisible(${bordersVisible});", null)
            applyState()
        }
        applyState()
        return button
    }

    private fun showModelMenu(anchor: TextView) {
        // "Dotted (glow rim)" removed from here - that look now belongs exclusively to the
        // Xenos screen (XenosActivity), not offered as a Globe mode choice too. "Textured"
        // removed per user request too - dots stay the plain white default now that COLOR (below)
        // is also gone. The underlying groups/mode machinery in globe.js are untouched for both -
        // Reactor still calls window.setGlobeModel('dotted-glow')/setGlobeColor directly, and
        // the specular texture "Textured" used is still loaded regardless (the dot lattice itself
        // samples it), so nothing else breaks by dropping these two from just this menu.
        val options = listOf(
            "Dotted (plain)" to "dotted-plain",
            "Satellite (NASA imagery)" to "satellite"
        )
        showSimplePopup(anchor, options.map { it.first }) { index ->
            val mode = options[index].second
            webView?.evaluateJavascript("window.setGlobeModel && window.setGlobeModel('$mode');", null)
        }
    }

    /** Stock android.widget.PopupMenu follows the SYSTEM theme (light on this device - confirmed
     * live: black text on a white popup, not matching the rest of this screen at all), and
     * restyling it properly needs a real style resource, not just code. Small custom dropdown
     * instead - black background, green monospace text, dismisses on outside-tap or selection -
     * matching the same look the COUNTRY picker already uses. */
    private fun showSimplePopup(anchor: View, labels: List<String>, onSelect: (Int) -> Unit) {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 10, 10, 10))
        }
        val popup = android.widget.PopupWindow(
            column,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.argb(235, 10, 10, 10))
                cornerRadius = dp(8).toFloat()
            })
            elevation = dp(8).toFloat()
        }
        labels.forEachIndexed { index, label ->
            column.addView(TextView(this).apply {
                text = label
                setTextColor(Color.parseColor("#4CAF50"))
                textSize = 14f
                typeface = Typeface.MONOSPACE
                setPadding(dp(20), dp(14), dp(20), dp(14))
                setOnClickListener {
                    onSelect(index)
                    popup.dismiss()
                }
            })
        }
        popup.showAsDropDown(anchor, 0, dp(4))
    }

    /** name+lat/lng only (mledoze/countries, public domain) - stage 1 of the Globe country-picker
     * plan agreed with Codex in combination.md. No borders/cities yet - deliberately deferred
     * until proven live, see that file for the full staged plan. */
    private fun loadCountries(): List<Country> = runCatching {
        val text = assets.open("countries.json").bufferedReader().use { it.readText() }
        val array = JSONArray(text)
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            Country(obj.getString("name"), obj.getString("cca2"), obj.getDouble("lat"), obj.getDouble("lng"))
        }
    }.getOrElse {
        android.util.Log.e("GlobeActivity", "Failed to load countries.json", it)
        emptyList()
    }

    /** Searchable, scrollable dropdown shared by COUNTRY and NETWORK - tap outside or pick an
     * entry to dismiss. `names` must be unique within the list passed in (both callers guarantee
     * this: country names globally, logged network SSIDs within NetworkHistory's saved entries). */
    private fun showSearchPicker(hint: String, names: List<String>, onSelect: (String) -> Unit) {
        if (searchPickerView != null) return
        val root = rootContainer ?: return

        // android.R.layout.simple_list_item_1's text color follows the current system/app theme,
        // not something set here - against this panel's dark background that can render as
        // invisible dark-on-dark text (confirmed live: list looked empty). Force it explicitly
        // rather than depend on theme resolution.
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as? TextView)?.apply {
                    setTextColor(Color.parseColor("#4CAF50"))
                    typeface = Typeface.MONOSPACE
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
                return view
            }
        }
        val listView = ListView(this).apply {
            this.adapter = adapter
            setBackgroundColor(Color.BLACK)
        }
        val searchBox = EditText(this).apply {
            this.hint = hint
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.argb(160, 40, 40, 40))
                cornerRadius = dp(8).toFloat()
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    adapter.filter.filter(s)
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            })
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(230, 10, 10, 10))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            // Consumes clicks within its own bounds (including empty padding, not just the
            // search box/list children) so they don't fall through to the overlay behind it and
            // trigger an unwanted dismiss - only taps genuinely outside the panel should close it.
            isClickable = true
            addView(searchBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) })
            layoutParams = FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.82f).toInt(),
                (resources.displayMetrics.heightPixels * 0.6f).toInt()
            ).apply { gravity = Gravity.CENTER }
        }

        // Full-screen catcher behind the panel - tapping it (anywhere outside the panel) dismisses,
        // same "click outside to close" behavior as the requested spec.
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setOnClickListener { dismissSearchPicker() }
            addView(panel)
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val visibleName = adapter.getItem(position)
            if (visibleName != null) onSelect(visibleName)
            dismissSearchPicker()
        }

        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        searchPickerView = overlay
    }

    private fun dismissSearchPicker() {
        val view = searchPickerView ?: return
        searchPickerView = null
        (view.parent as? ViewGroup)?.removeView(view)
    }

    /** Flies the globe camera to the selection via the same flyCameraToward animation double-click
     * already uses (globe.js's window.flyToCountry bridge - lat/lng only, works the same for a
     * state/province centroid as it does for a country). */
    private fun showCountryPicker() {
        showSearchPicker("Search countries...", countries.map { it.name }) { name ->
            val country = countries.firstOrNull { it.name == name } ?: return@showSearchPicker
            selectedCountry = country
            selectedLat = country.lat
            selectedLng = country.lng
            webView?.evaluateJavascript("window.flyToCountry && window.flyToCountry(${country.lat}, ${country.lng});", null)
            countryNameLabel?.apply {
                text = country.name
                visibility = View.VISIBLE
            }
        }
    }

    /** Networks this device has connected to (NetworkHistory.kt, logged by
     * ScifiAccessibilityService's WiFi callback) or is currently on - centroids only (one
     * location per network, captured at first connection), same fly-to/marker/label reuse as
     * COUNTRY. Selecting one also opens the INFO panel below with what's actually knowable about
     * it - no password field, that's genuinely not readable by any app (confirmed live via a real
     * shell-level test, not assumed - see combination.md). */
    private fun showNetworkPicker() {
        val entries = NetworkHistory.loadAll(this)
        if (entries.isEmpty()) {
            Toast.makeText(this, "No networks logged yet - connect to WiFi and check back.", Toast.LENGTH_SHORT).show()
            return
        }
        showSearchPicker("Search networks...", entries.map { it.ssid }) { name ->
            val entry = entries.firstOrNull { it.ssid == name } ?: return@showSearchPicker
            if (entry.lat != 0.0 || entry.lon != 0.0) {
                selectedLat = entry.lat
                selectedLng = entry.lon
                webView?.evaluateJavascript("window.flyToCountry && window.flyToCountry(${entry.lat}, ${entry.lon});", null)
            }
            countryNameLabel?.apply {
                text = entry.ssid
                visibility = View.VISIBLE
            }
            showNetworkInfoPanel(entry)
        }
    }

    private fun showNetworkInfoPanel(entry: NetworkEntry) {
        dismissNetworkInfoPanel()
        val root = rootContainer ?: return

        val dateFormat = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault())
        val hasLocation = entry.lat != 0.0 || entry.lon != 0.0
        val statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#4CAF50"))
            typeface = Typeface.MONOSPACE
            textSize = 13f
            text = buildString {
                append("SSID: ${entry.ssid}\n")
                append("BSSID: ${entry.bssid}\n")
                append("First seen: ${dateFormat.format(java.util.Date(entry.firstSeen))}\n")
                append("Last seen: ${dateFormat.format(java.util.Date(entry.lastSeen))}\n")
                append(if (hasLocation) "Location: %.5f, %.5f\n".format(entry.lat, entry.lon) else "Location: not recorded\n")
                append("Devices connected: checking...\n")
                append("\nPassword: not readable - no app (not even with elevated/Shizuku access) can read a saved WiFi password on modern Android. Confirmed directly, not assumed.")
            }
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 10, 10, 10))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
            addView(statusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            layoutParams = FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.9f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setOnClickListener { dismissNetworkInfoPanel() }
            addView(panel)
        }
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        networkInfoPanelView = overlay

        // Live device count only makes sense for the network we're ACTUALLY on right now - a
        // real ping sweep of the local subnet (LocalNetworkScanner), approximate by nature (see
        // its own doc comment for the real caveats), not attempted for historical entries.
        activityScope.launch {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as? android.net.wifi.WifiManager
            val currentBssid = runCatching { wifiManager?.connectionInfo?.bssid }.getOrNull()
            val suffix = if (currentBssid != null && currentBssid.equals(entry.bssid, ignoreCase = true)) {
                val count = LocalNetworkScanner.countOtherDevicesOnCurrentNetwork(this@GlobeActivity)
                if (count != null) "~$count other device(s) detected (approximate live scan)" else "Couldn't scan - not enough info to sweep this network"
            } else {
                "Not currently connected - live count only works for the network you're on right now"
            }
            statusText.text = statusText.text.toString().replace("Devices connected: checking...", "Devices connected: $suffix")
        }
    }

    private fun dismissNetworkInfoPanel() {
        val view = networkInfoPanelView ?: return
        networkInfoPanelView = null
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun buildLocationLabel(themeColor: Int): TextView = TextView(this).apply {
        visibility = android.view.View.GONE
        setTextColor(themeColor)
        textSize = 16f
        typeface = Typeface.MONOSPACE
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply {
            setColor(Color.argb(166, 0, 0, 0))
            cornerRadius = dp(12).toFloat()
        }
        setPadding(dp(18), dp(12), dp(18), dp(12))
        gravity = Gravity.CENTER

        val bottomInset = navigationBarInsetPx()
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setMargins(dp(24), dp(24), dp(24), dp(24) + bottomInset)
        }
    }

    /** Shows the name of whichever country the green marker (globe.js's countryMarker) is
     * currently highlighting - stays visible through the idle auto-spin, which is deliberately
     * never paused for a selection (the marker itself tracks the real location as a proper
     * object in the rotating globeRoot, so it doesn't need the label to compensate). */
    private fun buildCountryNameLabel(): TextView = TextView(this).apply {
        visibility = View.GONE
        setTextColor(Color.parseColor("#4CAF50"))
        textSize = 16f
        typeface = Typeface.MONOSPACE
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply {
            setColor(Color.argb(166, 0, 0, 0))
            cornerRadius = dp(12).toFloat()
        }
        setPadding(dp(18), dp(10), dp(18), dp(10))
        gravity = Gravity.CENTER

        val topInset = statusBarInsetPx()
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(16) + topInset
        }
    }

    /** Stage A of the "real houses/buildings" ask (see combination.md) - Mapbox Static Images
     * API, one plain HTTP GET per explicit tap, no live tile map/quad-tree/caching (Stage B,
     * declined by Codex for now - too much billing/engineering risk to build ahead of proof).
     * Stage A2 (this version): +/- zoom chips, each tap still exactly one bounded request for the
     * same lat/lng at a new zoom level - never a continuous fetch, never triggered by camera
     * movement. */
    private fun showDetailPanel() {
        val lat = selectedLat
        val lng = selectedLng
        if (lat == null || lng == null) {
            Toast.makeText(this, "Select a location first (COUNTRY, NETWORK, or double-tap the globe)", Toast.LENGTH_SHORT).show()
            return
        }
        if (BuildConfig.MAPBOX_TOKEN.isBlank()) {
            Toast.makeText(this, "Mapbox token not configured - add mapbox.token to local.properties", Toast.LENGTH_LONG).show()
            return
        }
        if (detailPanelView != null) return
        val root = rootContainer ?: return

        var currentZoom = MapboxStaticImageClient.DEFAULT_ZOOM

        val statusText = TextView(this).apply {
            text = "Loading satellite detail..."
            setTextColor(Color.parseColor("#4CAF50"))
            typeface = Typeface.MONOSPACE
            textSize = 14f
            gravity = Gravity.CENTER
        }
        val imageView = ImageView(this).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val zoomLabel = TextView(this).apply {
            text = "z$currentZoom"
            setTextColor(Color.parseColor("#4CAF50"))
            typeface = Typeface.MONOSPACE
            textSize = 13f
            gravity = Gravity.CENTER
        }

        fun fetchAt(zoom: Int) {
            statusText.text = "Loading satellite detail..."
            statusText.visibility = View.VISIBLE
            imageView.visibility = View.GONE
            activityScope.launch {
                when (val result = MapboxStaticImageClient.fetchDetailImage(lat, lng, zoom)) {
                    is MapboxStaticImageClient.Result.Success -> {
                        statusText.visibility = View.GONE
                        imageView.setImageBitmap(result.bitmap)
                        imageView.visibility = View.VISIBLE
                    }
                    MapboxStaticImageClient.Result.NoToken ->
                        statusText.text = "Mapbox token not configured."
                    MapboxStaticImageClient.Result.InvalidToken ->
                        statusText.text = "Mapbox rejected the token (invalid/expired)."
                    MapboxStaticImageClient.Result.RateLimited ->
                        statusText.text = "Rate limited by Mapbox - try again in a moment."
                    is MapboxStaticImageClient.Result.NetworkError ->
                        statusText.text = "Network error: ${result.message}"
                }
            }
        }

        // Shared by the tap chips below and detailZoomTrigger (hand-gesture pinch, see
        // zoomActiveGlobe in the companion object) - both are just "the user asked for one step
        // closer/farther," so both should behave identically.
        var lastZoomAdjustAtMs = 0L
        fun adjustZoom(zoomIn: Boolean) {
            // Each step here fires a real billed Mapbox request (fetchAt below) - fine for a tap,
            // but a held pinch gesture can re-fire many times a second since the hand-gesture
            // engine's own cooldown is deliberately short (tuned for responsive swipe/scroll
            // elsewhere, not for something with a per-call network cost). This cooldown is local
            // to the zoom step itself, not the general gesture engine, so it doesn't affect
            // anything else.
            val now = System.currentTimeMillis()
            if (now - lastZoomAdjustAtMs < DETAIL_ZOOM_COOLDOWN_MS) return
            lastZoomAdjustAtMs = now
            if (zoomIn) {
                if (currentZoom >= MapboxStaticImageClient.MAX_ZOOM) return
                currentZoom++
            } else {
                if (currentZoom <= MapboxStaticImageClient.MIN_ZOOM) return
                currentZoom--
            }
            zoomLabel.text = "z$currentZoom"
            fetchAt(currentZoom)
        }
        detailZoomTrigger = { zoomIn -> adjustZoom(zoomIn) }

        val zoomOutChip = zoomChipButton("− FARTHER") { adjustZoom(zoomIn = false) }
        val zoomInChip = zoomChipButton("+ CLOSER") { adjustZoom(zoomIn = true) }
        val zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(zoomOutChip)
            addView(zoomLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(14)
                marginEnd = dp(14)
                gravity = Gravity.CENTER_VERTICAL
            })
            addView(zoomInChip)
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 10, 10, 10))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
            addView(statusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(imageView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) })
            addView(zoomRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            layoutParams = FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.9f).toInt(),
                (resources.displayMetrics.heightPixels * 0.75f).toInt()
            ).apply { gravity = Gravity.CENTER }
        }
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setOnClickListener { dismissDetailPanel() }
            addView(panel)
        }
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        detailPanelView = overlay

        fetchAt(currentZoom)
    }

    private fun zoomChipButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.parseColor("#4CAF50"))
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply {
            setColor(Color.argb(160, 40, 40, 40))
            cornerRadius = dp(8).toFloat()
        }
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setOnClickListener { onClick() }
    }

    private fun dismissDetailPanel() {
        val view = detailPanelView ?: return
        detailPanelView = null
        detailZoomTrigger = null
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun showLocation(lat: Double, lng: Double) {
        selectedLat = lat
        selectedLng = lng
        val label = locationLabel ?: return
        // Real double-tap -> lat/lng pipeline, same one the Compose prototype proved end to end.
        // "map view" is now the DETAIL panel (Mapbox Static Images, Stage A) - tap DETAIL after
        // double-tapping a point to see it.
        label.text = "%.2f, %.2f\ndouble-tapped - tap DETAIL for satellite close-up".format(lat, lng)
        label.visibility = android.view.View.VISIBLE
    }

    // WindowInsets.Type is API 30+ - below that, margins just fall back to 0 (a real but minor
    // visual overlap risk on very old devices, not a crash risk either way since this is guarded).
    private fun statusBarInsetPx(): Int {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return 0
        return runCatching {
            window.decorView.rootWindowInsets
                ?.getInsets(android.view.WindowInsets.Type.statusBars())
                ?.top ?: 0
        }.getOrDefault(0)
    }

    private fun navigationBarInsetPx(): Int {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return 0
        return runCatching {
            window.decorView.rootWindowInsets
                ?.getInsets(android.view.WindowInsets.Type.navigationBars())
                ?.bottom ?: 0
        }.getOrDefault(0)
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        activityScope.cancel()
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        webView = null
        searchPickerView = null
        detailPanelView = null
        networkInfoPanelView = null
        detailZoomTrigger = null
        rootContainer = null
        super.onDestroy()
    }
}
