# Changes Made By Codex

## 2026-09-01

### Welcome eye timing corrected

File: `app/src/main/java/com/example/scifilauncher/WelcomeFaceSkeleton.kt`

I initially interpreted the user's wording as a short blink once per minute. The user clarified
the intended behavior: the eye should stay open for one minute, then stay closed for one minute,
repeating.

Change made:

- Restored `NoFaceEyeVisual`'s `eyeOpenness` animation to a 120-second cycle.
- Timing is now:
  - open from `0ms` to `59500ms`
  - transition closed by `60000ms`
  - stay closed until `119500ms`
  - transition open by `120000ms`
- Kept Claude's better eyelid-mask drawing approach: eyelids cover a stable eye instead of
  squashing/distorting the whole eye.

Reason:

- Matches the user's explicit requirement while preserving the improved reference-style eye
  structure already implemented.

### Removed ignored Android build tools override

File: `app/build.gradle.kts`

Change made:

- Removed `buildToolsVersion = "34.0.0"`.

Reason:

- The build was warning that Android Gradle Plugin 9.0.1 ignores Build Tools 34.0.0 because it
  requires at least 36.0.0 and will use 36.0.0 anyway.
- Removing the explicit stale override lets AGP use its default Build Tools version and removes
  the misleading warning.

### Globe stale location copy corrected

File: `app/src/main/java/com/example/scifilauncher/GlobeActivity.kt`

Change made:

- Replaced stale user-facing references to `STATES` with `NETWORK`.
- The current Globe controls are `MODEL`, `COUNTRY`, `DETAIL`, `BORDERS`, and `NETWORK`; there is
  no active `STATES` control in the current screen.

Reason:

- The app could tell the user to select `STATES` even though that control no longer exists.

### Mapbox token logging redacted

File: `app/src/main/java/com/example/scifilauncher/MyLocationWebView.kt`

Change made:

- Added URL redaction for `access_token` / `token` query parameters before logging WebView errors
  or console source IDs.

Reason:

- MyLocation passes the Mapbox token through the local WebView URL so Mapbox GL can load tiles.
  Logging raw URLs could expose the token in logcat.

### App backup locked down

Files:

- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/backup_rules.xml`
- `app/src/main/res/xml/data_extraction_rules.xml`

Change made:

- Set `android:allowBackup="false"`.
- Replaced Android Studio sample backup/extraction XML with explicit excludes for shared prefs,
  databases, internal files, and external files.

Reason:

- This app stores sensitive local state in SharedPreferences: control tokens, voice ID state,
  lock/security settings, logs, location/network history, and assistant memory. That should not
  silently move through cloud backup or device transfer.

### Sensitive debug logs gated to debug builds

Files:

- `app/src/main/java/com/example/scifilauncher/PhoneControlScreen.kt`
- `app/src/main/java/com/example/scifilauncher/ScifiAccessibilityService.kt`

Change made:

- Wrapped detailed spoken-text, backend-response, voice-ID, and WebSocket-frame debug logs in
  `BuildConfig.DEBUG`.

Reason:

- These logs are useful while developing but can expose private speech, assistant replies,
  commands, and relay traffic metadata in release builds.

### Suggested next improvements, separate from fixes

These are not implemented in this pass because they are feature work and should be kept separate
from bug/security verification:

- Add a dedicated diagnostics screen showing permission state, active services, backend reachability,
  Mapbox token configured/missing, gesture model loaded/missing, and last recognition confidence.
- Add a "gesture test lab" mode that records false positives/false negatives locally without firing
  real actions, so pinch/swipe/up/down can be tuned safely.
- Add a release/debug logging switch so privacy logs stay off by default but can be enabled during
  troubleshooting.
- Add an exportable local audit report for Claude/Codex collaboration: build status, manifest checks,
  asset checks, and gesture-template quality warnings.
- Improve visual polish in one controlled pass after functional stability: welcome eye proportions,
  gesture training preview consistency, and globe/current-location status overlays.

### Verification

- Ran `:app:assembleDebug` before the final eye-timing correction; it passed.
- After the final edits, `:app:assembleDebug` was rerun with Android Studio's bundled JBR:
  `JAVA_HOME=C:\Program Files\Android\Android Studio1\jbr`.
- Final result: `BUILD SUCCESSFUL in 5m`.
- Kotlin warnings only:
  - `GlobeActivity.kt`: deprecated `WifiInfo.connectionInfo`
  - `ScifiAccessibilityService.kt`: deprecated `WifiInfo.connectionInfo`
- The stale Android Build Tools warning is gone after removing `buildToolsVersion = "34.0.0"`.
  The existing CMake SDK XML warning still appears and was not changed by this pass.
- Validated `countries.json` and `globe/country_borders.json` with PowerShell JSON parsing.
- Audited core local areas: manifest/permissions, backup rules, gesture matching/storage,
  welcome face/eye timing, globe controls/assets/WebView, MyLocation WebView/token path,
  shared preference storage, and obvious sensitive log paths.
- First `:app:testDebugUnitTest` attempt unexpectedly tried to download
  `gradle-9.1.0-bin.zip` and was stopped because network is restricted in this session.
- Retried `:app:testDebugUnitTest` with approved Gradle network access. Final result:
  `BUILD SUCCESSFUL in 2m 1s`.
