# Done (built + installed, not all confirmed working yet - see experience/)

## Real notification actions + media transport controls (2026-08-14)
User: the last deferred item from the router/recents/notifications ask - real interactive
notification controls (a call's "End call") and real media controls (seeking to a duration,
play/pause, an end button) instead of the stripped-down `LastMessageInfo{appName,title,text}`
that discarded a notification's actual `Notification.actions` and any `MediaSession` entirely.

**Real per-notification action buttons**: `LastMessageInfo` now carries the notification's own
non-RemoteInput `Notification.actions` (call end/answer, download pause, etc.), rendered as real
buttons in `NotificationRow` that fire the exact same `PendingIntent` the system status bar would.
Confirmed live across multiple real apps (Gmail's ARCHIVE/MARK AS READ/REPLY, Harmix's own
PRE/PLAY/NEXT) - not simulated.

**Real "Now Playing" card** (`MediaSessionBridge.kt`, new): uses
`MediaSessionManager.getActiveSessions()` - available because `XenosNotificationListener` is
already an enabled `NotificationListenerService`, no extra permission needed - to get a live
`MediaController` for whatever's actually playing system-wide. Real title/artist/album art,
a working seek scrubber, play/pause, skip, gated per-control on the session's own declared
`PlaybackState.actions` (canSeek/canSkipNext/canSkipPrevious) so a control only appears if the
app genuinely supports it.

