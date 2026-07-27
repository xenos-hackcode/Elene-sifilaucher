# SciFiLauncher — The Full Picture

*Written 2026-07-26. This is a complete, from-scratch description of what this project actually
is, how it looks and works today, what it's built from, what equipment it depends on, and what's
still ahead. It exists so that a future session (or a future you) can read one document and
understand the whole thing, instead of piecing it back together from source code. Everything in
here reflects the code as it stands right now, including today's removal of the per-app
fingerprint/PIN lock — if a future change contradicts something written here, trust the code and
update this file, not the other way around.*

## What this actually is

SciFiLauncher is a custom Android home-screen launcher, written from scratch in Kotlin and Jetpack
Compose, running as the default launcher on one specific phone: a Samsung Galaxy A54, restricted
in the build config to the `arm64-v8a` ABI because there is no other device to support. The app is
enrolled as Android **Device Owner** on that phone, which is what makes most of the more unusual
features possible — silently granting its own runtime permissions, enforcing Kiosk/Screen-Pinning
mode, forcing an OS-level lockscreen or full wipe through Device Admin, and so on. None of that is
achievable by an ordinary, non-privileged app; it's also precisely why this was never intended for
the Play Store or for any other device. It is a personal daily-driver project, built and hardened
against a real, specific threat model (a lost or stolen phone, a nosy person picking it up, a
malicious sideloaded app), not a product.

The project is really three things wired together: the launcher itself (home screen, app drawer,
settings, security center — all on-device Kotlin/Compose), an AI voice assistant called **Elene**
whose reasoning happens on a small Python backend in the cloud, and a constellation of smaller
subsystems — screen recording, a remote-desktop link to a Windows laptop, custom keyboards, a
sibling-app "Cedal" ecosystem — that all hang off the same launcher shell.

## How it looks

The whole UI is built around one consistent idea: a "hacker terminal meets sci-fi HUD" register.
Backgrounds are near-black (`#020202` behind the home screen, dark near-black gradients on
settings-style panels), text is `FontFamily.Monospace` everywhere, and section headers are shouted
in caps — SECURITY CENTER, NEARBY DEVICES, SETTINGS. There is no light, friendly Material Design
here; every screen reads like a console.

The signature background effect is a Matrix-style rain, though it's not literally falling
characters — it's thirty vertical columns of small circles, each with a bright white "head" dot
and a fading, theme-colored tail, animated continuously and independently per column. It runs
behind the Dashboard, the app drawer, Recents, Freezer, and the hidden/favorite/battery-app
management screens, and freezes outright when Battery Saver is set to Aggressive. A second,
independent implementation of the same falling-dot idea exists as a classic Android `View` (not
Compose) inside the Cedal keyboard's own background.

Layered on top of that is the app's loudest visual signature: glitch text. Every big heading, every
app icon, and the three-letter N/O/S navigation row at the bottom of Dashboard/Apps/Recents is
rendered as three RGB-offset copies of itself — a base layer plus a red-tinted and a green-tinted
copy, each independently jittered by up to ten or twelve pixels and reset every eighty
milliseconds. It's a genuine chromatic-aberration, VHS-glitch look, not a static color trick. The
app-drawer grid goes further, layering a slower, sine-driven per-icon horizontal shift with a cyan
flash overlay on top of the fast RGB split, so icons periodically look like a signal breaking up.

Colour is themeable: ten named two-tone themes ship in the app (Matrix, Terminal, Volcano, SciFi,
Hacker, Coder, Ocean, Violet, Neon, Ghost), each a primary/secondary `Color` pair, switchable from
Settings. The one thing that never changes with theme is the battery ring — green above 50%, amber
between 20 and 49%, red below 20% — because that's meant to be read at a glance regardless of
what colour scheme is active. The battery ring itself, `HudCircle`, is a circular gauge with a
colored progress arc and a green radial "charge fill" wedge animation that plays while the phone is
plugged in, with the percentage rendered as glitch text in the center. Smaller HUD widgets echo the
same visual language: a generic translucent rounded-pill outline (`HudPanel`), a rotating
three-ring radar sweep used purely for decoration (`MiniRadar`), and — inside the Quick Settings
panel — a radial battery gauge, ring-style toggle buttons, and rounded-square icon tiles instead of
ordinary Material switches.

