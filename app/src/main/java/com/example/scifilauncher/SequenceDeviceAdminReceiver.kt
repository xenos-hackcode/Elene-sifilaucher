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

        /** Narrow, automated-only keyguard bypass - exists for exactly one caller
         * (SequenceAlertWorker's WhatsApp send), never a standing "skip the lock screen"
         * shortcut a person could trigger. No human ever taps anything through this: it's
         * disabled right before the automated send starts and the CALLER is responsible for
         * re-enabling it the instant that send finishes (success or failure), via the SAME
         * callback that reports the send result - never left disabled longer than that one
         * operation actually needs. Requires real Device Owner status (setKeyguardDisabled is
         * a device-owner-only API), not just an active admin. Returns whether the call actually
         * took effect, so the caller knows whether a matching re-enable call is needed. */
        fun setKeyguardDisabledTemporarily(context: Context, disabled: Boolean): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
            if (!dpm.isDeviceOwnerApp(context.packageName)) return false
            return runCatching { dpm.setKeyguardDisabled(componentName(context), disabled) }.getOrDefault(false)
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
