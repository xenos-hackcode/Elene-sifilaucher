package com.example.scifilauncher

import android.content.Context
import java.io.File

/** Real-signal (not guaranteed) checks for root/Magisk and whether SELinux is actually
 * enforcing. Worth being upfront about the limitation: this is an inherent cat-and-mouse game -
 * Magisk's own hiding features (Zygisk, DenyList) exist specifically to spoof exactly these
 * checks - so a clean result here is a real signal to weigh, not proof the device isn't rooted. */
object RootDetection {

    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su",
        "/system/bin/.ext/.su", "/system/usr/we-need-root/su-backup",
        "/data/local/bin/su", "/data/local/su", "/data/local/xbin/su",
        "/su/bin/su", "/system/app/Superuser.apk"
    )

    private val MAGISK_PACKAGES = listOf(
        "com.topjohnwu.magisk", "com.topjohnwu.magisk.debug",
        "io.github.huskydg.magisk", "io.github.vvb2060.magisk"
    )

    data class RootStatus(
        val suBinaryFound: Boolean,
        val magiskAppFound: Boolean,
        val selinuxEnforcing: Boolean?
    ) {
        val looksRooted: Boolean get() = suBinaryFound || magiskAppFound
    }

    fun check(context: Context): RootStatus {
        val suFound = SU_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }
        val magiskFound = MAGISK_PACKAGES.any { pkg ->
            runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
        }
        val enforcing = readSelinuxEnforcing()
        return RootStatus(suFound, magiskFound, enforcing)
    }

    /** Null if unreadable (common on stock, locked-down builds) rather than assuming permissive -
     * an unreadable result is genuinely unknown, not a pass or a fail. */
    private fun readSelinuxEnforcing(): Boolean? {
        val fromFile = runCatching {
            File("/sys/fs/selinux/enforce").readText().trim()
        }.getOrNull()
        if (fromFile != null) return fromFile == "1"

        return runCatching {
            val process = ProcessBuilder("getenforce").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            when {
                output.equals("Enforcing", ignoreCase = true) -> true
                output.equals("Permissive", ignoreCase = true) -> false
                else -> null
            }
        }.getOrNull()
    }
}
