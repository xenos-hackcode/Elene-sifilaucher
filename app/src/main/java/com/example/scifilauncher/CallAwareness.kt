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

/** Same reliability caveats as attemptAnswerCall - TelecomManager.endCall()'s actual permission
 * requirements for a non-default-dialer app are less clearly documented than acceptRingingCall's,
 * so this is even more of an "attempt it, see what really happens" than that one. */
fun attemptEndCall(context: Context): Boolean {
    XenosNotificationListener.incomingVoipCall?.let { call ->
        return XenosNotificationListener.instance?.respondToVoipCall(accept = false) == true
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
