package com.example.scifilauncher

import android.telecom.Call
import android.telecom.CallScreeningService

/** The only legitimate way for a non-default-dialer app to reject a ringing (not yet answered)
 * call - TelecomManager has acceptRingingCall() but no reject/decline equivalent for third-party
 * apps, which is deliberate on Android's part (silently rejecting incoming calls is more
 * sensitive than accepting one). Requires this app to hold RoleManager.ROLE_CALL_SCREENING (the
 * "Caller ID & spam" role, requested via CallAwareness.kt) - the user has to grant that through a
 * real system prompt, same as any other role, not something Device Owner can silently grant.
 *
 * Real, disclosed limitation: this API is designed for an *instant* automatic decision the
 * moment a call starts ringing (Android expects a response within ~5s or treats the app as if it
 * weren't there and lets the call ring normally) - not for "wait several seconds while the user
 * hears an announcement and decides". Deliberately does NOT respond immediately here, so the call
 * rings normally by default (same as if this service didn't exist) and the existing
 * announce-then-wait-for-a-spoken-decision flow still works - respondToCall() is only ever
 * called later, from attemptEndCall(), once the user actually says to decline. Whether a response
 * called well after the ~5s window still takes effect is genuinely unconfirmed until tested with
 * a real call. */
class EleneCallScreeningService : CallScreeningService() {

    companion object {
        @Volatile
        var pendingRingingCall: Call.Details? = null

        /** Rejects the call currently held by this service, if any. Returns false (rather than
         * throwing) if there's nothing pending or the late response doesn't take effect. */
        fun rejectPendingCall(): Boolean {
            val instance = activeInstance ?: return false
            val details = pendingRingingCall ?: return false
            return runCatching {
                instance.respondToCall(
                    details,
                    CallResponse.Builder()
                        .setDisallowCall(true)
                        .setRejectCall(true)
                        .setSkipCallLog(false)
                        .setSkipNotification(false)
                        .build()
                )
                pendingRingingCall = null
                true
            }.getOrDefault(false)
        }

        @Volatile
        private var activeInstance: EleneCallScreeningService? = null
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    override fun onScreenCall(callDetails: Call.Details) {
        pendingRingingCall = callDetails
        // No respondToCall() here on purpose - see class doc. The call keeps ringing normally;
        // this service only ever acts if attemptEndCall() calls rejectPendingCall() later.
    }
}
