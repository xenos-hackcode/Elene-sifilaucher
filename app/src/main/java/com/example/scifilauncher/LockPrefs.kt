package com.example.scifilauncher

import android.content.SharedPreferences

// Per-app lock temporarily disabled (kept as a single switch so it's a one-line revert): tapping
// an app icon no longer asks for fingerprint/PIN, even if one is set up. Everything else about
// the lock feature (PIN setup, 2-Step Verify, Locked Apps picker, recovery) still works normally
// from the Security screen - it's just not enforced on app launch right now.
const val APP_LOCK_ENFORCED = false

// When user has successfully passed PIN for a locked app
fun recordUnlock(prefs: SharedPreferences) {
    val now = System.currentTimeMillis()
    prefs.edit().putLong("last_unlock_time", now).apply()
}

// If this returns true -> show PIN dialog and treat app as "locked".
// If false -> skip PIN and treat app as "unlocked" within timer window.
fun shouldRequireUnlock(
    prefs: SharedPreferences,
    lockTimeoutMinutes: Int?
): Boolean {
    // OFF → always require PIN
    if (lockTimeoutMinutes == null) return true

    val lastUnlock = prefs.getLong("last_unlock_time", -1L)
    if (lastUnlock <= 0L) return true

    val diff = System.currentTimeMillis() - lastUnlock
    val timeoutMillis = lockTimeoutMinutes * 60_000L
    return diff >= timeoutMillis
}

// Read timer (minutes) from prefs. null = OFF.
fun loadLockTimeoutMinutes(prefs: SharedPreferences): Int? {
    val stored = prefs.getInt("lock_timeout_minutes", -1)
    return if (stored <= 0) null else stored
}

fun saveLockTimeoutMinutes(prefs: SharedPreferences, minutes: Int?) {
    prefs.edit()
        .putInt("lock_timeout_minutes", minutes ?: -1)
        .apply()
}

// Persist locked apps as a comma‑separated list
fun loadLockedApps(prefs: SharedPreferences): Set<String> {
    val raw = prefs.getString("locked_apps", "") ?: ""
    if (raw.isEmpty()) return emptySet()
    return raw.split(",").filter { it.isNotBlank() }.toSet()
}

fun saveLockedApps(prefs: SharedPreferences, set: Set<String>) {
    val raw = set.joinToString(",")
    prefs.edit().putString("locked_apps", raw).apply()
}

// Whether to hide notifications for locked apps while they are locked
fun loadHideLockedNotifications(prefs: SharedPreferences): Boolean {
    return prefs.getBoolean("hide_locked_notifications", true) // default ON
}

fun saveHideLockedNotifications(prefs: SharedPreferences, value: Boolean) {
    prefs.edit().putBoolean("hide_locked_notifications", value).apply()
}

// --- App PIN (fallback passcode when biometrics aren't available/fail) ---
// Reconstructed: this section (app PIN, 2-Step Verify, recovery) was uncommitted working-tree
// code lost to an accidental `git checkout`. Rebuilt from every call site still present in
// MainActivity.kt/SecurityScreen.kt/ScifiAccessibilityService.kt (keys match the still-intact
// MainActivity.saveAppPin member: "app_pin" / "app_pin_recovery").

fun loadAppPin(prefs: SharedPreferences): String? = prefs.getString("app_pin", null)

fun loadAppPinRecoveryAnswer(prefs: SharedPreferences): String? = prefs.getString("app_pin_recovery", null)

fun clearAppPin(prefs: SharedPreferences) {
    prefs.edit().remove("app_pin").remove("app_pin_recovery").apply()
}

// --- 2-Step Verify (spoken passphrase, matched via STT) ---

fun isTwoStepVerifyEnabled(prefs: SharedPreferences): Boolean =
    prefs.getBoolean("two_step_verify_enabled", false)

fun setTwoStepVerifyEnabled(prefs: SharedPreferences, enabled: Boolean) {
    prefs.edit().putBoolean("two_step_verify_enabled", enabled).apply()
}

fun loadVoicePassphrase(prefs: SharedPreferences): String? = prefs.getString("voice_passphrase", null)

fun saveVoicePassphrase(prefs: SharedPreferences, passphrase: String) {
    prefs.edit().putString("voice_passphrase", passphrase).apply()
}

// Whether a given app should currently be treated as locked - locked-apps membership, plus an
// unconditional override while Sequence Mode (anti-theft) lockdown is active.
fun isAppLockedRightNow(
    prefs: SharedPreferences,
    packageName: String,
    lockedApps: Set<String>
): Boolean {
    if (isSequenceModeActive(prefs)) return true
    return lockedApps.contains(packageName)
}

// Used by the PIN-recovery flow: wipes the PIN and drops every individually-locked app / unlock
// session, so recovering access doesn't leave old locks still armed against the new PIN.
fun clearAllLocksAndUnlocks(prefs: SharedPreferences) {
    prefs.edit()
        .remove("locked_apps")
        .remove("last_unlock_time")
        .apply()
}
