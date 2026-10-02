package com.example.scifilauncher

import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat

/**
 * Real safety tool, two modes:
 * - MAP: live GPS position on a real satellite+3D-buildings Mapbox map (Mapbox GL JS in a
 *   WebView, same asset-loading pattern GlobeActivity already proved works).
 * - AR: the phone's own live rear camera feed with a compass heading + live lat/lng overlaid -
 *   the only genuinely "live, real cars and people" option that actually exists (no map/satellite
 *   service provides live drone-style footage of arbitrary locations - confirmed and explained to
 *   the user directly, this is the honest alternative).
 * Built for a genuinely different purpose than the decorative Globe - "if someone gets lost and
 * has no way to know where to go, they can use it to find their current location and move based
 * on it" (user's own words). Separate screen, doesn't touch GlobeActivity at all.
 */
class MyLocationActivity : ComponentActivity() {
    private var webView: android.webkit.WebView? = null
    private var locationManager: LocationManager? = null
    private var activeProvider: String? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null
    private var compassOverlay: CompassOverlayView? = null
    private var mapContainer: View? = null
    private var arContainer: View? = null
    private var arModeActive = false
    private var modeToggleButton: TextView? = null

    private val locationListener = LocationListener { location ->
        pushLocation(location)
    }

    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val rotationSensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            var azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
            if (azimuthDeg < 0) azimuthDeg += 360f
            compassOverlay?.headingDegrees = azimuthDeg
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private val REQUEST_LOCATION = 4271
    private val REQUEST_CAMERA = 4272

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))

        val themeColor = currentThemeColorArgb()
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        if (BuildConfig.MAPBOX_TOKEN.isBlank()) {
            container.addView(buildErrorLabel("Mapbox token not configured - add mapbox.token to local.properties"))
            container.addView(buildBackButton(themeColor))
            setContentView(container)
            return
        }

        val mapView = createMyLocationWebView(this, BuildConfig.MAPBOX_TOKEN)
        webView = mapView
        mapContainer = mapView
        container.addView(mapView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val ar = buildArView(themeColor)
        arContainer = ar
        ar.visibility = View.GONE
        container.addView(ar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        container.addView(buildBackButton(themeColor))
        container.addView(buildModeToggle(themeColor))
        setContentView(container)

        locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager
        ensureLocationPermissionThenStart()
    }

    private fun buildArView(themeColor: Int): View {
        val preview = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        previewView = preview
        val overlay = CompassOverlayView(this, themeColor).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        compassOverlay = overlay
        return FrameLayout(this).apply {
            addView(preview)
            addView(overlay)
        }
    }

    private fun buildModeToggle(themeColor: Int): TextView {
        val button = TextView(this).apply {
            text = "AR VIEW"
            setTextColor(themeColor)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.argb(128, 0, 0, 0))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(dp(16), dp(16), dp(16), dp(16))
            }
        }
        button.setOnClickListener { toggleArMode() }
        modeToggleButton = button
        return button
    }

    private fun toggleArMode() {
        if (!arModeActive) {
            val hasCamera = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (!hasCamera) {
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQUEST_CAMERA)
                return
            }
            enterArMode()
        } else {
            exitArMode()
        }
    }

    private fun enterArMode() {
        arModeActive = true
        mapContainer?.visibility = View.GONE
        arContainer?.visibility = View.VISIBLE
        modeToggleButton?.text = "MAP VIEW"
        startCameraPreview()
        sensorManager.registerListener(sensorListener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
    }

    private fun exitArMode() {
        arModeActive = false
        arContainer?.visibility = View.GONE
        mapContainer?.visibility = View.VISIBLE
        modeToggleButton?.text = "AR VIEW"
        sensorManager.unregisterListener(sensorListener)
        runCatching { cameraProvider?.unbindAll() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_LOCATION -> {
                if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                    startLiveLocationUpdates()
                } else {
                    Toast.makeText(this, "Location permission is required to show your position.", Toast.LENGTH_LONG).show()
                    webView?.evaluateJavascript("window.setLocationStatus && window.setLocationStatus('Location permission denied.');", null)
                }
            }
            REQUEST_CAMERA -> {
                if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                    enterArMode()
                } else {
                    Toast.makeText(this, "Camera permission is required for the AR view.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Rear camera, plain preview only - no analysis/recognition, this is just showing the user
     * their own real surroundings, same idea as any camera viewfinder app. */
    private fun startCameraPreview() {
        val preview = previewView ?: return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            val previewUseCase = Preview.Builder().build().also {
                it.setSurfaceProvider(preview.surfaceProvider)
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun ensureLocationPermissionThenStart() {
        val hasFine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            startLiveLocationUpdates()
        } else {
            requestPermissions(
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION),
                REQUEST_LOCATION
            )
        }
    }

    /** GPS_PROVIDER preferred for real accuracy; NETWORK_PROVIDER as a fallback (works indoors/
     * without a clear sky view, coarser). Real live updates, not a one-shot last-known fix - this
     * screen exists specifically so the position keeps refreshing while someone's actually moving
     * to find their way, unlike LocationHistoryWorker's periodic "good enough for a history
     * trail" snapshot. */
    private fun startLiveLocationUpdates() {
        val lm = locationManager ?: return
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            webView?.evaluateJavascript("window.setLocationStatus && window.setLocationStatus('Location is turned off on this device.');", null)
            return
        }
        activeProvider = provider
        runCatching {
            lm.requestLocationUpdates(provider, 3000L, 5f, locationListener)
            lm.getLastKnownLocation(provider)?.let { pushLocation(it) }
        }.onFailure {
            webView?.evaluateJavascript("window.setLocationStatus && window.setLocationStatus('Could not start location updates.');", null)
        }
    }

    private fun pushLocation(location: Location) {
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        webView?.evaluateJavascript(
            "window.updateMyLocation && window.updateMyLocation(${location.latitude}, ${location.longitude}, ${accuracy ?: "null"});",
            null
        )
        compassOverlay?.updateLocation(location.latitude, location.longitude, accuracy)
    }

    override fun onPause() {
        // Live updates only while this screen is actually visible - battery cost of continuous
        // GPS polling/camera/compass isn't worth paying while backgrounded.
        runCatching { locationManager?.removeUpdates(locationListener) }
        if (arModeActive) {
            sensorManager.unregisterListener(sensorListener)
            runCatching { cameraProvider?.unbindAll() }
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (activeProvider != null) startLiveLocationUpdates()
        if (arModeActive) {
            startCameraPreview()
            sensorManager.registerListener(sensorListener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun currentThemeColorArgb(): Int {
        val idx = getSharedPreferences("theme_prefs", MODE_PRIVATE).getInt("theme_index", 0)
        return CedalThemes[idx % CedalThemes.size].primary.let {
            Color.argb((it.alpha * 255).toInt(), (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt())
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
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(dp(16), dp(16), dp(16), dp(16))
        }
    }

    private fun buildErrorLabel(message: String): TextView = TextView(this).apply {
        text = message
        setTextColor(Color.parseColor("#4CAF50"))
        typeface = Typeface.MONOSPACE
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(24), dp(24), dp(24))
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
    }

    override fun onDestroy() {
        runCatching { locationManager?.removeUpdates(locationListener) }
        runCatching { sensorManager.unregisterListener(sensorListener) }
        runCatching { cameraProvider?.unbindAll() }
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}

/** Compass heading (rotation-vector sensor) + live lat/lng overlaid on the AR camera preview -
 * plain Canvas View, same shape as ScifiAccessibilityService's HandSkeletonOverlayView/
 * GlitchCoverView. Top bar shows cardinal direction + degrees, bottom bar shows the same
 * lat/lng/accuracy readout the MAP mode's status panel shows, so both modes carry the same real
 * "read this out to someone" safety info. */
private class CompassOverlayView(context: android.content.Context, themeColorArgb: Int) : View(context) {
    private val bgPaint = Paint().apply { color = Color.argb(160, 0, 0, 0) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColorArgb
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColorArgb
        style = Paint.Style.FILL
    }

    @Volatile var headingDegrees: Float = 0f
        set(value) { field = value; postInvalidate() }
    private var latitude: Double? = null
    private var longitude: Double? = null
    private var accuracyMeters: Float? = null

    fun updateLocation(lat: Double, lng: Double, accuracy: Float?) {
        latitude = lat
        longitude = lng
        accuracyMeters = accuracy
        postInvalidate()
    }

    private fun dpf(value: Float): Float = value * resources.displayMetrics.density

    private fun headingToCardinal(deg: Float): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = (((deg + 22.5f) / 45f).toInt()).mod(8)
        return dirs[idx]
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cx = w / 2f

        // Top compass bar
        val compassHeight = dpf(72f)
        canvas.drawRect(0f, 0f, w, compassHeight, bgPaint)
        textPaint.textSize = dpf(26f)
        canvas.drawText("${headingToCardinal(headingDegrees)}  ${headingDegrees.toInt()}°", cx, compassHeight * 0.5f, textPaint)

        val needleY = compassHeight * 0.82f
        val path = Path().apply {
            moveTo(cx, needleY - dpf(9f))
            lineTo(cx - dpf(7f), needleY + dpf(5f))
            lineTo(cx + dpf(7f), needleY + dpf(5f))
            close()
        }
        canvas.drawPath(path, needlePaint)

        // Bottom status bar - lat/lng/accuracy
        val lat = latitude
        val lng = longitude
        val statusHeight = dpf(64f)
        val top = height - statusHeight
        canvas.drawRect(0f, top, w, height.toFloat(), bgPaint)
        textPaint.textSize = dpf(13f)
        if (lat != null && lng != null) {
            val accuracyText = accuracyMeters?.let { "±${it.toInt()}m" } ?: "unknown"
            canvas.drawText("Lat: %.6f  Lng: %.6f".format(lat, lng), cx, top + dpf(24f), textPaint)
            canvas.drawText("Accuracy: $accuracyText", cx, top + dpf(46f), textPaint)
        } else {
            canvas.drawText("Getting your location...", cx, top + dpf(34f), textPaint)
        }
    }
}
