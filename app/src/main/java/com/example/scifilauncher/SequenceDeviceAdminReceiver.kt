package com.example.scifilauncher

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Enables real OS-level lockdown (the actual Android lockscreen, not just this app's
 * PIN gate) for Sequence Mode. Only uses force-lock and wipe-data policies.
 */
class SequenceDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context, SequenceDeviceAdminReceiver::class.java)

        fun isActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return false
            return dpm.isAdminActive(componentName(context))
        }

        /** Locks the real OS lockscreen immediately. No-op if device admin isn't active. */
        fun lockNow(context: Context) {
            if (!isActive(context)) return
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
            runCatching { dpm.lockNow() }
        }

        /** Factory-resets the entire device. Only called when the user has explicitly
         * opted into full-device wipe (see SequenceMode.kt) - not the default behavior. */
        fun wipeEntireDevice(context: Context) {
            if (!isActive(context)) return
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
            runCatching { dpm.wipeData(0) }
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    // Shown by Android itself when the user tries to turn this off via Settings > Device
    // Admin apps - the only place it can be turned off, since this app has no in-app toggle
    // for disabling it once enabled.
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Turning off OS-level lockdown removes Sequence Mode's ability to lock or wipe " +
                "this device if it's ever lost or stolen. Only continue if you're sure."
    }
}
