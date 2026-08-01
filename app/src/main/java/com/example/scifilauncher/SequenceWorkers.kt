package com.example.scifilauncher

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Fires every 12h while Sequence Mode is active; stops itself after the 2-day window. */
class SequenceAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun doWork(): Result {
        val lockPrefs = applicationContext.getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)

        if (!isSequenceModeActive(lockPrefs)) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(SEQUENCE_ALERT_WORK_NAME)
            return Result.success()
        }

        if (isAlertWindowExpired(lockPrefs)) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(SEQUENCE_ALERT_WORK_NAME)
            return Result.success()
        }

        val message = buildAlertMessage(lockPrefs)

        withContext(Dispatchers.IO) {
            resolveFamilyContacts(applicationContext).forEach { contact ->
                // Narrow, automated-only keyguard bypass (declined.md already ruled out any
                // *human-facing* lock-screen shortcut, since a thief benefits from that exactly
                // as much as the real owner does - this is different: nobody taps anything, the
                // keyguard is disabled only for the exact span this one automated WhatsApp send
                // needs the screen, and re-enabled the instant it reports done, via the same
                // callback that reports success/failure - never left disabled longer than that.
                val keyguardWasDisabled = SequenceDeviceAdminReceiver.setKeyguardDisabledTemporarily(applicationContext, true)
                val whatsAppSent = suspendCancellableCoroutine<Boolean> { cont ->
                    sendWhatsAppAlert(applicationContext, contact.phoneNumber, message) { sent ->
                        if (keyguardWasDisabled) {
                            SequenceDeviceAdminReceiver.setKeyguardDisabledTemporarily(applicationContext, false)
                        }
                        if (cont.isActive) cont.resume(sent) {}
                    }
                }
                val smsSent = sendSmsAlert(applicationContext, contact.phoneNumber, message)
                SystemEventLog.record(
                    applicationContext, "SequenceMode",
                    "Alert to ${contact.label}: WhatsApp ${if (whatsAppSent) "sent" else "attempted/failed"}, SMS ${if (smsSent) "sent" else "failed/unavailable"}"
                )
            }
        }

        return Result.success()
    }
}

/** One-shot, 10 minutes after MotionTheftDetector's "are you running?" alert - arms Sequence
 * Mode for real only if the owner never confirmed via fingerprint in that window. A no-op if
 * the alert was already resolved (confirmed, or Sequence Mode got armed some other way meanwhile). */
class MotionConfirmTimeoutWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val lockPrefs = applicationContext.getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)
        if (!isMotionAlertPending(lockPrefs)) return Result.success()

        resolveMotionAlert(lockPrefs)
        if (!isSequenceModeActive(lockPrefs)) {
            SystemEventLog.record(applicationContext, "SequenceMode", "Motion alert not confirmed in time - auto-arming")
            enterSequenceMode(applicationContext, lockPrefs)
        }
        return Result.success()
    }
}

/** One-shot, ~30 days after Sequence Mode started - wipes protected app data if never recovered. */
class SequenceWipeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val lockPrefs = applicationContext.getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)
        if (!isSequenceModeActive(lockPrefs)) return Result.success()

        performSequenceWipe(applicationContext)
        return Result.success()
    }
}
