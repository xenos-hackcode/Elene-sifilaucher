package com.example.scifilauncher

/** Shizuku spawns this class in its own process with shell (ADB) UID privilege - it never runs
 * in this app's normal process, and only exists at all while Shizuku is bound to it. Whatever
 * ProcessBuilder executes here inherits that shell privilege, which is what turns a plain
 * command into the equivalent of running it over `adb shell`. */
class ShizukuUserService : IUserService.Stub() {
    override fun exec(cmd: Array<out String>): String {
        return runCatching {
            val process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output
        }.getOrElse { it.stackTraceToString() }
    }

    override fun destroy() {
        // The documented Shizuku pattern for a user service to actually exit when told to.
        Runtime.getRuntime().exit(0)
    }
}
