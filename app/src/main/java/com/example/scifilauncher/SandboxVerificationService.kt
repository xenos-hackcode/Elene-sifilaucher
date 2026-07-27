package com.example.scifilauncher

import android.app.Service
import android.content.Intent
import android.os.IBinder

private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
private val DIGITS_REGEX = Regex("^[0-9]+$")

/** Real isolated-process verification, not a semantic analyzer - this is honest pattern
 * matching on a shell command's exact shape, run somewhere that genuinely cannot touch this
 * app's real data or network (android:isolatedProcess="true" strips permissions and gives a
 * separate UID, unlike ScreenRecordService's ":recorder" which is same-privilege crash
 * isolation only). Fails closed: anything not matching a known-safe shape is rejected, not
 * silently allowed - a real improvement over the status quo, where
 * ShizukuUserService.exec() previously ran any command handed to it with zero checking. */
class SandboxVerificationService : Service() {

    private val binder = object : ISandboxVerification.Stub() {
        override fun verifyShellCommand(command: Array<out String>): SandboxVerdict {
            return runCatching { verify(command) }
                .getOrElse { SandboxVerdict(false, "Verification threw: ${it.message}") }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun verify(command: Array<out String>): SandboxVerdict {
        if (command.isEmpty()) return SandboxVerdict(false, "Empty command")

        // Hard denylist first, regardless of shape - these have no legitimate use in anything
        // this app has ever needed to run, so they're rejected before any allowlist matching.
        val joined = command.joinToString(" ")
        val dangerousTokens = listOf("rm", "dd", "mkfs", "chmod", "chown", "su", "reboot", "mv", "kill", "sh", "-c")
        if (command.any { it in dangerousTokens }) {
            return SandboxVerdict(false, "Command contains a denylisted token")
        }
        if (joined.contains("/data/data/") && !joined.contains(packageName)) {
            return SandboxVerdict(false, "Command targets another app's private data")
        }

        return when {
            matchesForceStop(command) -> SandboxVerdict(true, "am force-stop <package>")
            matchesPackageToggle(command) -> SandboxVerdict(true, "pm enable/disable <package>")
            matchesInputTap(command) -> SandboxVerdict(true, "input tap <x> <y>")
            matchesInputSwipe(command) -> SandboxVerdict(true, "input swipe <x1> <y1> <x2> <y2>")
            else -> SandboxVerdict(false, "Command doesn't match any known-safe shape")
        }
    }

    private fun matchesForceStop(cmd: Array<out String>): Boolean =
        cmd.size == 3 && cmd[0] == "am" && cmd[1] == "force-stop" && PACKAGE_NAME_REGEX.matches(cmd[2])

    private fun matchesPackageToggle(cmd: Array<out String>): Boolean =
        cmd.size == 3 && cmd[0] == "pm" && (cmd[1] == "enable" || cmd[1] == "disable") && PACKAGE_NAME_REGEX.matches(cmd[2])

    private fun matchesInputTap(cmd: Array<out String>): Boolean =
        cmd.size == 4 && cmd[0] == "input" && cmd[1] == "tap" && DIGITS_REGEX.matches(cmd[2]) && DIGITS_REGEX.matches(cmd[3])

    private fun matchesInputSwipe(cmd: Array<out String>): Boolean =
        (cmd.size == 6 || cmd.size == 7) && cmd[0] == "input" && cmd[1] == "swipe" &&
            (2..5).all { DIGITS_REGEX.matches(cmd[it]) } &&
            (cmd.size == 6 || DIGITS_REGEX.matches(cmd[6]))
}
