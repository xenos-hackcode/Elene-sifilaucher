package com.example.scifilauncher

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Compares this APK's real signing certificate against the one baked in at build time
 * (BuildConfig.EXPECTED_SIGNING_CERT_SHA256, computed directly from the actual signing key
 * used for this exact build - see debugCertSha256() in app/build.gradle.kts) to catch a
 * repackaged or resigned copy of the app running under this app's identity. Protects against
 * *someone else's* modified copy only - a build genuinely signed with the real key (even a
 * malicious one, if the key were ever stolen) passes this check legitimately; key security is
 * a separate problem this doesn't solve. Also worth being honest about: this project currently
 * signs with a debug key (no release keystore configured), which by Android convention is a
 * machine-local, regenerable identity rather than a stable production one - see the
 * 2026-07-31 signing-mismatch incident in planner/experience/experience.md for a real example
 * of exactly that instability. */
object AppIntegrityCheck {
    /** True if genuine, or if the expected hash couldn't be computed at build time (a fresh
     * checkout building for the first time) - deliberately fails open in that specific case
     * rather than flagging every fresh dev build as tampered. */
    fun isGenuine(context: Context): Boolean {
        val expected = BuildConfig.EXPECTED_SIGNING_CERT_SHA256
        if (expected.isBlank()) return true
        val actual = currentSigningCertSha256(context) ?: return false
        return actual.equals(expected, ignoreCase = true)
    }

    fun currentSigningCertSha256(context: Context): String? = runCatching {
        val pm = context.packageManager
        val certBytes: ByteArray = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo ?: return null
            val signers = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            signers?.firstOrNull()?.toByteArray() ?: return null
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray() ?: return null
        }
        MessageDigest.getInstance("SHA-256").digest(certBytes).joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