Information-dense screens — Settings, the Security Center, About, Requests, the error log —
deliberately drop the busy matrix rain in favor of a calmer flat vertical gradient background, with
content grouped into bordered, slightly-rounded card sections and thin hairline dividers between
rows. They still share the monospace type and the active accent color, but they read more like a
real settings app and less like the sci-fi dashboard underneath them. That split — spectacle on the
action screens, restraint on the configuration screens — is a deliberate, consistent choice, not an
inconsistency.

## Architecture, in one paragraph

`MainActivity.kt` is the single host Activity for almost everything — well over three thousand
lines holding nearly all top-level Compose state (theme, dark mode, battery mode, hidden and
favorite apps, Elene's bubble state), the local command dispatcher that turns strings like
`open_app:whatsapp` or `schedule:120:download_app:tiktok` into real actions, every Device-Owner
action flow, and the fingerprint-gated device-action confirmation system. Sitting alongside it,
`ScifiAccessibilityService.kt` is a cross-app Accessibility Service that draws a small floating
Elene bubble over *any* app, not just this launcher, and is genuinely the only place Elene's
command handling lives — the launcher's home screen doesn't have a separate copy of Elene running;
it bridges into the same Activity via an intent extra only for the handful of commands that
actually need visible Activity UI (Settings, Security, the Apps-screen search box, screen-record
consent, the confirmation panel). Elene's actual reasoning — understanding what you said and
deciding what to do about it — doesn't happen on the phone at all; it's a single-file Python
FastAPI service deployed on Google Cloud Run, calling OpenAI for the language understanding and
ElevenLabs for the voice. And underneath all of that, a genuine native C++/NDK layer (the first
one built in this project) does real, on-device signal processing for the parts that shouldn't
depend on network calls: speaker verification, voice-activity detection, and noise suppression.

## The launcher core

The very first thing shown on a fresh app is the **Welcome screen** — full-screen "XENOS HACKER"
glitch text over the Matrix rain, its color slowly auto-cycling between green and red every five
minutes, tap-anywhere to continue. Before ever reaching the dashboard, a first-run user also has to
pass through **Onboarding**: a mandatory, non-skippable legal/identity gate ("if you are not part
of the Cedal Star company, please close and delete this app immediately") followed by a skippable
two-slide tutorial explaining that Elene learns from behavior over roughly two months to
personalize itself.

The **Dashboard** is the real home screen: live time and date, WiFi/Bluetooth/airplane-mode status
text, the battery HUD ring, up to six favorite-app icons floating above a HUD-styled dock, a shield
shortcut into Security, and the glitch-lettered N/O/S row for navigating to the app drawer, home,
and Recents. The **app drawer** (`AppsScreen`) offers a live search box, a grid-or-paged toggle
(paged mode shows twenty apps per horizontal-pager page), the full animated glitch-icon effect on
every tile, and a long-press bottom sheet for uninstalling, viewing app info, sharing the APK, or
giving an app a local nickname (`AppLabelPrefs` — a rename that only changes what the launcher
displays, not the app's real label). **Recents** is a simple grid of recently-opened apps sharing
the same nav row.

Around the app grid sits a family of dedicated management screens, all built on the same
Switch-list pattern over the Matrix background: **Freezer** for frozen/hidden apps, **Hidden Apps**
to toggle what shows in the drawer at all, **Favorite Apps** capped hard at six for the dashboard
dock, and **Battery Allowed Apps**, an allow-list of what stays usable when Aggressive battery
saver is active (worth noting honestly: the actual filtering logic behind battery-saver app
restriction is currently a stub that returns the full list unfiltered — the UI for configuring it
exists, the enforcement behind it doesn't yet).

**Settings** is the app's own configuration hub, laid out with the same panel/row design language
used in Security: theme and font-size pickers, 12/24-hour time format, battery-saver mode,
language, keyboard style, Elene's voice toggle, and the "keeps listening" continuous-listening
toggle with an info dialog spelling out the actual tradeoff (hands-free convenience versus
anything said after tapping the mic being treated as a possible command). Theming itself
(`ThemeConfig`/`ThemePanel`) is a bottom-sheet picker across the ten named color themes plus
light/dark appearance, with an explicit Save action rather than applying instantly. Language
support (`Language.kt`) covers English, Yoruba, Mandarin, Korean, French, Spanish, and German, with
a small hand-translated table of Elene's most common fixed phrases in every one of them, so those
specific lines don't need a round-trip to the backend.

A **Command Reference** screen documents, in plain language, every voice command Elene understands
— opening and searching for apps, navigating pages, toggling dark mode, flashlight, Bluetooth,
WiFi scanning, volume and brightness, starting and stopping screen recording, world clock lookups,
freezing and force-stopping apps, scheduling an action for later, and telling Elene to remember or
forget a topic to avoid — kept in sync by hand with the backend's own system prompt rather than
generated from it.

## Elene, the voice assistant

Elene is deliberately built as one brain with two entry points. The Accessibility Service overlay
bubble is the only place her commands are actually handled; the launcher's home screen doesn't
duplicate that logic, it just bridges into the same dispatcher when a command needs an on-screen
Activity (opening Settings, showing a PIN-era or biometric dialog, running the Apps-screen search,
showing the screen-record consent prompt, or the device-action confirmation panel). It's worth
being precise about what "handles a command" actually means here: Elene has no visual perception
of the screen — no screenshot, no OCR, no vision model. Every app-control action (the WhatsApp
"Continue to Chat" tap-through, any future tap-this-button command) works by searching the Android
Accessibility Tree for a matching text label, content-description, or resource-id and clicking
that node — reading the same structured "system data" any accessibility service gets, not
"seeing" the screen the way a person would. A button rendered as a bare icon with no accessible
label is invisible to her, full stop, with no visual fallback underneath the text-matching to
catch it. (See `planner/possibilities/possibilities.md` section E for the concrete failure modes
this causes.) The bubble
itself has four visual states — dormant (hidden), listening (white glow), replying (a red, green,
or blue glitch glow while a reply is pending or speaking), and unresponsive (briefly black, when
recognition or the network call failed) — and it deliberately doesn't duplicate itself on the
launcher's own home screen, since only one Elene surface should ever be visible at a time.

Continuous listening is real, but carefully gated: it will never open the microphone during a
phone or VoIP call (checked via audio manager mode, not just `TelephonyManager`, specifically so it
also catches WhatsApp/Zoom/Meet calls), and it used to refuse to listen at all while media was
playing — that was found to make the assistant unusable during normal media consumption, so it was
changed to request real, non-transient Android audio focus instead, which pauses well-behaved
media apps for the duration of the turn rather than fighting them for volume, then releases focus
once the reply is done or Elene goes fully dormant. The one absolute local override, checked before
anything else, is the phrase "stop listening" (and a couple of close variants) — matched as the
*entire* trimmed utterance, not a substring, specifically because an earlier substring-based check
caused compound commands like "open WhatsApp then stop listening" to have their first half silently
dropped.

Every actual understanding step happens off-device. The Cloud Run backend's `/elene/chat` endpoint
takes the spoken or typed text plus a context block (battery mode, dark/light mode, the current
on-screen text, the installed-apps list, recently-opened apps, topics to avoid, and whether Voice
ID currently has an enrollment on file), feeds it into a long hand-written system prompt defining
Elene's whole persona and command vocabulary, and calls OpenAI's `gpt-4.1-mini` in forced-JSON mode
to get back a structured `{mode, text, commands}` response — `mode` distinguishing plain
conversation from one or more device commands to execute in order. The persona itself is a terse,
mixed hacker-AI-and-butler voice ("Affirmative," "Negative," "Activated"), defaulting to addressing
the user as "Emperor" and switching to "Xenos" when someone else is implied to be present. Replies
are spoken either through ElevenLabs (the backend's `/tts` endpoint proxies text to ElevenLabs'
multilingual voice model and streams back real audio) or, if that call fails or isn't configured,
through on-device Android text-to-speech as a fallback — the client is written to treat anything
other than a clean 200 response as "fall back," not as an error to surface.

Every device-owner-level action Elene can take — installing, uninstalling, force-stopping,
granting a permission, anything with real consequences — passes through a single confirmation
gate first: shown and spoken out loud, logged to a permanent Requests audit trail regardless of
outcome, and never auto-approved by silence (an unanswered request snoozes rather than being
treated as a yes). Critically, saying or tapping "yes" to that confirmation only ever *triggers* a
fingerprint prompt — the fingerprint check is the one and only thing that actually approves the
action. That rule was never touched by today's lock-removal work; it's a separate, deliberate
safety boundary from the per-app lock that got removed, and it's staying.

## Voice ID — knowing whose voice it actually is

Alongside fingerprint approval, the app does real, fully offline speaker verification, called Voice
ID. It went through a full rebuild after its first version — built on Google's FRILL embedding —
turned out to be unreliable in real testing, showing the same speaker's cosine similarity swinging
anywhere from 0.60 to 0.75 across retries, because FRILL is a general-purpose embedding, not one
built for telling voices apart. The current version uses WeSpeaker's ECAPA-TDNN model, a model
actually designed for speaker verification, run entirely on-device through ONNX Runtime. Getting
audio into a shape that model can use required writing genuine native C++ feature extraction —
80-dimensional Kaldi-style filterbank features, computed with the exact frame length, shift,
windowing, and normalization WeSpeaker's own reference implementation uses, vendoring
`kaldi-native-fbank` and `kissfft` source directly into the project rather than fetching them at
build time.

Enrollment records five distinct styles of speech — a long phrase, a medium phrase, a single short
word, reciting the alphabet, reciting digits — each with its own prompt, recording length, and
match threshold (shorter, less-discriminative styles get more lenient thresholds), because a
single word and a full sentence don't compare fairly against one reference. Rather than averaging
several takes into one reference vector per style — which turned out to reject the user's own
voice too easily once real day-to-day variation (tired, sick, a slightly different accent) came
into play — enrollment now grows a capped pool of up to twelve reference embeddings per style, and
verification matches against whichever stored sample is closest, not an average of all of them.
Two more native audio-processing stages sit ahead of the embedding step: Silero VAD, a genuinely
stateful neural voice-activity model (its RNN state threads across every audio chunk, not just a
single fixed energy threshold like the version it replaced), trims silence before the embedding
step, and classic RNNoise — deliberately the old, tiny ~425KB version rather than the current
30–78MB rewrite — runs real DNN-based noise suppression beforehand, bridged through a vendored
Speex resampler since RNNoise only operates at 48kHz and everything else in the pipeline is 16kHz.

Where Voice ID is actually wired in today, after the lock removal: enrolling, adding samples, and
resetting enrollment in the Security screen all still require a fingerprint first, same as before.
General voice commands run a non-blocking best-match check against whatever's enrolled and log the
result rather than gating anything, since fingerprint remains the only thing that actually approves
consequential actions. The lock screen's old voice-unlock option went away along with the rest of
the per-app lock. What's explicitly not built yet: a true single-capture flow where one recording
serves both speech-to-text and the speaker embedding — Android's built-in speech recognizer doesn't
reliably expose raw audio during normal recognition, so anything needing both today records twice
in a row. A real-time wake-word and barge-in rebuild ("Hey Imperial," interrupting Elene mid-reply,
echo cancellation on the main listening path, a bandpass filter to stop music bass/treble from
false-triggering voice detection) is a fully specified but not-yet-built next phase, and is blocked
on a decision about how to get real wake-word training data collected and trained, since that's not
something achievable inside this coding environment alone.

## Security and anti-theft, after today's changes

The Security Center is the hub for everything defensive in the app, organized into panel sections:
Data & Media (Freezer, Hidden Apps, the Intruder Attempts log, a built-in file manager, the command
reference), Activity (the Requests audit trail, this app's own internal error log, newly-installed
apps and a toggle to watch for sideloads), Sequence Mode, Network Protection, the Cedal Shared
System toggle, Shizuku, Voice ID, and Kiosk & Lock Screen.

As of today, the **per-app fingerprint/PIN lock is gone entirely** — there's no more prompt when
tapping an app icon, no launcher-relock screen when returning from the background, no gate on
opening Settings, Security, or the notification panel, and no fallback passcode or 2-Step spoken
passphrase to set up. `LockPrefs.kt`, `AppLockCoordinator.kt`, `LauncherLockGate.kt`,
`LockedAppsScreen.kt`, and `PinCheckDialog.kt` were deleted outright, along with every state
variable and gate wired to them in `MainActivity.kt`, `AppsScreen.kt`, and
`ScifiAccessibilityService.kt` — including the Recents-task-switcher lock enforcement that used to
catch apps being resumed without going through a launcher-icon tap. Three fingerprint-gated
features were deliberately left completely untouched by that removal, because they were never the
thing that was causing friction: Elene's device-action confirmations, Voice ID's enroll/reset gate,
and Sequence Mode's exit.

That last one needed a small, honest fix. Sequence Mode — the anti-theft lockdown feature — used to
arm itself automatically after three wrong app-lock PIN attempts, and its only exit path was
embedded inside that same PIN-recovery flow. With the PIN gone, that trigger has nothing left to
hook into, so Sequence Mode now has a real manual control in the Security screen instead: a
"Trigger lockdown now" row (with an info dialog explaining exactly what it does — locks the phone
immediately if OS-level lockdown is enabled, starts the wipe countdown if that's turned on) when
it's not active, and an "Exit Sequence Mode" row — still fingerprint-gated, since exiting can be
the difference between recovering the phone and quietly cancelling a wipe timer someone shouldn't
be able to cancel — when it is. Everything else about Sequence Mode is unchanged: arming captures
the last known location, forces the real Android lockscreen through Device Admin, schedules a
WorkManager job that emails and WhatsApps resolved family contacts (father, mother, brother,
sister — looked up by label in the phone's own contacts) every twelve hours while it's active, and
schedules a second one-shot job that performs a full wipe roughly thirty days later if it's never
recovered. A dedicated notification-listener service used to hide notifications from any
individually-locked app; it's been rewritten to hide *all* notifications while Sequence Mode
lockdown is active instead, which is the one piece of that old behavior actually worth keeping —
whoever is holding a locked-down phone shouldn't be able to read incoming messages off the lock
screen.

The Storage screen's Intruder Attempts log — photos and click counts captured on a failed app-lock
PIN attempt — is being left in place even though its trigger is gone with the PIN system; it'll
just stay empty from here on rather than being ripped out for the sake of it.

Kiosk mode is a separate, unrelated feature that survived completely intact: a real toggle using
Android's own Screen Pinning API, blocking Recents, the notification shade, and the power menu
until unpinned, with this app's own custom notification/quick-settings bar standing in for what
Kiosk mode blocks. "Change Android lock screen" is a deliberate deep link out to Android's own
security settings, with an honest warning that removing the phone's own PIN also weakens disk
encryption, since that's not something this app can or should quietly do on someone's behalf.

Network Protection runs a genuine local ad/tracker blocker: a `VpnService`-based DNS sinkhole that
intercepts only DNS queries, checks the requested domain against a bundled blocklist, and either
forges a clean NXDOMAIN reply or forwards the query untouched to Cloudflare's `1.1.1.1` — nothing
else is routed through it, by design; it's a filter, not a tunnel. The same section links out to
the home router's own management portal (see Equipment, below).

The Cedal Shared System is an opt-in, off-by-default channel that lets this app exchange basic
version/configuration information with sibling apps from the same developer, verified by matching
APK signing certificate before anything is ever exchanged and gated behind a signature-level
permission — a deliberately narrow, allowlisted mechanism, not a broad device scan.

Shizuku integration is unchanged: if the separate Shizuku app is installed, paired through wireless
debugging, and its service running, this app can request genuine `adb shell`-level privilege for a
real `force-stop`, matching exactly what Settings → App Info → Force Stop does — falling back
honestly to the weaker "stop background processes" API when Shizuku isn't set up, rather than
pretending the stronger behavior happened.

## Screen recording

Recording runs in its own separate process, deliberately, because `MediaProjection` is a genuinely
crash-prone API and this app is the phone's home launcher — a crash in the recorder should never be
able to take the whole launcher down with it, and an earlier real on-device crash from an unguarded
call is exactly why that separation exists. It records H.264 to `Movies/SciFiLauncher/`, holds a
partial wakelock while active, supports real pause and resume, and shows two floating overlays
while recording: a pencil tool that draws genuinely, visibly, and is really captured in the output,
and a blur tool that only shows a thin outline live — nothing is ever burned into the visible
screen — and gets properly redacted afterward in a real post-processing pass: raw decode, a GLES
box-blur shader sampled from the actual source video (a genuine out-of-focus look, not a flat
color block), and re-encode, with the original recording left untouched if that pass ever fails.
Partial-region recording is chosen against a real snapshot of the current screen, so you're aligning
the crop against actual content rather than a blank canvas. Recording with the device's own media
audio (not just the microphone) is set up in the UI but explicitly not implemented yet — it needs
raw playback-capture APIs this project hasn't built.

## The laptop link

One of the stranger features here is genuine phone-controls-laptop remote desktop, running entirely
over the same Cloud Run backend Elene uses. A small Windows-only Python program
(`laptop_agent/agent.py`) generates a pairing token on first run, prints it as both plain hex text
and a scannable QR code, and opens a WebSocket to the backend's relay endpoint. Once a phone pairs
with the same token, the laptop side captures the primary monitor at a modest frame rate and
resolution and streams JPEG frames up; the phone renders those frames full-bleed with pinch-zoom
and pan, and sends mouse-move, click, scroll, and keystroke commands back down, which the laptop
agent replays with `pyautogui`. The backend itself never decodes or stores anything — it's a pure
pass-through relay keyed on the pairing token, which is explicitly treated as the entire
authentication boundary, the same way a password would be. Because this app is the phone's Device
Owner home launcher, forcing real device rotation for a proper landscape remote-desktop view turned
out to have device-wide side effects on system UI placement, so that was deliberately reverted in
favor of staying letterboxed in portrait and relying on pinch-to-zoom instead — a real tradeoff,
not an oversight.

## The Cedal ecosystem

Two nearly-identical custom system keyboards — Cedal and Xenos — exist as full `InputMethodService`
implementations, each themed differently but otherwise sharing the same QWERTY layout, shift/caps
handling, a symbols page, a twenty-entry clipboard history panel, and smart delete that removes back
to the previous sentence boundary rather than one character at a time. A third, much simpler
in-app (not system-level) Compose keyboard widget exists separately for text entry inside the app
itself. The Cedal Shared System, described above under Security, is the cross-app signaling
mechanism that ties this launcher to its sibling Cedal apps without ever trusting an unverified
package.

## Everything else worth knowing about

A real local network scanner does a best-effort sweep of the phone's own subnet, resolving
hostnames and heuristically guessing device types where it can, honestly documented as
non-authoritative since there's no non-root way to read a router's ARP table on modern Android — the
Nearby Devices screen pairs that with an actual six-second Bluetooth LE scan of what's really
broadcasting nearby. Location History records a location snapshot roughly every fifteen minutes
using whatever fix is already available rather than forcing a fresh GPS request, kept deliberately
separate from Sequence Mode's one-off "where is it right now" snapshot. A built-in file browser
reaches the same shared/external storage tier a laptop would see over USB, and deliberately cannot
reach other apps' private internal storage — that's not a limitation of this app, it's a real
Android sandboxing boundary no unprivileged (or even Device-Owner) app can cross without root. A
package-install watcher flags newly sideloaded apps and anything requesting a fairly long list of
sensitive permissions, logging every install it's seen either way. A Capabilities screen lays out,
permission by permission, exactly what the app can access and precisely where that's controlled —
an explicit transparency feature, not just a permissions list. Every device-owner-level action
request and its outcome lives permanently in a Requests log; this app's own internal errors are
kept in a completely separate log, on purpose, since "what did we do to the phone" and "what broke
inside this app" are different questions worth answering separately.

## What it's built from

The app is Kotlin and Jetpack Compose throughout, targeting `compileSdk`/`targetSdk` 34 with a
`minSdk` of 24, built with NDK 27.1.12297006 restricted to the single `arm64-v8a` ABI the Galaxy
A54 actually needs. Notable dependencies: `androidx.biometric` for the fingerprint prompts that
remain (device-action confirmations, Voice ID, Sequence Mode exit), OkHttp for all network and
WebSocket traffic, WorkManager for the periodic/scheduled anti-theft jobs, ZXing for QR
scanning, the Shizuku API/provider libraries, and ONNX Runtime for Android to run the two neural
models (the ECAPA-TDNN speaker embedding and Silero VAD) entirely on-device. The native layer
compiles to exactly two shared libraries — `libspeaker_fbank.so` (Kaldi-style feature extraction
for Voice ID, vendoring `kaldi-native-fbank` and `kissfft`) and `libdenoise.so` (RNNoise plus a
Speex resampler for noise suppression), kept as two separate libraries specifically because
RNNoise vendors its own older `kiss_fft` copy that would otherwise collide with the newer one
compiled into the speaker library. Both are built with a 16KB page-size link flag for Android 15+
compatibility, which doesn't extend to ONNX Runtime's own prebuilt library — a known, unresolved
upstream limitation, not something this project can fix on its own. Minification and resource
shrinking are both off in every build type; there's no ProGuard/R8 pass happening yet.

## The equipment behind it

This isn't just code — it depends on a real, specific set of hardware and services:

- **The phone**: a Samsung Galaxy A54, `arm64-v8a`, Device-Owner-enrolled — the one and only
  target device the build is configured for.
- **A laptop**: a Windows machine running `laptop_agent/agent.py` for the remote-desktop feature,
  needing `websockets`, `mss`, `pyautogui`, `Pillow`, and `qrcode` installed.
- **ADB / Shizuku**: for genuine shell-level app control beyond what an ordinary app can do,
  requiring the separate Shizuku app, wireless debugging paired through Developer Options, and
  (on most phones) redoing that pairing after every reboot.
- **Google Cloud Run**: hosts the entire Elene backend — chat, text-to-speech proxying, alert
  email, and the laptop-relay WebSocket all live on one deployed service.
- **OpenAI**: `gpt-4.1-mini` is the actual reasoning model behind every Elene conversation and
  command.
- **ElevenLabs**: supplies Elene's real spoken voice; on-device Android TTS is only the fallback
  when that call fails or isn't configured.
- **Gmail SMTP**: sends Sequence Mode's anti-theft alert emails, authenticated with an App
  Password rather than the account's normal login credentials.
- **WhatsApp**: the actual delivery channel for Sequence Mode's periodic family alerts, sent via a
  `wa.me` deep link tapped through automatically.
- **Sky** (the home ISP/router): the Security screen's "Router" row points directly at Sky's own
  `myrouter.io` self-management portal.
- **Cloudflare's `1.1.1.1`**: the upstream DNS resolver every non-blocked query gets forwarded to
  by the tracker-blocking VPN.
- **DuckDuckGo and Opera Mini**: the two browsers the app actually expects to be installed and
  opens links in, in that order, before falling back to whatever the system resolves.

## Where things stand, and what's still ahead

Per the project's own planner, a lot has been built but not all of it has real, confirmed
on-device test results logged yet — screen recording's pause/resume/redaction, Elene's
multi-command execution and installed-apps awareness, the Recents-lock bypass fix (now moot, since
the per-app lock it protected no longer exists), the flash-before-lock-prompt mitigation (also
moot for the same reason), and the newest Voice ID work (the multi-sample pool replacing averaged
enrollment, and Elene no longer going fully deaf during media playback) are all functionally
finished but still waiting on a real, honest test pass rather than an assumption that they work.
That's a standing rule for this project, not a one-off note: nothing gets marked done without a
real confirmed result, because the project has been burned before by moving on before confirming
the last fix actually held.

Still not started: the rest of Voice ID's real-time pipeline (a genuine wake word, echo
cancellation on the main listening path, a bandpass filter ahead of voice-activity detection,
barge-in, game-mode audio handling) — blocked specifically on how to get real wake-word training
data collected and trained, since that's not a pure coding task this environment can finish alone.
After that, a weekly background re-enrollment pass for voice drift is planned, though the
multi-sample pool already built may cover most of what that was meant to solve. Queued after Voice
ID wraps up: a real anti-tampering and RASP hardening pass — layered Frida detection, a
signature-level permission plus runtime caller-verification on exported components, root/Magisk/
SELinux checks, and an APK signature self-check to catch a repackaged copy of the app — followed by
the user's own hands-on pentest of this exact app, on this exact phone, using real tooling
(Frida, Objection, Drozer, Burp Suite/mitmproxy, apktool, MobSF). A short, explicit list of things
were asked about and refused outright, and are staying refused: cross-app data exfiltration via
accessibility or device-admin access, silently installing a CA certificate to intercept other
apps' traffic, and anything resembling a RAT, keylogger, or uninstall-resistant persistence
mechanism. Those aren't gaps in the plan — they were asked about and turned down on purpose.

Real bugs hit and fixed along the way are worth remembering, because most of them taught something
that shaped later decisions: a Compose layout defaulting vertical children to full width instead of
their actual size, `@Volatile` not being legal on a local variable, a private Shizuku API forcing a
pivot to the AIDL service pattern instead, an OS-level restriction that made the "Show taps" toggle
permanently non-functional (removed rather than left silently broken), a speaker-verification model
crash traced to per-frame embeddings needing mean-pooling instead of a single hardcoded shape, a
missing vendored header on the first native build, an unresolvable Android 15+ page-size warning in
a third-party prebuilt library that simply can't be fixed from this side, RNNoise linker errors from
a missing `extern "C"` guard, a Speex resampler that needed explicit compile-time mode definitions,
and — most tellingly — a digit-based anti-replay challenge that kept failing because Android's
speech recognizer collapses spoken digit sequences into a single numeral token, which got fixed and
then abandoned anyway in favor of something simpler once real use showed the extra friction wasn't
worth what it bought.

That last pattern — building something, testing it for real, and simplifying or reverting when the
real friction wasn't worth it — is close to the whole philosophy behind today's biggest change. The
per-app lock had gone through a real hardening pass (closing the Recents bypass, shortening the
flash-before-prompt window) and was, on its own terms, working. But it was also the single biggest
source of daily friction on a phone this app is used on every day, and rather than keep patching a
system that had already been debugged twice, the honest call was to remove it outright and lean on
the fingerprint gates that were never the problem in the first place — Elene's device-action
confirmations, Voice ID, and Sequence Mode's exit — to carry the actual security weight going
forward.
