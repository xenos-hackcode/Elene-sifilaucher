package com.example.scifilauncher

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/** Shizuku lets this app run commands with real shell (ADB) privilege - the same access level
 * as `adb shell` - without root. It only works after the user explicitly activates the separate
 * Shizuku app (via wireless debugging pairing, typically redone after most reboots unless the
 * device is rooted) and grants this app permission through Shizuku's own prompt. Nothing here
 * works silently or without that setup - every function checks real availability first and
 * fails honestly rather than pretending to succeed. */
object ShizukuManager {
    private const val REQUEST_CODE = 7734

    @Volatile private var userService: IUserService? = null
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.example.scifilauncher", ShizukuUserService::class.java.name)
    ).daemon(false).processNameSuffix("shizuku").debuggable(true).version(1)

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            userService = if (binder.pingBinder()) IUserService.Stub.asInterface(binder) else null
        }
        override fun onServiceDisconnected(name: ComponentName) {
            userService = null
        }
    }

    /** Binds the privileged user service ahead of time (e.g. right after permission is
     * granted) so the first real command doesn't have to wait on the async bind. Safe to call
     * repeatedly - Shizuku no-ops a bind against an already-connected service. */
    fun ensureUserServiceBound() {
        if (!hasPermission() || userService != null) return
        runCatching { Shizuku.bindUserService(userServiceArgs, userServiceConnection) }
    }

    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean {
        if (!isAvailable()) return false
        return runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun requestPermission(onResult: (granted: Boolean) -> Unit) {
        if (!isAvailable()) {
            onResult(false)
            return
        }
        if (hasPermission()) {
            ensureUserServiceBound()
            onResult(true)
            return
        }
        lateinit var listener: Shizuku.OnRequestPermissionResultListener
        listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE) {
                Shizuku.removeRequestPermissionResultListener(listener)
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                if (granted) ensureUserServiceBound()
                onResult(granted)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure {
                Shizuku.removeRequestPermissionResultListener(listener)
                onResult(false)
            }
    }

    /** Runs a shell command with Shizuku's shell-level privilege, returning combined
     * stdout+stderr. Callers should check hasPermission() first for a clean "not set up"
     * message - this just fails if the user service isn't bound yet (normally instant once
     * permission is granted, since ensureUserServiceBound() is called right after that). */
    private fun runShellCommand(vararg command: String): Result<String> {
        ensureUserServiceBound()
        val service = userService ?: return Result.failure(IllegalStateException("Shizuku user service not connected yet - try again"))
        return runCatching { service.exec(command) }
    }

    /** Genuine force-stop, equivalent to `adb shell am force-stop <package>` - not the lighter
     * killBackgroundProcesses() fallback used when Shizuku isn't set up, this actually matches
     * what Settings > App Info > Force Stop does. */
    fun forceStopPackage(packageName: String): Result<Unit> {
        if (!hasPermission()) return Result.failure(IllegalStateException("Shizuku permission not granted"))
        return runShellCommand("am", "force-stop", packageName).map { }
    }
}
