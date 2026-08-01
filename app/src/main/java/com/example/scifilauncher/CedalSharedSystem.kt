package com.example.scifilauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest

private const val TAG = "CedalSharedSystem"
const val CEDAL_SHARE_PERMISSION = "com.xhacker.cedal.SHARE_SYSTEM"
const val CEDAL_SYNC_ACTION = "com.xhacker.cedal.action.SYNC"

/** Known Cedal package names - deliberately a short, explicit list, not a broad device scan. */
val CEDAL_KNOWN_SIBLINGS = listOf(
    "com.xhacker.cedalmobiledev",
    "com.xhacker.cedalsmsrelay"
)

/**
 * Legitimate, scoped cross-app coordination between apps signed with the same certificate:
 * checks only a known, explicit package list (no QUERY_ALL_PACKAGES / broad device probing),
 * and gates the actual data exchange behind a signature-level permission so only apps signed
 * with this same certificate can ever send or receive on this channel. Off by default; the
 * user must explicitly enable it in Security, with a plain-language explanation of what it does.
 */
object CedalSharedSystem {

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("cedal_shared_prefs", Context.MODE_PRIVATE)

    fun isEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean("cedal_shared_system_enabled", false)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean("cedal_shared_system_enabled", enabled).apply()
    }

    /** Which known sibling packages are installed AND signed with our same certificate. */
    fun findInstalledSiblings(context: Context): List<String> {
        return CEDAL_KNOWN_SIBLINGS.filter { pkg ->
            runCatching {
                context.packageManager.getPackageInfo(pkg, 0)
                isVerifiedSibling(context, pkg)
            }.getOrDefault(false)
        }
    }

    /** Sends our version info + any [flags] to every verified, installed sibling. No-op if
     * the user hasn't enabled this, or no verified siblings are present. */
    fun broadcastToSiblings(context: Context, flags: Map<String, String> = emptyMap()) {
        val sharedPrefs = prefs(context)
        if (!isEnabled(sharedPrefs)) return

        val siblings = findInstalledSiblings(context)
        if (siblings.isEmpty()) {
            Log.d(TAG, "No installed/verified siblings to broadcast to")
            return
        }

        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"

        siblings.forEach { pkg ->
            val intent = Intent(CEDAL_SYNC_ACTION).apply {
                setPackage(pkg)
                putExtra("from_package", context.packageName)
                putExtra("version_name", versionName)
                flags.forEach { (k, v) -> putExtra("flag_$k", v) }
            }
            runCatching {
                context.sendBroadcast(intent, CEDAL_SHARE_PERMISSION)
                Log.d(TAG, "Sync broadcast sent to $pkg")
            }.onFailure { Log.e(TAG, "Failed to broadcast to $pkg", it) }
        }
    }

    fun getFlag(context: Context, key: String): String? =
        prefs(context).getString("flag_$key", null)

    /** Last-known versions reported by each sibling, from the most recent sync received. */
    fun loadKnownSiblingVersions(context: Context): Map<String, String> {
        val sharedPrefs = prefs(context)
        return CEDAL_KNOWN_SIBLINGS.mapNotNull { pkg ->
            sharedPrefs.getString("version_$pkg", null)?.let { pkg to it }
        }.toMap()
    }

    /** Re-verifies a claimed sender's actual installed signature against our own, rather than
     * trusting the self-reported package name alone. Defense-in-depth for the case where an
     * OS-level bug ever let something bypass the manifest-level signature permission check -
     * BroadcastReceiver.onReceive has no direct calling-UID API the way a Binder call does, so
     * this is the strongest verification actually available here. */
    fun isVerifiedSibling(context: Context, packageName: String): Boolean {
        if (packageName !in CEDAL_KNOWN_SIBLINGS) return false
        val ours = signatureHash(context, context.packageName) ?: return false
        val theirs = signatureHash(context, packageName) ?: return false
        return ours == theirs
    }

    private fun signatureHash(context: Context, packageName: String): String? {
        return runCatching {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
            } ?: return null

            val digest = MessageDigest.getInstance("SHA-256")
            signatures.joinToString(",") { sig ->
                digest.reset()
                digest.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
            }
        }.getOrNull()
    }
}

/** Receives sync broadcasts from verified same-signature Cedal sibling apps. This receiver
 * requires CEDAL_SHARE_PERMISSION (signature-level) to be reached at all, so only apps signed
 * with this same certificate could have sent the broadcast in the first place. */
class CedalSharedSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CEDAL_SYNC_ACTION) return

        val fromPackage = intent.getStringExtra("from_package") ?: return
        if (!CedalSharedSystem.isVerifiedSibling(context, fromPackage)) {
            Log.w(TAG, "Rejected sync claiming to be from $fromPackage - signature didn't match")
            return
        }

        val prefs = CedalSharedSystem.prefs(context)
        if (!CedalSharedSystem.isEnabled(prefs)) return

        val editor = prefs.edit()
        intent.getStringExtra("version_name")?.let { editor.putString("version_$fromPackage", it) }
        intent.extras?.keySet()
            ?.filter { it.startsWith("flag_") }
            ?.forEach { key -> intent.getStringExtra(key)?.let { editor.putString(key, it) } }
        editor.apply()

        Log.d(TAG, "Synced from $fromPackage")
    }
}
