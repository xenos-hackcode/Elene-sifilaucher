package com.example.scifilauncher;

// Runs inside a separate process Shizuku spawns with shell (ADB) UID privilege - anything this
// process executes (via ProcessBuilder) inherits that privilege, which is how a plain shell
// command becomes equivalent to running it over `adb shell`.
interface IUserService {
    String exec(in String[] cmd);
    void destroy();
}
