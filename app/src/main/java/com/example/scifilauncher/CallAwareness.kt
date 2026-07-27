package com.example.scifilauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

/** Attempts to answer a currently-ringing call via the public TelecomManager API, WITHOUT this
 * app becoming the default dialer (a much bigger commitment this app doesn't make). Confirmed
 * during planning: this path is not guaranteed to work on every OEM's Telecom stack (Samsung
 * One UI specifically has unconfirmed reliability here) - only ever called in direct response to
 * an explicit "pick it up"/"answer it", never automatically, and the caller should treat a
 * `false`/exception result as "didn't answer, nothing more this app can do" rather than retry
 * loop. If this turns out not to work reliably on real hardware, the next step is observing what
 * actually happens on a real incoming call and building an accessibility-tap fallback from real
 * data, not another guess. */
fun attemptAnswerCall(context: Context): Boolean {
    // A ringing VoIP call (WhatsApp/etc.) never goes through TelecomManager at all - if one is
    // currently ringing, accept it via its own notification's real Accept action instead.
    XenosNotificationListener.incomingVoipCall?.let { call ->
        return XenosNotificationListener.instance?.respondToVoipCall(accept = true) == true
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
        return false
    }
    return runCatching {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return false
        telecomManager.acceptRingingCall()
        true
    }.getOrDefault(false)
}

/** Three distinct paths, tried in order, because "decline a call" means something different
 * depending on what kind of call and what state it's in:
 * 1. A ringing VoIP call - declined via its own notification's real Decline action.
 * 2. A ringing cellular call - TelecomManager has no reject-ringing API for third-party apps at
 *    all (confirmed, not assumed - see EleneCallScreeningService), so this is the only
 *    legitimate path, and only works once the user has granted ROLE_CALL_SCREENING.
 * 3. Anything else (an already-answered/active cellular call, or the screening role isn't
 *    granted) - falls back to TelecomManager.endCall(), with the same "attempt it, see what
 *    really happens" caveat as attemptAnswerCall - its real permission requirements for a
 *    non-default-dialer app are less clearly documented than acceptRingingCall's. */
fun attemptEndCall(context: Context): Boolean {
    XenosNotificationListener.incomingVoipCall?.let {
        return XenosNotificationListener.instance?.respondToVoipCall(accept = false) == true
    }
    if (EleneCallScreeningService.pendingRingingCall != null) {
        if (EleneCallScreeningService.rejectPendingCall()) return true
        // Fall through to endCall() below only if the screening response didn't take (e.g. past
        // its window) - worth trying rather than giving up outright.
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
        return false
    }
    return runCatching {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return false
        @Suppress("DEPRECATION")
        telecomManager.endCall()
        true
    }.getOrDefault(false)
}

/** Whether this app currently holds the "Caller ID & spam" role (RoleManager.ROLE_CALL_SCREENING)
 * - required for attemptEndCall to have any real chance of declining a ringing cellular call.
 * Unlike the dangerous permissions elsewhere in this app, Device Owner cannot silently grant a
 * role - it always requires the real system prompt launched via requestCallScreeningRoleIntent. */
fun hasCallScreeningRole(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager ?: return false
    return runCatching { roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING) }.getOrDefault(false)
}

/** The real system intent that shows the user the actual role-grant prompt - must be launched
 * from an Activity via registerForActivityResult, this app cannot grant this on its own behalf. */
fun requestCallScreeningRoleIntent(context: Context): android.content.Intent? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager ?: return null
    if (!roleManager.isRoleAvailable(android.app.role.RoleManager.ROLE_CALL_SCREENING)) return null
    return roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING)
}

/** Briefly wakes the screen for an incoming-call announcement, the way a real dialer app would -
 * without this, an announcement could play with the screen off and easily go unnoticed. Uses the
 * deprecated wake-lock flags deliberately: there's no Activity here to use the modern
 * setShowWhenLocked/setTurnScreenOn APIs from, and this is exactly the situation those old flags
 * still exist for. Auto-releases after 8s regardless, so it can never hold the screen on stuck. */
@Suppress("DEPRECATION")
fun wakeScreenBriefly(context: Context) {
    runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wakeLock = pm.newWakeLock(
            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                android.os.PowerManager.ON_AFTER_RELEASE,
            "SciFiLauncher:IncomingCallWake"
        )
        wakeLock.acquire(8000L)
    }
}
