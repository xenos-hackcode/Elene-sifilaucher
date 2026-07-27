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
