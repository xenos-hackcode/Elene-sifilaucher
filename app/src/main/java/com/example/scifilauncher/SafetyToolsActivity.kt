package com.example.scifilauncher

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

class SafetyToolsActivity : Activity() {
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = TerminalToolsUi(this, "01 / GUARD", "Safety console", "Your device. Your controls. A clear plan before you need it.")
        val timer = ui.card("01  /  CHECK-IN")
        status = ui.text(timer, "", 22f, ui.accent)
        ui.text(timer, "A private reminder on this phone. No one is contacted automatically.")
        ui.text(timer, "REMIND ME IN / MINUTES", 11f, ui.accent)
        val minutes = ui.input(timer, "1 to 1440 minutes", "15", 8143).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        ui.button(timer, "Arm check-in", true) {
            SafetyCheckIn.arm(this, minutes.text.toString().toIntOrNull() ?: 0)
            refresh()
        }
        ui.button(timer, "I'm safe / cancel") { SafetyCheckIn.cancel(this); refresh() }
        val access = ui.card("02  /  ALERT READINESS")
        ui.text(access, "Enable notifications and alarm access before arming. Sound follows your notification and Do Not Disturb settings.")
        ui.button(access, "Notification settings") {
            if (Build.VERSION.SDK_INT >= 33 && !SafetyCheckIn.prepare(this)) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 81)
            } else if (Build.VERSION.SDK_INT >= 26) {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } else {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        ui.button(access, "Alarms & reminders") {
            if (Build.VERSION.SDK_INT >= 31) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "Alarm access is already available on this Android version.", Toast.LENGTH_SHORT).show()
            }
        }
        val help = ui.card("03  /  QUICK ACCESS")
        ui.button(help, "Open phone dialer") { startActivity(Intent(Intent.ACTION_DIAL)) }
        ui.button(help, "Show my location") { startActivity(Intent(this, MyLocationActivity::class.java)) }
        val defense = ui.card("04  /  DEVICE DEFENSE")
        ui.text(defense, if (getSystemService(KeyguardManager::class.java).isDeviceSecure)
            "System screen lock configured" else "Screen lock needs attention", 18f, ui.accent)
        ui.button(defense, "Android security settings") { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
        ui.button(defense, "Review VPN connections") { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
        ui.text(ui.content, "LOCAL REMINDER ONLY", 11f, ui.accent)
        ui.text(ui.content, "This does not detect danger or call for help. Restarting clears the timer. Force-stop, revoked permissions, a powered-off phone, or device settings can prevent alerts. Re-arm after restarting.", 12f)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val deadline = SafetyCheckIn.deadline(this)
        status.text = if (deadline == 0L) "STANDBY\nNo check-in armed" else {
            val formatted = DateFormat.getDateTimeInstance().format(Date(deadline))
            "CHECK-IN SET\n$formatted"
        }
    }
}
