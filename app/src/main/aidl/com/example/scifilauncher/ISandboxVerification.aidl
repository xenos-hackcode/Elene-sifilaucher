package com.example.scifilauncher;

import com.example.scifilauncher.SandboxVerdict;

// Runs inside android:isolatedProcess="true" - a genuinely separate, permission-stripped UID,
// not just a separate process like ScreenRecordService's :recorder. It can't touch this app's
// real files/prefs and has no network access of its own; it only ever does pattern matching on
// the command string it's handed, then hands back a verdict. The real command still runs (or
// doesn't) back in the normal process, gated on that verdict.
interface ISandboxVerification {
    SandboxVerdict verifyShellCommand(in String[] command);
}