**Loop**: confirmed via the SDK's own `api-versions.xml` that the platform `MediaSession` API has
no repeat/loop action at all (it only exists in a separate compat library, and only works there if
the target app opted in, which most don't) - so a delegated loop button would be fake for most
apps. Built our own instead: MainActivity's polling loop watches position vs. duration and, within
~800ms of the end, seeks back to 0 itself. Confirmed working live.

**2x/3x speed**: `setPlaybackSpeed()` is real, standard API 31+ platform behavior - gated only on
`Build.VERSION.SDK_INT` (not on the app's declared actions bitmask, since that turned out to be an
unreliable predictor - see below). Confirmed live that the button fires correctly; whether a given
track actually speeds up depends on the app itself supporting it (most of the actually-installed
test apps didn't).

**Real bugs found and fixed while verifying this live** (not guessed):
- `MediaSessionBridge`'s position interpolation diffed `PlaybackState.getLastPositionUpdateTime()`
  (documented as being on the `SystemClock.elapsedRealtime()` timebase) against
  `System.currentTimeMillis()` (wall clock) - a units mismatch that made every playing track's
  reported position run away to sit at its own duration, which in turn made the loop feature think
  every track was constantly at its end. Fixed by using `SystemClock.elapsedRealtime()`.
- `com.harmix.player.editmusic` (the app used for on-device testing) implements
  `onPause()`/`onSeekTo()` but never `onPlay()` - confirmed via `dumpsys media_session` showing zero
  state change after calling `transportControls.play()`. Since there's no androidx dependency in
  this project, worked around it by also dispatching a real `KEYCODE_MEDIA_PLAY` media-button event
  (`AudioManager.dispatchMediaKeyEvent`) alongside the proper call - the same signal a physical
  play button or `adb shell input keyevent KEYCODE_MEDIA_PLAY` sends, routed by the system to
  whichever app holds the active session regardless of whether it implemented the modern callback.
  This is also why `restartFromBeginning()`'s loop needed the same fallback after its seek.
- Learned from the above that an app's declared `PlaybackState.actions` bitmask isn't a reliable
  signal of what it actually honors (Harmix's bitmask claimed `ACTION_PLAY` support it didn't
  really have) - so `canSetSpeed` was deliberately left ungated on the speed action bit, only
  guarded by the real SDK crash risk (`SDK_INT >= 31`).

## Real Recents: screenshots, timestamps, open-count (2026-08-14)
User: "recents own first" - built out the full Recents overhaul that had been scoped-but-deferred
earlier the same day (see entry below). Confirmed working live on-device, not just built.

New persistent store `RecentAppHistory.kt` (SharedPreferences + JSON, same idiom as
`LocationHistory.kt`/`NearbyDeviceHistory.kt`): tracks `lastOpenedMs` and `openCount` per package,
plus a PNG thumbnail file per package under `filesDir/recent_thumbs/`.

Recording moved out of the launcher's own `launchApp()` entirely and into
`ScifiAccessibilityService.onAccessibilityEvent`'s existing `TYPE_WINDOW_STATE_CHANGED` foreground-
app tracking, so "opened" is counted for every real way an app is entered (launcher tap,
notification, another app's link, Android's own recents/multitasking) - matching the user's literal
"counted +1 everytime u close the app and open again", not just launcher-initiated launches.
`launchApp()` itself was simplified back down to just starting the activity.

Real thumbnails use `AccessibilityService.takeScreenshot()` (API 30+, no extra consent dialog since
Accessibility is already granted), captured ~700ms after an app comes to foreground. Found and fixed
a real bug that would have made this silently fail: `android:canTakeScreenshot="true"` was missing
from `scifi_accessibility_service_config.xml`.

`RecentsScreen.kt` rewritten to a grid of cards showing the real screenshot (icon as fallback for
apps with no capture yet), "Xm/Xh ago" / date, and "opened Nx", with the CLEAR action now actually
clearing `RecentAppHistory`'s store (previous CLEAR only cleared in-memory Compose state).

Verified live via adb: launched Calculator and Clock, confirmed `recent_app_history_prefs.xml` and
`recent_thumbs/*.png` were written on-device with correct counts, then confirmed the Recents screen
itself renders the real per-app screenshots, "2m ago", "opened 1x", and that CLEAR empties the list
back to "Nothing opened yet."

## Router label, Recents data-loss bug, notification cap (2026-08-14)
User: "we have something called router now i went to a friend house and they were using ee yet
my stuff was still named sky" - confirmed via code: the "Router" row in Security > Network
Protection was a hardcoded `"Sky"` string literal, completely disconnected from the actual
connected network (its own tap action opens a generic `myrouter.io` admin page, not anything
Sky-specific either). Fixed by reading the real DHCP gateway IP of whatever network is actually
connected (`WifiManager.dhcpInfo.gateway`), added as a new field on the existing
`WifiConnectionStatus` used elsewhere for the real Wi-Fi SSID row.

User: "clciking s for recents is displaying one app at a atime instead of every app u cant clear
it". Real, confirmed bug in `launchApp()` (`MainActivity.kt`): every app launch REPLACED the whole
`recentApps` list with a single-element list containing just the app that was clicked, discarding
all prior history - so Recents could structurally never hold more than one app no matter how many
were opened. Fixed by prepending the newly-launched app onto the existing list (deduped) instead
of replacing it. Also added a real CLEAR action to `RecentsScreen.kt` (there wasn't one at all).

User: "notifications why is it liite dt to just 50" - `missedNotifications` in
`XenosNotificationListener.kt` was capped at 50 entries. Confirmed this list is in-memory only
(never persisted to disk, lost on process death anyway), so raising the cap costs a bit more RAM
and nothing else - bumped to 300.

**Scoped but not built** (flagged to the user as genuinely large new features, not bugs, rather
than guessing at scope): (1) real Recents thumbnails (a screenshot of the app's last state, not
just its icon) + date/time last opened + an open-count that increments every reopen - the current
`RecentsScreen.kt` only ever showed icons, no history metadata at all; (2) real interactive
notification actions (e.g. a call's "End" button) and media playback controls (e.g. a song's real
seek bar) - confirmed via code that `XenosNotificationListener.kt` strips every captured
notification down to a bare `LastMessageInfo { appName, packageName, title, text }` before it ever
reaches `NotificationBarPanel.kt`'s `NotificationRow`, discarding the original `Notification`'s
`actions` array and any `MediaSession` token entirely - real actions/media controls need surfacing
that data instead, not just a UI tweak.

## Xenos/Cedal keyboards: clipboard toggle fix + real per-keyboard tools (2026-08-11)
Two follow-ups on the same day as the keyboard redesign above.

**Real bug**: "clciking on clip board again should turn me back to normal" - confirmed via code,
the Clipboard button in both keyboards only ever opened the clipboard panel; a second tap did
nothing, the only way back was tapping an actual saved clip. Fixed in both services: tapping
Clipboard while it's already open now closes it back to the normal keyboard. While in there, also
fixed a related bug the fix exposed: the settings overlay (Tiles/tools panel) wasn't being hidden
when switching to the symbols keyboard or clipboard view, so it could visually stack on top of
them - now hidden on every view switch in both services.

**New feature**: user felt Cedal (after the Tiles-toggle removal) was now too bare, and wanted
"more tools for settings in xenos too" - asked which direction (via AskUserQuestion) and got "both,
add more, put logic behind all too" - i.e. real working tools, not placeholder buttons. Built
`KeyboardTextTools.kt` (shared `applyToSelection()` helper - reads `getSelectedText()` from the
field currently being typed into, transforms it, commits the replacement; toasts "Select some text
first" if nothing's selected rather than silently doing nothing) plus the actual transforms:
- Xenos (hacker tools, in the existing Tiles-toggle settings panel): Base64 encode/decode, ROT13,
  SHA-256 hash (hex), leetspeak (a→4, e→3, i→1, o→0, s→5, t→7, b→8, g→9).
- Cedal (professional tools, in a newly-rebuilt settings panel replacing the old Tiles toggle):
  UPPERCASE, lowercase, Title Case, trim-extra-whitespace, and a live word/character count of the
  full field content (shown when the panel opens).

## Xenos/Cedal keyboards: real visual + shift-key bugs, both fixed (2026-08-11)
User: "xenos and ceal keyboard look way too alike let xenos bring in the hacker, cyber and hidden
kind oif vibe with it tools while cedal is more professuonal and all and also fix the capital and
small letter stuff". Two real, confirmed-via-code bugs, not a from-scratch redesign:

1. **They looked alike because Cedal's layout was a literal copy of Xenos's.** Diffed
   `view_xenos_keyboard.xml` against `view_cedal_keyboard.xml` - every single button in Cedal's
   file referenced `@drawable/xenos_key_black` and the same `#FF09C20C` hacker-green text color as
   Xenos, byte-for-byte. The only difference was a `CedalMatrixRainView` (falling green matrix
   rain) bolted onto Cedal's background - meaning Cedal, the one meant to be "professional," was
   the one with the hacker rain effect, and Xenos had none. Backwards from both keyboards' own
   names. Fixed: built `cedal_key_normal.xml`/`cedal_key_accent.xml` (dark slate `#FF262B33`,
   near-white text, blue `#FF4C8DFF` accent - a real distinct professional palette, no green) and
   bulk-replaced Cedal's drawable/color references to use them; moved the rain effect into Xenos's
   layout instead (wrapped Xenos's root in a `FrameLayout` with the rain view behind the keys,
   matching Cedal's old structure); renamed the view class `CedalMatrixRainView` → `MatrixRainView`
   since it now belongs to Xenos, not Cedal.

2. **"Capital and small letter stuff" - real shift-key bug in both keyboards.** In
   `XenosKeyboardService.kt`/`CedalKeyboardService.kt` (identical duplicated logic in both), the
   shift button's tap handler set `isCapsLock = capsOn` on every single tap - which made the
   letter-key handler's own "auto-revert to lowercase after one letter" condition
   (`capsOn && !isCapsLock`) permanently unreachable, since `isCapsLock` always mirrored `capsOn`
   already. Net effect: a single tap behaved exactly like caps-lock (capitalized everything) with
   no way to type just one capital letter - not the normal phone-keyboard behavior a long-press was
   clearly meant to provide separately. Fixed in both services: tap = real one-shot capital (auto-
   reverts after the next letter), tap again while locked = fully off, long-press = real caps lock.

Also found and removed real dead code while investigating: `XenosKeyboard.kt` (a separate Compose
composable, never called anywhere - the real IME uses the XML-layout-based
`createXenosKeyboard()` in `XenosKeyboardService` instead) had its own independent version of the
same bug (every key hardcoded uppercase, no shift key existed at all) - deleted rather than fixed,
since nothing referenced it.

**Follow-up same day**: user checked the result and said they still "shouldn't be sharing many
things in common" - correct, the recolor alone left every other detail identical (same corner
radius, same font, and Cedal still had Xenos's neon "Tiles" color-toggle gimmick, just re-skinned
in Cedal's new colors instead of removed). Went further: gave Xenos a monospace font (Cedal keeps
default sans-serif) and sharpened its key corners to 2dp (terminal/grid feel) while rounding
Cedal's further to 16dp (soft, product-UI feel); and removed Cedal's Settings/Tiles toggle
entirely rather than just recoloring it - a flashy neon color-switcher doesn't fit "professional,"
so it's gone from Cedal's toolbar (Clipboard only now) and stays Xenos-exclusive. Cleaned up the
now-dead `applyTilesMode()`/`switchTiles`/`btnChangeMode`/`btnSettings`/`themePrefs` wiring and the
unused `cedal_key_accent.xml` drawable from `CedalKeyboardService.kt` rather than leaving them
orphaned.

## Settings Feedback: rebuilt as a mailto hand-off, not a backend relay (2026-08-11)
User: "where feedback is change it that when u click on it it oshows box user can type what they
want and user should put thier gmail so the message wuld be sent to me". First build: a real
in-app dialog (message + reply email fields) posting to a new `/feedback` backend endpoint that
relays via Gmail SMTP - discovered along the way that `GMAIL_USER`/`GMAIL_APP_PASSWORD` were never
actually configured on Cloud Run (the existing `/alert/email` code for Sequence Mode alerts had the
same unmet dependency, apparently unnoticed until now). User pushed back before configuring
anything: "wait what why need all that it literally just like user sending message to me... simple"
- correctly read as wanting the zero-setup version. Rebuilt: SEND now opens the phone's own mail
app (`Intent.ACTION_SENDTO`, `mailto:hackerxenos06@gmail.com`) with subject/message/reply-email
pre-filled, same pattern `AboutScreen.kt`'s existing "Email developer" row already uses - one more
tap in their own mail app, no backend, no credentials, works immediately. Removed the now-unused
`/feedback` endpoint and `EleneApiClient.submitFeedback()` rather than leaving them as dead code.
Also removed `openPlayStoreForFeedback()`/`openPlayStoreForRating()` from `MainActivity.kt`
(Feedback previously opened the Play Store review flow instead - both functions had no other
caller once that was replaced).

## Tracker/ad blocking: real slowdown bug, then a real shutdown bug I introduced (2026-08-11)
User: "everything is slwo to load or not eve going through especailly websites" right after the
blocklist expansion (see the earlier 2026-08-10 entry below). Root-caused via real logcat, not
guessed: on this network, direct UDP queries to 1.1.1.1 were timing out on effectively every real
(non-blocklisted) DNS lookup, and `TrackerBlockVpnService.runLoop()` processed every DNS packet
synchronously on one thread - so each 5-second timeout stalled every OTHER pending lookup queued up
behind it too. A single webpage fires dozens of parallel DNS lookups; serialized behind repeated
multi-second timeouts, that's exactly "everything is slow, especially websites." Fixed by
dispatching each packet's handling onto its own coroutine (real concurrency) and shortening the
per-attempt timeout from 5000ms to 2000ms.

**Then a real bug in that very fix, found immediately after**: user reported "when i put it off it
is nt answering" (toggling blocking off left the internet unresponsive for a while). Real evidence
via logcat: `stopVpn()` logged "Stopping tracker-block service" but DNS forwards kept running and
timing out 5+ seconds AFTER that line - because the first fix's `scope.launch { handlePacket(...) }`
spawned each packet's work as an independent top-level coroutine on the service's own `scope`
(never cancelled by `stopVpn()`'s `job?.cancel()`), not as a structured child of the loop's own job.
Fixed properly by making `runLoop()` a `suspend fun` wrapped in `coroutineScope { }`, so `launch {}`
calls inside it are real structured children - cancelling `job` now cancels everything immediately,
while still keeping concurrent (non-serialized) lookups during normal operation.

## Security screen: fixed back button + full localization (2026-08-11)
User: "also in security let the bak button be permanent like settings" - traced via code: unlike
`SettingsScreen.kt` (header outside the scrollable Column), `SecurityScreen.kt` had its entire
content - including the "< DASH" back button - inside one big scrollable Column, so scrolling down
the screen's many sections scrolled the back button away too. Restructured to match Settings: fixed
header, separate inner scrollable Column for everything below it.

User: "now translate all the security so when we choose it can be in that language" - full
localization of every section, toggle, row, and disclosure dialog (Data & Media, Activity, Sequence
Mode, Network Protection, Cedal Shared System, Shizuku, Phone Calls, Voice ID, Kiosk & Lock Screen,
Integrity & Tamper Detection - roughly 90 distinct strings including several long multi-paragraph
dialogs) across all 7 languages, using the same `tr()`/`LocalLanguage` system built for Settings.
Lifted `languageOption` state from being Settings-screen-local up to `MainActivity` (mirroring the
existing `onThemeChange` pattern) so Security and Settings both reflect the same live language
choice instead of drifting out of sync. Voice ID's recording-flow status messages (which splice in
`VoiceStyle.label`/`.prompt` - the actual words the user must say aloud) have their surrounding
template text translated but the spliced English prompt words themselves left as-is - translating
those would mean also localizing what users literally need to say for voice verification, a
separate, bigger change than UI text localization. First build caught nothing wrong this time (the
Settings batch's French/Spanish-argument-count mistake did not repeat), but did lose the device
over USB mid-session (unrelated) - installed once it reconnected.

## EleneApiClient network timeout fix (2026-08-10)
User: "the ai is saying something abt not been able to reach it backend server". Root-caused via
real Cloud Run server-side logs, not guessed: the backend wasn't down at all - both failing
requests actually completed with HTTP 200 server-side (`/elene/chat` took 22.6s, `/tts` took 6.8s)
because Cloud Run had scaled the service to zero idle instances and had to cold-start a fresh one.
`EleneApiClient.kt`'s `OkHttpClient` had no explicit timeouts, so it inherited OkHttp's 10s default
read timeout - well short of the real 22s cold start - and gave up client-side even though the
server was still working. Fixed by giving the client explicit, generous timeouts (15s connect/45s
read/30s write) so it survives a cold start instead of erroring out mid-response.

## Voice-triggered "split screen" silently failing + Nearby Devices tap-to-show-IP (2026-08-10)
User: "in scanner for wifi own let it mention everyone except my own device..." (fixed earlier same
day) and later: "i told xenos to split screen and he saying he couldt do that doesnt he know the
stuffs we have let him have access to them". Root-caused via real logcat: the "Hey Xenos" bubble
(`ScifiAccessibilityService.kt`) has its own separate command dispatcher from `MainActivity.kt`,
completely independent of the `multi_control` case added there for the Multi Control feature below
- the bubble's dispatcher had no matching case, so it silently fell through to the generic "I
couldn't do that" line. Fixed by adding a `multi_control` case directly to that dispatcher (resolves
app names via `AppResolver`, calls the same `launchAppsInMultiControl()`). While fixing it, caught a
second latent bug the earlier UI-only test never exercised: `launchAppsInMultiControl()` was missing
`FLAG_ACTIVITY_NEW_TASK`, required when starting an activity from a Service context (not needed from
`MainActivity`, which is already an Activity) - would have crashed the moment the dispatcher fix let
it actually run. Fixed both together. Also added tap-to-expand on Nearby Devices rows, showing the
real IP (WiFi) or MAC address (Bluetooth) - user: "let there be it can get packs by... let clicking
on the nerby device stuffs should display thier ip also".

## Icon Pack support: real third-party icon packs, not fake art (2026-08-10)
User wants app icons themed via real icon-pack apps (Icon Pack Studio, X Icon Changer) rather than
generated art (correctly identified this codebase can't produce real custom icon art itself).
Built `IconPackManager.kt` + `IconPackPanel.kt`: detects any installed app declaring the standard
icon-pack intent-filter actions (the same de facto protocol Nova/Apex/etc. use), lets the user pick
one under Settings > Appearance > Icon pack, parses that pack's real `appfilter.xml` component→
drawable mapping, and remaps every app's icon in `loadAllApps()` - falling back to the real icon for
anything the pack doesn't cover, same as every other icon-pack-supporting launcher. Wired into the
existing MULTI CONTROL-adjacent "Icon pack" row (replaced a dead `openMultiWindowSettings()`-style
stub that used to just open Samsung's native split-screen settings).

**Real bug found via direct APK inspection, not guessed**: after the user installed and selected
their exported Icon Pack Studio pack, icons didn't change. Pulled the pack's actual APK off the
device and inspected it with `aapt` - confirmed `appfilter.xml` is packaged as a `res/raw/` resource
and a plain `assets/appfilter.xml`, NOT the compiled `res/xml/` resource type the first version
looked for exclusively. The mapping format itself (`component="ComponentInfo{pkg/class}"`) was
exactly as expected - only the storage location was wrong. Fixed by trying compiled-xml, then raw
resource, then assets, in order. Also added a permanent "Get packs free" note + "OPEN ICON PACK
STUDIO ON PLAY STORE" link in the panel, per the user's request to point people at that tool (it's
free, and it's the actual mechanism this feature depends on).

## Voice ID "weaker lately" warning not clearing after re-enrollment (2026-08-10)
User: "in voice id i already re enrolled my oice id why is it still sying voice id matches have
been weaker lately". Root-caused via code, not guessed: `VoiceIdConfidenceLog.isDrifting()` looks
at whether 3+ of the last 5 real (non-test-button) verification attempts failed - but nothing ever
cleared that log on re-enrollment, so stale pre-enrollment failures kept tripping the warning
regardless of how good the fresh enrollment now was. Added `VoiceIdConfidenceLog.clear()`, called
right after a successful "Add voice samples" completes in `SecurityScreen.kt`, and made the on-
screen `voiceDrifting` flag a live `mutableStateOf` (was a one-shot `remember{}` before) so the
warning actually disappears immediately after a successful re-enrollment instead of needing a
fresh 5 real attempts to age the old failures out.

## Ad/tracker blocking: real blocklist was a 71-domain starter list (2026-08-10)
User: "in trackr ad ad blockin we need it to work very well". Found the real cause by reading the
list itself: `tracker_blocklist.txt` was a 71-domain placeholder, explicitly labeled in its own
header comment as "a starter list, not exhaustive". Replaced it with AdAway's real, actively-
maintained, CC-BY-licensed mobile ad/tracker blocklist (~6,540 domains) merged with the original
curated set, deduplicated to ~6,580 total. While expanding it, also fixed a real scaling problem in
`TrackerBlockVpnService.isBlocked()`: the old check (`blockedDomains.any { d.endsWith(".$it") }`)
scanned the entire set per DNS query - fine at 71 entries, a real measurable cost at 6,580+ since
every DNS lookup on the device goes through this while the VPN is active. Rewrote it to walk up the
query domain's own labels (O(depth) instead of O(blocklist size)) for the same blocking behavior at
real scale.

## Full UI localization: Settings screen (2026-08-10)
User: "u will see language all this lanaguage affect the text like when i choose yoruba for exmple
te word time format can be changed and so on". Found the real gap: the existing Language setting
(`Language.kt`) only ever translated ~11 spoken Elene phrases - every screen label (including "Time
format", the user's own example) was hardcoded English regardless of language, since there was no
UI-text localization system at all. Asked the user to scope this (spoken-phrases-only vs most-used
screens vs everything) given the real size - they chose full scope (every screen, every string).

Built the reusable infrastructure first rather than hardcoding language checks per screen:
`UiStrings.kt` provides a `LocalLanguage` CompositionLocal + `tr(key)` (composable contexts) /
`uiString(key, lang)` (non-composable contexts like coroutines) backed by a keyed translation table,
so any current or future screen can pull a translated string without threading a language parameter
through every function signature. Fully migrated `SettingsScreen.kt` (every section, every dialog,
every panel - Appearance, Network, System & Behavior, Capabilities, Memory, About & Support, plus
the Language/Time Format/Font Size/Keyboard picker panels) across all 7 languages as the first
complete, real slice - proven by fixing a genuine compile error the first build caught (a translation
entry missing its French/Spanish pair). Other screens are not yet migrated - this is a real, large,
ongoing effort (every screen in the app has its own set of hardcoded strings), tracked as a
continuing task, not a one-shot "done everywhere" claim.

## Multi Control: real split-screen, 2-3 apps at once (2026-08-10)
User's ask: "multi control means i can two to three apps showing at omce on the screen" - clarified
via question to mean genuine split-screen/multi-window, not previews, with both a Quick Settings
tile and a voice command as entry points ("Both"). Confirmed technically feasible first, not
assumed: `pm has-feature android.software.freeform_window_management` returns true on this device
(SDK 36), and this app already has Device Owner/default-launcher privileges a normal third-party
app doesn't - that's specifically what makes forcing another app into freeform windowing mode
possible here via `ActivityOptions.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM)`.

Built `MultiControlScreen.kt`: an app picker (2-3 apps, reusing the existing `AppItem` model and
`allAppsState`) and `launchAppsInMultiControl()`, which computes per-app screen bounds (left/right
halves for 2 apps, top/middle/bottom thirds for 3) and launches each into its own freeform window.
Wired it to the MULTI CONTROL Quick Settings tile, which previously only opened Samsung's native
split-screen settings (or Display settings as fallback) as a placeholder - that stub is now removed,
replaced by the real in-app picker. Added a `multi_control:<app>|<app>|<app>` voice command (backend
prompt in `backend/elene/main.py` + handler in `MainActivity.kt`, reusing
`AppResolver.resolvePackageName()` for fuzzy name matching, same as `open_app`) - deployed to Cloud
Run as `elene-backend-00046-5hp`, smoke-tested live against `/pink`.

**Confirmed live, not just built:** user tested both the 2-app and 3-app cases live on-device.
Pulled real logcat evidence afterward rather than trusting "it works" alone - confirmed the
system's own `com.android.wm.shell.freeform.FreeformContainerView` and dismiss-button-freeform
windows genuinely appeared for both runs, and there were zero `AndroidRuntime:E`/`FATAL EXCEPTION`
lines across the whole test window. This is real OS-level freeform windowing being created, not a
fake preview.

## Eye Comfort crash fix (2026-08-10)
User: "it ends up kicking me out of the app when i click it". Root-caused via live logcat, not
guessed: `SecurityException: Starting FGS with type specialUse ... requires permissions ...
FOREGROUND_SERVICE_SPECIAL_USE` - the manifest declared `foregroundServiceType="specialUse"`
correctly but was missing the matching `<uses-permission>`. Fixed by adding the permission.
Confirmed live: user reproduced the crash first ("it closes again") for a clean before/after, then
confirmed the fix ("ok works") - real `Background started FGS: Allowed` log line and a full
`WindowManagerGlobal#addView`/`#removeView` start/stop cycle with no crash.

## WiFi Calling settings crash - investigated, confirmed OS-level bug (2026-08-10)
User: "settings keeps on stopping". Root-caused via the real crash stack trace to be entirely
inside `com.android.settings` (Samsung's own Settings app): a `NullPointerException` in
`WifiCallingSettings.updateTitleForCurrentSub()` calling `ActionBar.setTitle()` on null. Tried one
legitimate mitigation - passing `Settings.EXTRA_SUB_ID` via
`SubscriptionManager.getDefaultVoiceSubscriptionId()` - user retested live ("tried"), same crash,
byte-for-byte identical stack trace. This is a genuine bug inside Samsung's own Settings app, not
fixable from a third-party app. Documented honestly rather than chasing further workarounds.

## Secure Folder "not set up" - investigated, confirmed accurate, redirect added (2026-08-10)
User: "why is secure folder telling me that it isnt set up on this device even though we have file
manager". Investigated and found the message was actually correct, not a bug:
`com.samsung.knox.securefolder` is installed but its launcher-shortcut component
(`SecureFolderShortcutActivity`) is genuinely disabled by Samsung until the user completes Secure
Folder's own one-time setup - confirmed via `adb shell am start` on it returning "does not exist".
User asked "set it up for me" - declined, since a personal security PIN/account should be the
user's own action, not something an agent sets silently. First fix attempt deep-linked directly
into Secure Folder's own setup Activity (`SecureFolderMainSettingActivity`) - this did launch, but
user observed it live ("it looks like it at to oen and then immedaitely closes") and real logcat
confirmed a genuine open-then-self-destroy ~0.6s later, a deliberate Knox caller-trust security
check, not a bug to route around. Reverted to redirecting to the public, documented
`Settings.ACTION_SECURITY_SETTINGS` screen instead, where Secure Folder shows up through Samsung's
own trusted in-Settings navigation.

## Nearby Devices scanner: own-device exclusion, last-seen, ALL/RECENT filter (2026-08-10)
User: "in scanner for wifi own let it mention everyone except my own device and let them have time
the time would say when lst they were seen if like they are still on it would say live". Found the
real bug via code reading: `scanLan()`'s ping sweep included the phone's own IP because
`InetAddress.isReachable()` on your own address reliably succeeds and there was no exclusion check
- fixed by comparing against the local address before adding a result. Added `NearbyDeviceHistory.kt`
(SharedPreferences+JSON persistence, same pattern as the existing `LocationHistory.kt`) so devices
seen in past scans are remembered, not just the current scan - each row now shows "LIVE" (theme
color) if seen in the scan just run, or a relative "Xm/Xh/Xd ago" otherwise. Added ALL/RECENT
(24h) filter chips. Build installed and crash-free; user had not yet run a live scan with these
changes as of this write-up - not yet independently confirmed live.

## Standalone controller app: added QR scanner (2026-08-10)
User pointed out the standalone controller app (built earlier the same day, deliberately
text-entry-only to keep it lightweight) was missing the QR scan option the main app's own
controller screens have - reasonable ask, since this app is specifically the one meant to be
handed to other people, where a scan option matters more than in the main app. Added
`zxing-android-embedded` + `zxing:core` (same library the main app already uses, not a new
approach), `CAMERA` permission, a `ScanContract`-based launcher in `MainActivity.kt` mirroring the
main app's own pattern, and the `onScanQr` parameter + "SCAN QR INSTEAD" button back into both
`LaptopControlScreen.kt` and `PhoneControlScreen.kt` in the `controller/` module. Self-tested:
compiled clean (one transient Kotlin-daemon session error, unrelated to the code, fell back and
still succeeded), built a real APK, installed on the actual device, confirmed no crash. Re-uploaded
to the public download bucket - verified byte-for-byte match between the live URL and the local
build output.

## Link to Phone agent: pause/resume across screen-off, real crash found and fixed (2026-08-10)
Real, hard Android platform fact confirmed live via logcat: MediaProjection is deliberately revoked
by the OS the instant the screen turns off (`MediaProjection.Callback.onStop()` fires), on every
Android device, for every app - not a bug, a deliberate privacy boundary. No wake lock or foreground-
service trick can prevent it, and resuming always needs one fresh user consent tap - Android does
not expose any permission, including Device Owner-level ones, that makes this silent. Confirmed this
directly with the user rather than chase a workaround that doesn't exist.

Built the best available real solution: on projection loss, the session pauses instead of fully
logging out (websocket, pairing token stay alive) and a "RESUME NOW" button appears in the app.
**Real bug found and fixed via a live crash, not assumed**: the first version tried to keep
`ScreenCaptureService` alive as a foreground service through the pause (to also show a system
notification for a background-app scenario) - crashed every time with a real
`SecurityException: Starting FGS with type mediaProjection ... requires permissions` (Android
refuses to let a service re-claim the `mediaProjection` foreground-service type without an actively
valid projection backing it - another deliberate platform enforcement, not a bug to route around).
Root-caused via the actual stack trace (`AndroidRuntime:E`), not guessed. Fixed by redesigning: 
`ScreenCaptureService` now just fully tears down on projection loss like it always did; the wake
lock and the "paused, tap to resume, STOP" notification both moved to `PhoneLinkAccessibilityService`
instead (an AccessibilityService, which has no foreground-service-type restriction to fight with).

Self-tested for real, iteratively, catching real regressions each round: compiled clean, built,
installed, confirmed via `AndroidRuntime:E` logs and process PID checks that the crash is gone and
a real active session (genuine live taps/swipes/key-presses flowing through) survives a resume.
**Honest final state**: in-app resume (open the app, tap RESUME NOW) works and is crash-free -
confirmed live. The background-notification path (screen off while the app itself isn't in the
foreground) does not reliably show the notification yet - the user tested this directly, it didn't
appear, and given the core crash is fixed and in-app resume works, we stopped chasing this specific
polish item rather than over-invest further. Worth another look later if it matters enough to
revisit, but not blocking.

## Link to Phone agent: wake lock fix for screen-lock, not just app-swipe-away (2026-08-10)
Second real background-survival fix for the phone agent the same day - user reported a *different*
symptom from the earlier `stopWithTask` fix: locking/turning off the CONTROLLED phone's screen (not
closing the app - just the normal screen-off/lock) stops the session working. Confirmed via grep
that `ScreenCaptureService` held no wake lock at all (`PowerManager` was only being used for the
battery-optimization check added earlier) - once the screen locks, the CPU can go into light sleep,
stalling frame capture/gesture dispatch/network processing even though the foreground service and
`MediaProjection` are technically still alive.

Fixed by acquiring a `PowerManager.PARTIAL_WAKE_LOCK` (tag `PhoneLinkAgent:capture`, 6-hour safety
timeout, `setReferenceCounted(false)`) when capture starts, released in `teardown()` alongside the
rest of the cleanup. Deliberately partial, not a screen-on wake lock - a real remote-control session
shouldn't need the controlled phone's own physical screen to stay lit for whoever's holding it, just
the CPU needs to keep processing. Added the required `WAKE_LOCK` manifest permission.

Self-tested: `:agent:compileDebugKotlin` succeeded, built a real APK, installed on the actual device,
confirmed no crash via logcat. Re-uploaded to the public download bucket - verified byte-for-byte
match between the live URL and the local build output.

**Not yet confirmed live**: same standing gap as everything else in this phone-to-phone flow - a
real two-device test (start a session, lock the controlled phone's screen, confirm capture/control
keeps working) hasn't been done this session.

## Link to Phone agent: background survival fixes (2026-08-10)
User reported the phone-side agent app (`agent/`, `com.example.phonelinkagent`) doesn't survive
just closing/swiping the app away - matching the same category of gap already fixed for the PC
side. Root-caused two real, separate gaps rather than one:
1. `ScreenCaptureService` had no `android:stopWithTask` set in the manifest - swiping the app away
   from Recents killed the foreground service/capture session outright, even though it's a real
   registered foreground service with a persistent notification. Fixed with
   `android:stopWithTask="false"`.
2. No battery-optimization exemption was ever requested. This is the other common cause of OEM
   background-killing (Samsung especially, which this project's test device is) - even a correctly
   flagged foreground service can get killed by aggressive OEM battery management unless the app is
   explicitly exempted. Added a new mandatory onboarding step (`BatteryStep` in
   `PhoneLinkAgentScreen.kt`), inserted between the existing Accessibility step and the Pairing step
   - same disclosure-driven, nothing-silent pattern as the rest of this app - requesting
   `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for itself, with a fallback to the general
   battery settings screen if that specific intent is rejected (some OEM/Play-Protect-restricted
   builds do this). Added the required `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` manifest permission.

Self-tested: `:agent:compileDebugKotlin` succeeded, built a real APK, installed on the actual
device, confirmed no crash (empty `AndroidRuntime:E` logcat output) and the app's task genuinely
came to the foreground on relaunch rather than crash-looping. Re-uploaded the updated APK to the
public download bucket so the Download screen's existing "Link to Phone" share link isn't stale -
verified byte-for-byte match between the live URL and the local build output.

**Not yet confirmed live**: whether the session genuinely survives a real swipe-away now needs an
actual two-device pairing test (start a session, swipe the agent app away, confirm the controller
side stays connected) - not done this session. This is the same standing gap already noted
elsewhere in the planner: the phone-to-phone agent flow has never been confirmed live end-to-end,
only code-complete and crash-free on install.

## Standalone controller app + Download screen scroll fix (2026-08-10)
User pointed out the Download screen wasn't scrollable (a plain `Column` with no scroll modifier,
overflowing on real content) - fixed with `.verticalScroll(rememberScrollState())`.

User then asked for the CONTROLLER role to also work outside the main SciFiLauncher app, clarified
as: a standalone, shareable Android app that can control a paired PC/phone without installing the
full launcher. Built a third Gradle module, `controller/` (`com.example.xenoscontroller`), mirroring
the existing `agent/` module's pattern (separate app, own `settings.gradle.kts` entry, own
`build.gradle.kts`). Deliberately minimal manifest - just `INTERNET`, no Device Admin, no
Accessibility Service, no HOME category, since this only ever sends commands to and renders frames
from something else already running the agent side, never touches its own device's screen/input.

Ported `LaptopControlScreen.kt`/`PhoneControlScreen.kt` into the new module (same wire protocol,
same reconnect-with-backoff client logic) with one deliberate simplification: text-entry-only
pairing, no QR scan (avoids pulling in a camera/scanning dependency for a small standalone app;
the main app's controller screens keep their QR option). `MainActivity.kt` is a simple home
screen picking between the two, each with its own persisted token in the app's own
SharedPreferences.

Self-tested for real at each step: `:controller:compileDebugKotlin` succeeded after one real fix
(missing `rememberSaveable` import - lives in `androidx.compose.runtime.saveable`, not covered by
the wildcard `androidx.compose.runtime.*` import), built a real APK, installed it on the actual
device, confirmed genuine focused-app launch with no crash via `dumpsys window` (not just "install
succeeded"). Uploaded to the same public bucket as the other two downloads
(`XenosController.apk`, verified live with a real HTTP 200). Added as a third section in
`DownloadScreen.kt` ("STANDALONE CONTROLLER APP") alongside the existing Link to PC/Phone sections,
sharing a download link the same way. Main app recompiled, rebuilt, reinstalled, relaunched clean
with no crash.

**Not yet confirmed live**: actually pairing through the standalone controller app end-to-end (enter
a real token, see real frames, send real commands) - built, installed, and the download link is
live, but the actual live-control flow through this specific new app hasn't been exercised yet.

## Download screen: Settings > Capabilities > Download, Controller/Controlled for both link types
## (2026-08-10)
Built the actual UI piece of the Link to PC/Phone restructure discussed earlier - a new
`DownloadScreen.kt`, reached via a new "Download" row added to `CapabilitiesScreen.kt` (styled
distinctly from the other capability rows since it navigates rather than opening an info dialog).
Per the user's explicit design: Quick Settings tiles stay exactly as they are (the CONTROLLER
shortcut, for viewing/controlling something you're already paired with); the new Download screen is
specifically where you get/share the CONTROLLED-side app for each link type, with both roles clearly
labeled ("CONTROLLER (this device)" links straight into the existing LaptopControlScreen/
PhoneControlScreen; "CONTROLLED (share this app)" shares a real download link via Android's share
sheet).

Both distributables are hosted as public objects in a new dedicated bucket
(`gs://cedal-fd4a2-link-downloads`, `roles/storage.objectViewer` granted to `allUsers` - deliberately
public and non-expiring, unlike the self-update pipeline's signed URLs, since these are meant for
open-ended sharing with arbitrary people, not a single fingerprint-approved recipient):
- `XenosLinkToPC.exe` - the PyInstaller-built Windows agent from the persistent-background work
  above (already includes the tray icon, install/uninstall, disclosure gate, and all the real bug
  fixes from that investigation).
- `XenosLinkToPhone.apk` - fresh rebuild of the existing `agent/` module (`com.example.phonelinkagent`).

Both URLs verified for real with direct HTTP requests (not assumed from the upload command's exit
code) - `200 OK`, byte-for-byte size match against the local files. App compiled clean
(`compileDebugKotlin`), built a real debug APK, installed on the actual device, confirmed a clean
crash-free relaunch via logcat. **Not yet confirmed live**: actually tapping through Settings >
Capabilities > Download > both share buttons and both controller shortcuts on the real device -
built and installed, ready for that pass whenever it happens next.

## Link to PC: persistent background operation (tray icon, auto-start, disclosure gate) - fully
## confirmed live, 2026-08-10
User wanted the PC agent to survive closing its window, and asked for something like a real
Windows Service. Flagged a real architectural fact first (not a design choice): a true Windows
Service runs in Session 0, which has no interactive desktop - it literally cannot capture/control
the visible screen, the exact thing this feature needs to do. User chose the real working
alternative instead: an auto-starting background app in the user's own session, with a visible tray
icon (never fully invisible, matching this whole project's "no silent operation" rule) and a
mandatory disclosure + typed "yes" confirmation before installing anything - same consent pattern
already established for the phone agent app's own disclosure step.

Added to `laptop_agent/agent.py`: `--install` (disclosure -> confirm -> install), `--uninstall`
(removes everything, kills any running instance), `--tray` (the actual background mode: pystray
icon with Status/Show pairing code/Quit, agent loop running in a background thread). Preferred
install path is a real Task Scheduler entry (AtLogOn trigger + `RestartCount`/`RestartInterval`
settings, which genuinely restarts it if killed, not just re-launches at next login) - with a
Startup-folder shortcut as an automatic fallback (auto-start-at-login only, no restart-if-killed,
since that specifically needs OS-level supervision) when Task Scheduler isn't available.

**Real bugs found and fixed via live testing on this actual machine, in order, each confirmed with
real evidence before moving to the next**:
1. **Task Scheduler genuinely blocked on this account** - both `Register-ScheduledTask` and the
   classic `schtasks.exe` fail identically with "Access is denied" (confirmed not-admin, looks like
   a domain/GPO restriction, not a bug). Startup-folder fallback works with zero special permissions
   and was exercised for real every test after this.
2. **PyInstaller onefile temp-dir crash**: a frozen exe launching a fresh copy of itself inherits the
   parent's `_MEI*`/`_PYI*` bootloader env vars, which point the child at the parent's temp
   extraction folder - deleted the moment the parent (`--install`) process exits, so the child
   crashed with `FileNotFoundError` on startup. Fixed by stripping those vars from the child's
   environment before spawning it.
3. **Apparent duplicate-instance bug, real but the root cause was misdiagnosed as a bug for a while**:
   after each install, two `XenosLinkToPC.exe --tray` OS processes were consistently alive. A/B
   tested against the raw Python script (never duplicated, even waiting well past the timing window)
   to confirm this was exe-specific. Built a singleton Win32 mutex guard - first attempt used the
   `Global\` namespace and only checked for `ERROR_ALREADY_EXISTS`, which was itself buggy (this
   account also lacks the privilege for `Global\` objects, so `CreateMutexW` failed for an unrelated
   reason and BOTH processes read that as "lock acquired"); fixed with a plain session-local mutex
   name and checking the handle itself, not just one specific error code - validated correct via a
   direct two-instance test on the raw script (second instance correctly detected the lock and
   exited). Then found the exe's "rejected" instance was hanging forever on a `print()` call with no
   attached console to write to (launched via `.bat` -> `start ""` -> no console) - removed the print
   for the tray-mode rejection path. **After all that, still two OS processes** - but real evidence
   (Cloud Run logs showing only ONE accepted WebSocket connection every single time, and a real
   CPU/memory/thread-count comparison: one process at 7MB/3 threads, the other at 62MB/6+ threads)
   revealed this was never actually a duplicate-session bug at all - it's PyInstaller onefile's
   completely normal, benign "bootloader stub + real worker" two-process shape on Windows. The
   mutex/env-var/print fixes were all real and worth keeping (they fix genuine edge cases - a truly
   accidental double-launch, or a crash on frozen-exe self-relaunch), but the "two processes in Task
   Manager" itself was never the actual problem.

**Confirmed live, final state**: install (disclosure -> Startup-folder fallback since Task Scheduler
is blocked here -> real running tray instance) and uninstall (Startup .bat removed, all processes
killed) both verified via direct PowerShell/process checks, not assumed. Real backend connections
confirmed via Cloud Run logs throughout - exactly one accepted `/laptop/ws/agent/<token>` connection
per install, consistently, across every test in this whole investigation.

## Link to PC / Link to Phone controller auto-reconnect (2026-08-10)

## Link to PC / Link to Phone controller auto-reconnect (2026-08-10)
User asked for the connection to survive going away and coming back, "no matter where I am, even
London." Checked the relay architecture first: it already runs over the public internet (Cloud Run
WebSocket relay), not local WiFi, so location was never actually the limiting factor - that part of
the ask needed no code change, just confirming/reassuring.

The real gap, found by reading the actual code: `LaptopControlClient`/`PhoneControlClient`
(`LaptopControlScreen.kt`, `PhoneControlScreen.kt`) each open their WebSocket once in a
`DisposableEffect(token)` and never retry - `onFailure`/`onClosed` just set state to `DISCONNECTED`
and stop. A dropped connection (network handoff, backend instance recycling, walking out of signal)
left the screen stuck showing "Disconnected" until the user manually backed out and back into the
screen to force a fresh connect. The agent sides (PC exe/`agent.py`, phone agent app) already had
their own reconnect-with-backoff loops - only the controller side was missing it.

Fixed by adding the same capped exponential-backoff reconnect pattern (2s -> 30s) to both
`LaptopControlClient` and `PhoneControlClient` - on drop, schedules a reconnect via
`Handler.postDelayed` instead of just giving up; resets the backoff to 2s on a real successful
`onOpen`; `disconnect()` (called from the screen's `onDispose`) cancels any pending reconnect so
navigating away doesn't leave a retry loop running behind it. Self-tested: compiled clean
(`compileDebugKotlin`, `BUILD SUCCESSFUL`), built a real debug APK, installed on the actual device.
**Confirmed live, 2026-08-10**: real pairing session established (PC agent exe + phone's Link to
Laptop screen, verified via actual Cloud Run logs showing the WebSocket accept for the real session
token), then the user toggled airplane mode on/off on the phone without navigating away from the
screen. Real evidence of a genuine disconnect-then-reconnect cycle - two separate `[accepted]` log
lines for the identical token, ~3.5 minutes apart (01:56:23 initial, 02:00:01 after the airplane-mode
drop) - confirming the backoff reconnect fired and recovered on its own, not assumed from the user's
report alone.

## PowerShell rewrite of the Link to PC (laptop) agent - built, works, has a real distribution
## caveat (2026-08-09/10)
User asked for the Windows-side "Link to PC" agent to be rewritten from Python (`agent.py`, needs
Python + `pip install` of 4 packages) to native PowerShell (`agent.ps1`, nothing to install - just
run it, since PowerShell ships with Windows) - part of a larger plan to add a "Download" section
under Settings > Capabilities where both Link to PC and Link to Phone become real shareable apps.

Built `laptop_agent/agent.ps1` matching the exact wire protocol already used by the Python agent and
expected by `LaptopControlScreen.kt`/the backend relay (same JSON command shapes, same
`/laptop/ws/agent/{token}` endpoint, same token-persistence file). Implemented via real Win32
P/Invoke (`SetCursorPos`/`mouse_event` for mouse, `SendKeys` for keyboard) and
`System.Net.WebSockets.ClientWebSocket` for the relay connection - no external modules needed.

**Real, converging finding via live testing on this actual Windows machine, not assumed**: raw
PowerShell scripts doing this (outbound network + simulated mouse/keyboard input) trip Windows
Defender's AMSI content scan - got a real, repeatable (though inconsistent - sometimes ran, sometimes
blocked) "This script contains malicious content and has been blocked by your antivirus software"
error. This is because the pattern (connect out, receive commands, simulate input) is functionally
identical in shape to a RAT, which is exactly what AMSI heuristics are built to catch - not a bug in
the script, a structural property of what it does. Explicitly did not attempt to obfuscate the script
to dodge AV detection - that would be evasion, not a fix, and wouldn't be stable against future
signature updates anyway.

User asked whether bundling Python via PyInstaller into a real standalone `.exe` would avoid the
same block. Tested for real: built `XenosLinkToPC.exe` via PyInstaller from the existing `agent.py`
(`pyinstaller --onefile --console`), ran it 3 separate times on this real machine - **zero AMSI
blocks**, and confirmed real backend connections via actual Cloud Run logs (`WebSocket
/laptop/ws/agent/<token> [accepted]`), not just local process state. So: **the compiled `.exe` is the
real, working path for reliable distribution - the PowerShell rewrite itself works correctly but
isn't reliably runnable when just handed to someone as a raw `.ps1` file.**

**Not yet done**: the exe currently bundles unnecessary transitive dependencies (PyQt5/numpy/OpenCV
pulled in despite not being used), coming out to 102MB - fixable with `--exclude-module` flags, not
done yet. Also not yet disclosed anywhere in-app: an unsigned exe will likely trigger a Windows
SmartScreen "protected your PC" warning on first run (normal/expected click-through, different and
milder than the AMSI block, but real friction worth stating plainly wherever this gets shared from,
matching the phone agent app's own upfront disclosure step).

## Notification listener missing already-active notifications on reconnect (2026-08-09/10)
User reported the phone's real notification shade had 4 notifications but the app's own captured
feed only showed 1 (a frequently-updating charging notification). Root-caused via real evidence, not
guessed: pulled `dumpsys notification --noredact` and confirmed the missing ones (`Cedal SMS Relay`)
genuinely had real, non-blank text (`"Sent to +447... just now."`), ruling out the obvious blank-text
theory. Real cause: `XenosNotificationListener.onListenerConnected()` never called
`getActiveNotifications()` - it only ever processed notifications posted *after* that connection, so
any already-active, rarely-updating notification (an ongoing foreground-service notification that
posted once and hasn't changed since) stayed invisible until it happened to re-post. The charging
notification only ever showed because it updates every minute or two, giving it constant fresh
chances to be caught regardless of reconnect timing.

Fixed by backfilling `activeNotifications` through the same `onNotificationPosted()` path in
`onListenerConnected()`. Self-tested for real: compiled locally (`./gradlew :app:compileDebugKotlin`,
`BUILD SUCCESSFUL`), built a real debug APK (`assembleDebug`), installed it on the actual device via
adb, then proved the fix live - not assumed. First attempt showed the listener hadn't rebound yet
(no `"NotificationListener connected"` line after install); the Settings toggle to force a reconnect
was greyed out (notification access was device-admin-granted, not user-toggleable), so forced it via
`adb shell cmd notification disallow_listener` + `allow_listener` instead. Real log evidence after
that: `disconnected` -> `connected` -> immediately followed by `Cedal SMS Relay` (previously
invisible) and 3 others all appearing via the backfill. Separately confirmed `Harmix` correctly still
doesn't appear - it uses a fully custom `RemoteViews` notification layout with no extractable
standard text field, a real, different, structural limitation (not something this fix addresses,
and not simply fixable the same way).

## Voice ID gate genuinely rejected a real command - all-or-nothing enrollment gotcha found and fixed (2026-08-09)
User reported "I said open whatsapp and nothing happened." Root-caused via real evidence, not
guessed: pulled the phone's own logcat (`adb logcat`, filtered to the app's real tags -
`EleneBubble`/`EleneVoiceID`, found by reading the actual `Log.d(...)` calls in
`ScifiAccessibilityService.kt` rather than assumed) and saw the full real pipeline - mic heard
"open WhatsApp" correctly, backend returned the correct `open_app:WhatsApp` command, but no
`Command verb=...` execution line followed. One line explained it:
`EleneVoiceID: heard="open WhatsApp" style=SHORT similarity=0.21495928` - the `SHORT` style's
required threshold is `0.55` (`VoiceStyle.kt`), so `voiceIdAllowsCommand()` correctly rejected it
and silently blocked execution. This is the gate working as intended, not a bug - and likely the
first time the user has actually seen it reject something live, since the fail-open bug fixed
2026-08-01 (see `in_progress.md`) used to let everything through regardless of score.

Checking Security screen to re-enroll surfaced a second, real, separate issue: the button showed
"Enroll voice" instead of "Add voice samples". Root cause in code: `VoiceIdManager.isEnrolled()`
is all-or-nothing - it requires all 5 `VoiceStyle` entries to have stored embeddings
(`VoiceStyle.entries.all { isStyleEnrolled(...) }`), so even one incomplete style makes the whole
account read as "not enrolled" everywhere that checks it (Security screen's button label, the
`voice_id_status` sent to the backend, etc.) - not immediately obvious from the UI alone. User's
own real explanation for how it happened: they cut off an earlier enrollment recording before
finishing the phrase, leaving that one style's embedding missing/incomplete.

**Confirmed fixed, real evidence both ways**: after the user redid the full enroll flow, pulled the
actual on-device `voice_id_prefs.xml` directly via `adb shell run-as ... cat ...` (not assumed from
the UI) and confirmed real embedding data now exists for all 5 styles
(`ALPHABET`/`LONG`/`MEDIUM`/`NUMBERS`/`SHORT`). A live retry of "open WhatsApp" then produced a real
`Command verb="open_app" arg="WhatsApp"` execution line - the full pipeline completed. One honest
caveat logged for future debugging: that particular successful run had no `EleneVoiceID` score line
at all, meaning the gate took its designed fail-open path (verification recording didn't come back
cleanly - mic timing, not a bad match) rather than a confirmed high-score pass - still open to see a
real scored pass against the fresh sample in a future run, not blocking anything.

## Multi-provider LLM fallback: Groq + OpenRouter added alongside Anthropic (2026-08-09)
Extended the same-day Anthropic fallback (below) after the user asked why Stability/Replicate/Groq/
Deepgram/OpenRouter (all already sitting in `.env`) weren't being used. Real answer, not all of them
fit this slot: Stability is image generation, Deepgram is speech-to-text - different jobs entirely,
not swappable for a text-reasoning call. Replicate has no simple universal chat-completions endpoint
(each model needs its own request shape) - more friction than the others, left out. Groq and
OpenRouter both expose an OpenAI-compatible REST API, so they're wired in as two more fallback tiers
by pointing the same `openai` SDK at their base_url instead of adding new client libraries.

Consolidated what had been separate 2-tier try/except blocks in three endpoints into two shared
helpers (`_llm_reply` for text, `_llm_vision_reply` for screenshot-based calls) - with 4 providers
across 3 call sites the old inline pattern would've meant ~12 near-identical try blocks. Chain order:
OpenAI -> Anthropic -> Groq -> OpenRouter for text; OpenAI -> Anthropic -> OpenRouter for vision (Groq's
configured model, `llama-3.1-8b-instant`, is text-only, so it's skipped there rather than left in as
dead weight that always fails).

**Self-tested for real, found a real limitation worth flagging honestly**: ran the backend locally
with OpenAI deliberately broken and Anthropic still out of credits, to force the chain past both.
Groq itself then failed too - not from a bad key, but `413 Request too large... TPM Limit 6000,
Requested 6208` - the `/elene/chat` system prompt (the full command-list prompt) alone is large enough
to blow past Groq's free-tier tokens-per-minute cap on a single request. So Groq is wired in and
correctly *attempted*, but in practice will almost always fail specifically on `/elene/chat` given
its current prompt size - it isn't dead code (it could still work for shorter prompts, and doesn't
block the chain), just not realistically load-bearing there. OpenRouter is what actually answered in
this test (`"confirmed"` came back correctly) - real proof the 4-deep chain works end to end, just
not evenly across all four tiers.

Deployed as revision `elene-backend-00044-nh4` (added `GROQ_API_KEY`/`OPENROUTER_API_KEY` to Cloud
Run - they weren't there before; refreshed `ANTHROPIC_API_KEY` to the user's newer key, still
unfunded per the entry below). Verified live post-deploy with a real `/elene/chat` call.

## OpenAI key fix + Anthropic fallback for the three LLM call sites (2026-08-09)
Root-caused via real Cloud Run logs (not guessed): every `/elene/chat` call was silently failing with
`401 Incorrect API key provided` from OpenAI, which the backend already caught and turned into the
bland "I had a problem thinking just now." reply - explains why "create a calculator app" appeared to
do nothing on the phone (the request never got far enough to call `propose_update`). User refreshed
`OPENAI_API_KEY` in `.env`; deployed the new value to Cloud Run (`--update-env-vars`) as revision
`elene-backend-00043-mlt`, verified live with a real `/elene/chat` call - got back an actual reply, not
the fallback message.

Also added a real Anthropic fallback to all three OpenAI call sites (`/elene/chat`, `describe_screen`,
`game_move`) so a single bad/revoked/rate-limited OpenAI key doesn't take the whole assistant down
silently again - `ANTHROPIC_API_KEY` was already provisioned on Cloud Run (present in `.env`, just
never wired into code), so no new secret needed. Added `anthropic` to `requirements.txt`.

**Self-tested for real, not assumed - and found a real gap this way**: ran the backend locally with a
deliberately broken `OPENAI_API_KEY` to force the fallback path (the live deploy's own test didn't
exercise it, since the real key just worked). The code path itself is correct - it genuinely caught
the OpenAI failure and attempted Anthropic - but the Anthropic call itself then failed with "Your
credit balance is too low to access the Anthropic API." **So the fallback is code-complete but not
actually functional yet**: the Anthropic account behind that key has no billing/credits. Needs the
user to add credits at console.anthropic.com/settings/billing before this provides real protection -
flagged clearly, not claimed as working.

## Self-update routine: event-driven trigger instead of pure hourly polling (2026-08-09)
User asked why the routine waited up to an hour instead of firing as soon as they made a request.
Reduced the cron fallback from hourly to every 4 hours (`34 */4 * * *` - the API's stated minimum
interval is 1 hour, so 4h is the sparsest reasonable safety net) and added a real GitHub webhook
trigger via `RemoteTrigger action=create_webhook_trigger` so the routine fires close to immediately
when there's real activity, instead of relying on the poll alone.

The webhook body shape wasn't documented anywhere available - discovered it by iterating on real
HTTP 400 validation errors (`scope`/`filter` as I first guessed were rejected outright; the real
required shape is `hook_type` ("app"), `source` ("github"), `scope_id` (`"org/repo"`), `events`
(`["issues"]`)). Created successfully (`trigger_id` `429eecb4-0a1e-4b5e-9a5f-611340b8ce58`) - fires
on GitHub `issues` events for this repo, which includes the `labeled` action, so approving on the
Updates screen (which adds the `approved-backend-update` label) should now trigger a near-immediate
run. **Known imprecision**: couldn't find a way to scope the webhook to only the `labeled` action
specifically - it'll also fire on other issue activity (comments, closes, etc.) on this repo. Judged
harmless rather than worth blocking on: the routine's own step 1 does a real `gh issue list --label
approved-backend-update` check and exits immediately if nothing's pending, so extra fires just cost
an idle routine run, not incorrect behavior. **Not yet confirmed live**: an actual approval-driven
webhook fire hasn't happened yet - first real test will be the next real approved proposal.

## Android SDK/NDK Cloud Build pipeline wired into the production self-update routine (2026-08-09)
After the mechanism below was proven end to end (real Cloud Build auth without `gcloud`, and a real
successful build of this exact app), the user gave explicit go-ahead ("yh start") to wire it into
the live hourly routine (`trig_01XxHRhbPpqZWmDnmVBSCqSe`). Did so via `RemoteTrigger action=update`:
the routine's prompt now branches on scope (backend vs. Android) at step (b), and a new "ANDROID APP
CHANGES" section (steps j-o) covers the whole app-side flow - implement the Kotlin change on a
dedicated `auto-update-issue-<N>` branch (never straight to the default branch, since this path
cannot locally compile-check anything), trigger a real Cloud Build job via the proven JWT/REST
mechanism (`GITHUB_PAT` pulled from Secret Manager via Cloud Build's own `availableSecrets`, not
embedded anywhere new), poll it to a real terminal state, and only merge + hand back a signed 7-day
GCS download URL on a genuine `SUCCESS` - never a silent install, matching this app's own established
"no silent install" model. On `FAILURE` the branch is left unmerged and the issue gets the real
build-log error, not a paraphrase. SCOPE/SAFETY BOUNDARIES updated to cover `app/` alongside
`backend/elene/`, and both additive and removal changes are authorized on both paths (fingerprint
approval on the phone already covers removal, per the user's explicit answer earlier this session).
Update call returned HTTP 200 and the routine was read back to confirm the new content landed intact
(private key, Android section, and updated SCOPE boundaries all present, no drift). Scratchpad key/
script/prompt-draft files (`android-pipeline-key.json`, `build_android_prompt.py`,
`android_prompt_v3.txt`, `current_prompt_v2.txt`) deleted immediately after confirming the update
landed.

**Real, honest exposure disclosure**: a live service-account key (`elene-backend-deployer`, key ID
`18137d543ce65b04a8097edbe664d68d30f8de8d`, scoped only to Cloud Build/the `cedal-fd4a2-android-
builds` bucket/`elene-backend` Cloud Run deploys - no IAM/billing/other-resource access) is now
embedded in this routine's stored prompt, same pattern already accepted for the backend deploy path
earlier this session before that path turned out to be moot (`gcloud` unavailable). Unlike that
earlier attempt, this one is real and load-bearing - the Android build path genuinely needs it to
function. **Not yet proven**: an actual unattended run of the production routine exercising this
new path end to end - everything above was validated interactively and via a manual container-image
build/test, not through the routine itself firing on a real approved Android-scoped issue yet. The
next real approved Android proposal from the Updates screen will be the first true end-to-end proof.

## Android SDK/NDK Cloud Build pipeline - proven end to end (2026-08-09)
Confirmed the whole Cloud-Build-based idea the user proposed after the `gcloud`-in-sandbox dead end:
real Android SDK/NDK builder container image built and pushed to a new Artifact Registry repo
(`us-central1-docker.pkg.dev/cedal-fd4a2/android-builder/android-sdk-ndk`), exact versions matching
`app/build.gradle.kts` (`platforms;android-34`, `build-tools;34.0.0`, `ndk;27.1.12297006`,
`cmake;3.22.1`). Then used it to actually build **this real app** via Cloud Build - not a toy
project: `./gradlew :app:assembleDebug` ran inside the custom image against the real repo source,
native RNNoise C code compiled cleanly via CMake/ninja, and a genuine 66,982,096-byte debug APK
came out (`BUILD SUCCESSFUL in 2m 53s`, all 42 Gradle tasks executed). Separately, an interactive
test (not an unattended routine - an attempt to test this via a scheduled diagnostic was correctly
blocked by Claude Code's own auto-mode safety classifier, since embedding a fresh credential in an
unattended context was the same pattern that already caused a real problem earlier the same day)
proved JWT-based service-account auth + raw REST calls to `cloudbuild.googleapis.com` work with
zero `gcloud` CLI involvement - a trivial test build was triggered and confirmed reaching `SUCCESS`
by polling, not assumed from the initial response.

**Real, honest scope of what's proven vs. not**: the mechanism (auth without `gcloud`, triggering
Cloud Build, this app's actual native-code build succeeding inside a custom image) is proven, with
real evidence at every step. What's NOT done: wiring any of this into the actual production
self-update routine - that would mean embedding a credential into an unattended automated context
again, and given today's earlier experience with that exact pattern, needs the user's explicit
go-ahead rather than being assumed as the natural next step just because the underlying idea now
checks out. See `planner/not_started.md` for the full detail and the open decision.

## Persona rename: Elene -> Xenos, female -> male voice (2026-08-08)
User-requested rename, deliberately scoped to user-facing text/behavior only - internal code
identifiers (class/file names like `EleneApiClient`, `HeyEleneWakeWord`, SharedPreferences keys
like `elene_voice_on`, log tags, the `/elene/*` backend routes) were left unchanged since renaming
those has real regression risk for zero user-facing benefit. Real naming collision caught and
resolved before editing anything: "Xenos" was already used in this app as the user's own honorific
(backend prompt) and developer credit (About/More Apps screens) - user's explicit call was to keep
using "Xenos" for the assistant and simplify the user's address to just "Emperor" instead.
- **Backend** (`main.py`): system prompt persona ("You are Xenos..."), all in-prompt self-references
  (vision/game-move modules), the addressing-the-user logic simplified to always "Emperor" (dropped
  the old "Xenos" fallback since that name now belongs to the assistant), and
  `ELEVENLABS_VOICE_ID` now reads `ELEVENLABS_MALE1_VOICE_ID` instead of `..._FEMALE1_...` - added
  that env var to Cloud Run (value from the project's own `.env`, one of three male voices already
  provisioned there). Deployed, revision `elene-backend-00042-mx5`.
- **Android app**: catalogued via a dedicated research pass first (388 raw hits across 34 files) to
  separate real user-facing strings from internal identifiers before touching anything. Updated:
  every Settings/Security/Capabilities/Command-Reference/Memory/Onboarding/About screen string that
  names or gives pronouns for the assistant ("she"->"he" throughout), the accessibility service's
  system-visible label/description (`strings.xml`), notification content title, and all spoken TTS
  lines that self-identify ("Xenos here...", "Speak to Xenos", etc.).
- **Wake word, done carefully because of pronunciation**: "Xenos" is pronounced "Zenos" - Android's
  speech recognizer transcribes what's *said*, not the intended spelling, so both
  `ScifiAccessibilityService.containsWakeWord()` and `MainActivity.onUserText()`'s legacy matching
  now check for "zenos" variants as the primary match (with "xenos" kept as a fallback in case STT
  ever spells it literally) - matching the exact lesson already learned building the personalized
  Hey-Elene-now-Hey-Xenos audio-match feature earlier the same day.
- Builds clean, installs on the real device with no crash (confirmed via logcat).
- **Confirmed by inspection, not yet confirmed live**: the backend deploy succeeded and non-LLM
  endpoints (`/pink`, `/elene/evacuate_backup`) both verified working post-deploy (no regression),
  but `/elene/chat` itself could not be end-to-end verified against the real persona - found a real,
  separate, pre-existing problem via Cloud Run logs: the configured `OPENAI_API_KEY` is being
  rejected by OpenAI as invalid (`401 Incorrect API key provided`), unrelated to anything changed
  today (the deployed key matches `.env` exactly - the key itself needs replacing, a user action,
  not a code fix). **Also not yet confirmed live**: the user's own "Hey Elene" voice recordings from
  earlier today no longer match the new wake phrase and need re-recording for "Hey Xenos".

## Self-update routine unattended deploy - attempted, proven not viable, reverted (2026-08-08)
**Correction to an earlier entry in this file**: this was originally logged as "wired in" -
that claim was wrong, found out the hard way, and is corrected here rather than left standing.

User's explicit decision was fully unattended deploy of approved backend changes (fingerprint
approval unchanged as the only human checkpoint). A service account
(`elene-backend-deployer@cedal-fd4a2.iam.gserviceaccount.com`) was created and, after real
trial-and-error against actual deploy errors, granted `roles/run.developer` (project-wide,
unconditioned - three attempts at scoping it to just `elene-backend` via IAM Conditions all failed;
`gcloud policy-troubleshoot iam` showed the condition evaluates as `UNKNOWN_CONDITIONAL` for this
permission path, a real limitation, not a syntax bug), `roles/cloudbuild.builds.editor`,
`roles/iam.serviceAccountUser` on the project's actual Cloud Build identity
(`717899371194-compute@developer.gserviceaccount.com` - the legacy `@cloudbuild.gserviceaccount.com`
doesn't exist in this project), `roles/artifactregistry.writer` on `cloud-run-source-deploy`, and
`roles/storage.admin` project-wide (had to go project-wide after two narrower storage roles both
failed - `storage.buckets.list` is inherently project-level, can't be scoped to one bucket). Each
escalation past the original "just elene-backend" plan was surfaced to the user before granting it.
A manual deploy authenticated as this identity did succeed (revision `elene-backend-00041-shz`,
verified live) - **but that only proved the credential works when `gcloud` is available to use it**,
which turned out to be the load-bearing assumption that was wrong.

**A key was embedded directly in the routine's stored prompt** (the only mechanism available - no
MCP connector exists for this) so the routine could authenticate. This was disclosed to the user as
a real, accepted exposure before doing it.

**Then a follow-up diagnostic (prompted by the user asking about a Cloud-Build-based alternative for
Android builds) proved the whole mechanism was never going to work**: `gcloud` cannot be installed
in this sandbox by any method. A dedicated one-off diagnostic routine tried all three official
Google Cloud SDK distribution channels - the `packages.cloud.google.com` apt repo, the
`sdk.cloud.google.com` installer script, and a direct `dl.google.com` tarball download - and every
one hit a real 403 at the sandbox's own egress gateway (`connect_rejected` / policy denial),
confirmed via the proxy's own status log, not inferred. This is the identical class of block already
found for Android SDK tooling - not specific to this app, a general property of this cloud sandbox's
network policy. Since there's no `gcloud` binary, the credential could never have been used no
matter how correctly it was scoped - the entire deploy-credentials effort was built on an assumption
that was never actually verified through the routine itself, and turned out to be false.

**Worth noting**: that same diagnostic session independently refused to write the embedded private
key to disk or use it for auth, flagging - correctly - that a live-looking credential embedded in an
unattended, automated prompt is exactly the shape of a credential-exfiltration attempt, and that it
had no way to verify who really authored the surrounding justification. It was moot here (no
`gcloud` to authenticate with anyway), but it's a real, working independent safety signal worth
recording, not just a footnote.

**Cleaned up immediately once found, same session**: the exposed key was revoked
(`gcloud iam service-accounts keys delete`), and the routine's stored prompt was reverted -
private key material fully removed, step (e) restored to honestly checking for `gcloud` and stopping
if it's absent, now explicitly documenting this as a confirmed permanent environment limitation
rather than an open "maybe next time" question. The service account and its IAM grants were left in
place (dormant, unused) rather than torn down, in case a future path - e.g. the Cloud-Build-based
approach discussed below - ever makes them usable again.

**Real conclusion, not a workaround**: this specific sandbox cannot run `gcloud` at all, for backend
*or* Android deploys, so no prompt-level fix can close this gap - it needs either a different
execution environment/egress policy, or (see `not_started.md`) routing the actual `gcloud`-dependent
work through something like Cloud Build, triggered by a lighter-weight call the sandbox's own
network policy might permit - unconfirmed, not yet tested.

## Updates screen Stage 2 self-update - real gap found + manually closed out (2026-08-08)
- User asked directly whether the self-update pipeline (built 2026-08-02) actually works - checked
  with real evidence (GitHub issue #3's full comment history, not memory) instead of assuming
- **Confirmed working**: the approve-on-phone → GitHub-issue → scheduled-agent → implement →
  self-test chain is genuinely real - the agent correctly read the request, wrote a working
  endpoint, ran a real local server, and curl-verified it, every single time
- **Confirmed broken**: the deploy step. The scheduled agent's own sandbox has never had `gcloud`
  installed - 19+ consecutive hourly runs (~21 hours) all hit this and honestly reported it instead
  of faking success (one run even flagged the loop explicitly: "retrying this hourly isn't going
  to produce a different outcome")
- Manually closed the loop for this one case: applied the agent's already-committed change
  (`bf09c1d`, a `/pink` endpoint) onto the current `main.py` (which had since gained an unrelated
  `/elene/evacuate_backup` endpoint from other work) rather than deploying that commit directly,
  so the deploy carried both instead of rolling one back. Deployed (revision
  `elene-backend-00040-dcg`), verified live via a real HTTP request (`{"status":"okay"}`, HTTP
  200), re-verified `/elene/evacuate_backup` still worked post-deploy (no regression), then
  commented on and closed issue #3 with that evidence.
- **Open decision, not resolved**: whether to give the scheduled routine real `gcloud`/GCP
  credentials so future approved changes deploy unattended, or accept "implements + tests, a human
  deploys" as the permanent shape of this pipeline - a real, consequential call (handing deploy
  credentials to an unattended scheduled job), not made here. See `planner/not_started.md`.

## Hey Elene - personalized accent-independent wake-word matching (2026-08-08)
- Requested directly: the user's accent made the default "Hey Elene" detection miss, since it
  works by transcribing speech via Android's SpeechRecognizer and text-matching the result - if
  the transcription doesn't contain the phrase, no amount of saying it right triggers anything
- New `HeyEleneWakeWord.kt`: record 5 reference takes of "Hey Elene", extract the same Kaldi-
  style fbank features already used for Voice ID (`SpeakerFbank`, the vendored kaldi-native-fbank
  JNI - reused as-is, no new native code), and match a live short capture against those templates
  with Dynamic Time Warping (DTW) - a classic template-matching technique, no model training
  needed (unlike the openWakeWord path already scoped as blocked in `planner/not_started.md`).
  Threshold is calibrated from the recorded takes' own pairwise DTW distances (with a safety
  margin), not a fixed guess.
- Critically, the live wake-word capture bypasses SpeechRecognizer/STT entirely once enrolled -
  a raw ~2.5s `AudioRecord` capture (reusing `VoiceCapture.recordVoiceSample`, denoised + VAD-
  trimmed) instead of a SpeechRecognizer session, so detection no longer depends on Android's STT
  getting the words right at all. Automatically falls back to the existing STT-text-match path if
  nothing's been recorded - no behavior change for anyone who doesn't use this.
- `recordVoiceSample` gained a `requestFocus` param (default `true`, preserving existing Voice ID
  behavior) - the new wake-word check cycle passes `false` so it doesn't grab transient audio
  focus (and duck/pause media) every few seconds in the background, matching why the existing
  STT-based wake check already skips AudioFocus for passive checks.
- New "Record my voice for 'Hey Elene'" row in Settings, next to the existing "Always listening"
  toggle, with an info dialog honestly disclosing this is a simpler technique than a real trained
  wake-word model and can still occasionally miss/mis-trigger.
- UX fix same session, from real user feedback before any device testing even started: the first
  version only updated a small subtitle line on the settings row while recording ("Say... (2/5)")
  - easy to miss since the user has to look at the phone while also speaking. Replaced with a
    real modal dialog (large "Hey Elene" title, "Say it now" / "Take 2 of 5" counter, a Cancel
    button) that stays up for the whole 5-take sequence, instead of relying on a tiny row label.
- Builds clean, installed on the real device with no crash (confirmed via logcat - clean launch).
- **Not yet confirmed live**: recording real takes and testing whether "Hey Elene" now actually
  triggers reliably for this accent, whether it holds up over repeated real use, and whether the
  auto-calibrated threshold needs adjustment (too tight = misses, too loose = false triggers) -
  needs the user's own hands and repeated real-world use, not just one test.

## Phoenix Protocol, small version - evacuation backup before wipe (2026-08-07)
- Scoped-down piece of the deferred full Phoenix Protocol (see `planner/not_started.md` for why
  the full detect/freeze/evacuate/restore flow stays deferred) - just a one-way backup of the
  intruder-capture photos and location history, not the whole app's data, not a restore flow
- New backend endpoint `POST /elene/evacuate_backup` (`backend/elene/main.py`) - uploads a
  per-run `manifest.json` plus each intruder photo as a real object into a new, private Cloud
  Storage bucket (`gs://cedal-fd4a2-elene-evacuation`, uniform bucket-level access, public access
  prevention on). Cloud Run's default compute service account was granted
  `roles/storage.objectAdmin` scoped to just this bucket, not project-wide. Deployed as revision
  `elene-backend-00039-mzv` with `EVACUATION_BUCKET` set.
- Android: `EleneApiClient.evacuateBackup()` (its own longer-timeout OkHttp client, since a batch
  of photos is bigger than the single-image calls elsewhere in this client) + `PhoenixEvacuation.kt`
  (gathers `IntruderCaptureLog`/`LocationHistory` entries, base64-encodes each photo file off disk)
- Wired into `SequenceMode.performSequenceWipe()`, now `suspend`: runs the upload first, only when
  `isFullWipeEnabled()` is true (matching the plan's own gating), before the existing wipe/delete
  steps - best-effort, wrapped in `runCatching` so a failed upload never blocks the real wipe
- New "Test evacuation backup now" row in Security > SEQUENCE MODE (self-contained, same pattern
  as the existing "Test voice match" button) - runs the exact same upload on demand without
  wiping anything, specifically so this can be verified safely instead of only by way of a real,
  irreversible 30-day wipe
- Honest scope note (also in the button's own info dialog): "encrypted" here means HTTPS in
  transit plus Cloud Storage's standard encryption at rest - the same trust model the rest of
  this backend already uses (no other endpoint does client-side/end-to-end encryption either),
  not a new zero-knowledge scheme invented for this one feature
- **Confirmed working, not just built**: the backend endpoint was smoke-tested directly (not just
  `ok:true` trusted blindly) - a real manifest and a real 1x1 JPEG both showed up in the bucket via
  `gcloud storage ls`, confirmed byte-for-byte present. Builds clean
  (`compileDebugKotlin`/`assembleDebug`), installed on the real device with no crash (confirmed
  via logcat - clean launch, no FATAL/AndroidRuntime).
- **Confirmed working live 2026-08-08** - user tapped "Test evacuation backup now" on the real
  device and it succeeded, after one real bug found and fixed via logcat (a `SocketTimeoutException`
  from the upload client's `readTimeout`/`writeTimeout` still defaulting to 10s despite `callTimeout`
  being raised - Cloud Run's ~9.5s cold start alone nearly exhausted that budget; fixed by setting
  `connectTimeout`/`readTimeout`/`writeTimeout` explicitly, see experience.md).

## Anti-theft mode toggle (2026-08-07)
- New "Anti-theft mode" toggle in Security > SEQUENCE MODE (default ON, matching the existing
  always-on behavior so nobody's protection silently changes on upgrade)
- Turning it OFF silences Elene's proactive motion-based "are you running, or is everything OK?"
  alerts entirely: the speak, the "Are you OK?" notification, and the 10-minute confirm-or-arm
  countdown - `MotionTheftDetector.onMotionSpikeDetected()` early-returns when off
- Turning it OFF also immediately cancels any *already in-flight* alert
  (`cancelPendingMotionAlert()`) rather than leaving it to time out up to 10 minutes later, and
  `MotionConfirmTimeoutWorker` re-checks the toggle at fire time so a mid-countdown toggle-off
  resolves the alert without arming
- Manual lockdown, the N-failed-fingerprint auto-arm, location tracking, and full-device wipe are
  all untouched by this toggle - it only silences the motion-based proactive check-in
- Info dialog on the toggle explains exactly what turning it off does and doesn't affect
- Builds clean (`compileDebugKotlin`, `assembleDebug`), installs on the real device with no
  crash (confirmed via logcat - clean launch, accessibility service reconnected, bubble showing)
- **Confirmed working live 2026-08-07** - user tested on the real device

## Updates screen Stage 2 - real backend self-update pipeline (2026-08-02)
- `/elene/submit_update_request` backend endpoint, called only after a real fingerprint approval
- A real scheduled cloud agent (hourly) that polls GitHub issues, implements backend-only
  changes, self-tests against a real local run, deploys to Cloud Run, verifies against the live
  service, and comments/closes the issue - or leaves it open with a clear explanation if anything
  doesn't check out
- Deliberately backend-only scope - app-side (APK) self-update still needs a real local session
  or the separately-deferred remote-auto-update mechanism
- **Not yet confirmed live end-to-end** - no real proposal has gone through the full chain yet

## Screen recording
- Pause/resume/stop controls in the recording overlay toolbar (draggable, collapsible, icon-only)
- Real GLSL box-blur (was a solid block before) + working crop for redaction
- Partial-screen recording area selection + audio source picker (None/Mic)

## Elene (voice assistant) core
- Consolidated to ONE brain (ScifiAccessibilityService) - launcher's separate bubble deleted
- Multi-command execution fixed (backend can return several commands per turn, now deduped)
- Continuous listening with call/media safety gating
- Voice-controlled volume and brightness
- Fingerprint-first device-action confirmations (tap/voice "yes" only triggers the biometric
  prompt, never approves directly)
- Shizuku integration for genuine force-stop
- installed_apps / recently_opened_apps context so Elene can answer "which apps do I have"
- DuckDuckGo (fallback Opera Mini) set as the preferred browser everywhere the app opens a link
- CMD reference screen under Security > Data & Media - lists every voice command + example phrase

## App lock / security
- Fixed: locked apps could be opened via Android's Recents/task-switcher, bypassing the PIN/
  fingerprint check entirely (only launcher-icon taps were ever gated)
- Added an instant black overlay cover to shorten (not eliminate - see experience/) the flash
  of a locked app's content before the lock prompt appears
- Security screen, Settings screen, and the Notifications panel now require fingerprint to open
- "Change passphrase" option added for the old 2-Step Verify text passphrase

## Voice ID (speaker verification) - Phase 1: core matching
- Replaced Google's FRILL (general-purpose embedding, inconsistent for this use) with WeSpeaker's
  ECAPA-TDNN (real speaker-verification model) via ONNX Runtime
- Real Kaldi-style fbank feature extraction in native C++ (first native/NDK build in this project)
- 5 enrollment styles (long/medium/short/alphabet/numbers) with per-style thresholds
- Min-3-word anti-replay concept explored, then simplified back to plain record+verify (see
  experience/ for why)

## Voice ID - Phase 2: VAD + noise suppression
- Silero VAD (real neural voice-activity detection) replacing a naive RMS-energy silence trim
- Classic RNNoise (v0.1, tiny ~425KB model) + Speex resampler for real noise suppression before
  matching

## Anti-tampering / RASP hardening (confirmed 2026-07-31)
- APK integrity self-check comparing the running app's real signing cert against one baked in
  dynamically at build time
- Root/Magisk detection (su paths, known Magisk package IDs) + SELinux enforcing-mode check
- Reviewed every exported manifest component; fixed the one real gap found
  (CedalSharedSystemReceiver re-verifies a claimed sender's actual signature now, not just an
  allowlisted name)
- Layered native (JNI/C++) Frida detection - five independent signals (maps scan, port probe,
  thread names, process scan, timing heuristic), XOR-obfuscated detection strings in the binary
- All wired into a new Security > Integrity & Tamper Detection panel

## Voice ID - Phase 3 (partial, ongoing)
- General voice commands ("open WhatsApp") now actually GATE on a Voice ID mismatch instead of
  only logging a score afterward
- Enrollment changed from "average 3 takes into 1 vector" to a growing per-style pool of
  reference samples, matched by nearest-neighbor - directly targets false-rejects from natural
  voice variation
- Real AcousticEchoCanceler + Android AudioFocus added to Voice ID's own recording path
- Media playback no longer blocks Elene outright - requests real AudioFocus to pause well-behaved
  media apps instead of refusing to listen
