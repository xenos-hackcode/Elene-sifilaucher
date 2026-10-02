package com.example.scifilauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import de.blinkt.openvpn.api.IOpenVPNAPIService
import de.blinkt.openvpn.api.IOpenVPNStatusCallback
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.DateFormat
import java.util.Date

/** VPN Gate discovery plus consent-based control of the separately installed OpenVPN engine. */
class NetworkProtectionActivity : ComponentActivity() {
    private lateinit var ui: TerminalToolsUi
    private lateinit var blockerStatus: TextView
    private lateinit var remoteStatus: TextView
    private lateinit var directoryStatus: TextView
    private lateinit var countryPicker: Spinner
    private lateinit var serverList: LinearLayout
    private lateinit var refreshButton: Button
    private var api: IOpenVPNAPIService? = null
    private var bound = false
    private var authorized = false
    private var callbackRegistered = false
    private var pendingServer: VpnGateServer? = null
    private var pendingDisconnect = false
    private var operation: Job? = null
    private val remoteLock = Mutex()
    private var servers = emptyList<VpnGateServer>()
    private var country = "All countries"
    private var favoritesOnly = false
    private var visibleCount = 30
    private val prefs by lazy { getSharedPreferences("vpn_directory", MODE_PRIVATE) }

    private val statusCallback = object : IOpenVPNStatusCallback.Stub() {
        override fun newStatus(uuid: String?, state: String?, message: String?, level: String?) {
            runOnUiThread {
                if (!isDestroyed) {
                    remoteStatus.text = when (level) {
                        "LEVEL_CONNECTED" -> "CONNECTED\n" + prefs.getString("last_server", "OpenVPN")
                        "LEVEL_NOTCONNECTED" -> "DISCONNECTED"
                        "LEVEL_AUTH_FAILED" -> "CONNECTION FAILED\nTry another server."
                        "LEVEL_CONNECTING_SERVER_REPLIED", "LEVEL_CONNECTING_NO_SERVER_REPLY_YET" -> "CONNECTING\nWaiting for the VPN server."
                        else -> "OPENVPN / " + (state?.take(40) ?: "Status unavailable")
                    }
                }
            }
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            api = IOpenVPNAPIService.Stub.asInterface(binder)
            lifecycleScope.launch {
                try {
                    val consent = withContext(Dispatchers.IO) { api?.prepare(packageName) }
                    if (consent == null) authorizeReady()
                    else remoteStatus.text = "SETUP REQUIRED\nAllow VPN controls to connect."
                } catch (_: Exception) { remoteStatus.text = "OpenVPN controls unavailable. Open the OpenVPN app to check setup." }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            api = null; authorized = false; callbackRegistered = false
            pendingServer = null; pendingDisconnect = false
            remoteStatus.text = "ENGINE UNAVAILABLE\nCheck OpenVPN or Android VPN settings."
        }
    }
    private val blockerConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) runCatching { TrackerBlockVpnService.start(this) }.onFailure { e -> showError(e) }
    }
    private val apiConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) lifecycleScope.launch { authorizeReady() }
        else { pendingServer = null; pendingDisconnect = false; remoteStatus.text = "VPN control permission declined." }
    }
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK && pendingServer != null) connectPending()
        else { pendingServer = null; remoteStatus.text = "Connection cancelled." }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = TerminalToolsUi(this, "03 / NETWORK", "Network control", "Local filtering. Worldwide VPN discovery. One clear disconnect.")
        val local = ui.card("01  /  TRACKER & AD BLOCKER")
        blockerStatus = ui.text(local, "", 20f, ui.accent)
        ui.text(local, "Filters DNS on this device. It does not hide your IP address. Apps using their own encrypted DNS may bypass filtering.")
        ui.button(local, "Enable local blocker") {
            AlertDialog.Builder(this).setTitle("Use local blocking?")
                .setMessage("Android allows one active VPN per profile. This replaces any remote VPN connection.")
                .setPositiveButton("Enable") { _, _ ->
                    runCatching {
                        val consent = VpnService.prepare(this)
                        if (consent == null) TrackerBlockVpnService.start(this) else blockerConsent.launch(consent)
                    }.onFailure { showError(it) }
                }.setNegativeButton("Cancel", null).show()
        }
        ui.button(local, "Turn blocker off") { TrackerBlockVpnService.stop(this) }
        lifecycleScope.launch {
            TrackerBlockVpnService.running.collect { running ->
                blockerStatus.text = if (running) "ON / LOCAL FILTER" else "OFF"
            }
        }

        val vpn = ui.card("02  /  REMOTE VPN")
        remoteStatus = ui.text(vpn, "Checking VPN engine…", 20f, ui.accent)
        ui.text(vpn, "Uses OpenVPN for Android by Arne Schwabe. Install it once and allow this app to control it. Server availability and countries change.")
        ui.button(vpn, "Install / open OpenVPN for Android") {
            val launch = packageManager.getLaunchIntentForPackage(ENGINE_PACKAGE)
            startActivity(launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=de.blinkt.openvpn")))
        }
        ui.button(vpn, "Allow VPN controls") { requestAuthorization() }
        ui.button(vpn, "Disconnect VPN & blocker", true) {
            TrackerBlockVpnService.stop(this)
            pendingServer = null
            operation?.cancel()
            pendingDisconnect = true
            if (authorized) disconnectRemote() else requestAuthorization()
        }
        ui.button(vpn, "Android VPN settings / always-on") { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
        ui.text(vpn, "If Android is set to block connections without a VPN, disconnecting can leave the internet blocked. Manage that setting above. Other VPN apps must be disconnected in their own controls.")

        val directory = ui.card("03  /  FREE SERVER DIRECTORY")
        directoryStatus = ui.text(directory, "Refresh to load available countries and servers.")
        ui.text(directory, "VPN Gate is volunteer-operated and keeps connection logs. Servers can be slow or disappear. Speeds and ping below are directory reports, not measurements from your phone.")
        ui.button(directory, "Read VPN Gate logging policy") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.vpngate.net/en/about_abuse.aspx")))
        }
        refreshButton = ui.button(directory, "Refresh free servers", true) { refreshDirectory() }
        countryPicker = Spinner(this).apply {
            adapter = ArrayAdapter(this@NetworkProtectionActivity, android.R.layout.simple_spinner_dropdown_item, listOf(country))
            backgroundTintList = android.content.res.ColorStateList.valueOf(ui.accent)
        }
        directory.addView(countryPicker, LinearLayout.LayoutParams(-1, ui.dp(56)))
        countryPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                (view as? TextView)?.setTextColor(Color.WHITE)
                country = parent?.getItemAtPosition(position)?.toString() ?: "All countries"
                prefs.edit().putString("country", country).apply()
                visibleCount = 30
                renderServers()
            }
        }
        val favorites = CheckBox(this).apply {
            text = "Favorites only"; setTextColor(Color.WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(ui.accent)
            setOnCheckedChangeListener { _, checked -> favoritesOnly = checked; visibleCount = 30; renderServers() }
        }
        directory.addView(favorites)
        serverList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        directory.addView(serverList)
        val options = ui.card("04  /  OTHER OPTIONS")
        ui.button(options, "Proton VPN Free · provider website") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://protonvpn.com/free-vpn")))
        }
        ui.text(options, "Provider accounts, country selection and free-plan limits are managed by the provider. The directory above only controls VPN Gate through OpenVPN.")
        bound = runCatching {
            bindService(Intent("de.blinkt.openvpn.api.IOpenVPNAPIService").setPackage(ENGINE_PACKAGE), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) remoteStatus.text = "ENGINE NOT INSTALLED\nInstall OpenVPN for Android, then reopen this screen."
    }

    private fun requestAuthorization() {
        val engine = api
        if (engine == null) { remoteStatus.text = "OpenVPN unavailable. Install/open it, then reopen Network control."; return }
        lifecycleScope.launch {
            try {
                val consent = withContext(Dispatchers.IO) { engine.prepare(packageName) }
                if (consent != null) apiConsent.launch(consent) else authorizeReady()
            } catch (e: Exception) { showError(e) }
        }
    }
    private suspend fun authorizeReady() {
        val engine = api ?: return
        try {
            if (!callbackRegistered) {
                withContext(Dispatchers.IO) { engine.registerStatusCallback(statusCallback) }
                callbackRegistered = true
            }
            authorized = true
            if (pendingDisconnect) disconnectRemote()
            else if (pendingServer != null) prepareRemote()
        } catch (e: Exception) { authorized = false; showError(e) }
    }
    private fun prepareRemote() {
        operation?.cancel()
        operation = lifecycleScope.launch {
            try {
                val engine = api ?: error("OpenVPN unavailable")
                val consent = withContext(Dispatchers.IO) { engine.prepareVPNService() }
                if (pendingServer == null) return@launch
                if (consent != null) vpnConsent.launch(consent) else connectPending()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { pendingServer = null; showError(e) }
        }
    }
    private fun connectPending() {
        val server = pendingServer ?: return
        pendingServer = null
        operation = lifecycleScope.launch {
            try {
                remoteLock.withLock {
                    val engine = api ?: error("OpenVPN unavailable")
                    TrackerBlockVpnService.stop(this@NetworkProtectionActivity)
                    withTimeout(3000) { TrackerBlockVpnService.running.first { !it } }
                    prefs.edit().putString("last_server", server.country + " / " + server.ip).apply()
                    remoteStatus.text = "CONNECTING\n" + server.country
                    withContext(Dispatchers.IO) { engine.startVPN(server.profile) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(e) }
        }
    }
    private fun disconnectRemote() {
        pendingDisconnect = false
        operation = lifecycleScope.launch {
            try {
                remoteLock.withLock {
                    val engine = api ?: error("OpenVPN unavailable")
                    remoteStatus.text = "DISCONNECTING…"
                    withContext(Dispatchers.IO) { engine.disconnect() }
                    // Only the engine callback can report confirmed DISCONNECTED.
                }
            } catch (e: Exception) { showError(e) }
        }
    }
    private fun refreshDirectory() {
        refreshButton.isEnabled = false
        directoryStatus.text = "Loading the live VPN Gate directory…"
        lifecycleScope.launch {
            try {
                servers = withContext(Dispatchers.IO) { VpnGateDirectory.fetch() }
                val countries = listOf("All countries") + servers.map { it.country }.distinct().sorted()
                countryPicker.adapter = ArrayAdapter(this@NetworkProtectionActivity, android.R.layout.simple_spinner_dropdown_item, countries)
                val savedCountry = prefs.getString("country", "All countries")
                countryPicker.setSelection(countries.indexOf(savedCountry).coerceAtLeast(0))
                directoryStatus.text = servers.size.toString() + " servers • Updated " + DateFormat.getTimeInstance().format(Date())
                renderServers()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { directoryStatus.text = e.message ?: "Directory unavailable. Try again later." }
            finally { refreshButton.isEnabled = true }
        }
    }
    private fun renderServers() {
        if (!::serverList.isInitialized) return
        serverList.removeAllViews()
        val favorites = prefs.getStringSet("favorites", emptySet()).orEmpty()
        val matches = servers.filter { (country == "All countries" || it.country == country) && (!favoritesOnly || it.ip in favorites) }
            .sortedWith(compareByDescending<VpnGateServer> { it.ip in favorites }.thenByDescending { it.speedMbps })
        matches.take(visibleCount).forEach { server ->
            val ping = server.pingMs?.toString() ?: "?"
            ui.text(serverList, server.country + " / " + server.ip, 17f, ui.accent)
            ui.text(serverList, server.speedMbps.toString() + " Mbps • " + ping + " ms • " + server.sessions + " sessions", 12f)
            ui.button(serverList, "Connect to " + server.country) {
                AlertDialog.Builder(this).setTitle("Connect through " + server.country + "?")
                    .setMessage("This volunteer VPN Gate server can log your connection. It replaces local blocking or any current VPN. Continue?")
                    .setPositiveButton("Connect") { _, _ ->
                        pendingServer = server; pendingDisconnect = false
                        if (authorized) prepareRemote() else requestAuthorization()
                    }.setNegativeButton("Cancel", null).show()
            }
            ui.button(serverList, if (server.ip in favorites) "Remove favorite" else "Save favorite") {
                val updated = favorites.toMutableSet()
                if (!updated.add(server.ip)) updated.remove(server.ip)
                prefs.edit().putStringSet("favorites", updated).apply()
                renderServers()
            }
        }
        if (matches.isEmpty()) ui.text(serverList, if (servers.isEmpty()) "No servers loaded yet." else "No matching servers. Refresh or change the filter.")
        if (matches.size > visibleCount) ui.button(serverList, "Show more servers") { visibleCount += 30; renderServers() }
    }
    private fun showError(error: Throwable) {
        if (!isDestroyed) {
            remoteStatus.text = "ACTION FAILED\n" + (error.message?.take(160) ?: "Try again or open Android VPN settings.")
        }
    }
    override fun onDestroy() {
        pendingServer = null
        if (callbackRegistered) runCatching { api?.unregisterStatusCallback(statusCallback) }
        if (bound) runCatching { unbindService(connection) }
        api = null
        super.onDestroy()
    }
    companion object { private const val ENGINE_PACKAGE = "de.blinkt.openvpn" }
}
