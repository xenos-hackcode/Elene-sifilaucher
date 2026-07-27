# Experience - what real on-device testing actually showed

- FRILL-based Voice ID: same-speaker cosine similarity ranged ~0.60-0.75 across retries even
  after silence-trimming + per-frame normalization fixes - inconsistent enough that a real
  same-voice retry got wrongly rejected at a 0.75 threshold. Root cause: FRILL is a
  general-purpose embedding, not built for speaker verification. Led to the full ECAPA-TDNN
  swap (Phase 1).
- After the ECAPA-TDNN swap: user's sister said "open WhatsApp" and it executed - turned out
  general voice commands were only ever LOGGING a Voice ID score, never actually gating on it.
  Fixed by making commands wait for a passing check before running.
- After that fix: user's OWN voice started getting rejected - averaging 3 enrollment takes into
  one reference vector was likely too narrow for natural voice variation (tired, sick, accent).
  Fixed by switching to a growing multi-sample pool matched by nearest-neighbor, not average.
  ** Not yet re-tested against real accept/reject numbers after this fix. **
- Random-digit anti-replay challenge for the lock screen: kept saying "that didn't match" /
  "voice unrecognized" - partly the digit-collapsing STT bug (fixed), but user found the whole
  challenge-response flow too much friction given every app is already independently
  lock-gated anyway. Reverted to plain record-and-compare (no challenge step).
- Recents app-lock fix: after the first pass, going into Recents and closing it WITHOUT
  switching apps re-triggered the fingerprint prompt (a loop) - root cause was a purely
  time-based grace window, not real continuity tracking. Fixed by tracking which app is
  actively "owned" instead of just elapsed time. ** User confirmed this is "manageable" given
  fingerprint covers it, but has NOT confirmed the loop is fully gone. **
- Flash-before-lock-prompt: user directly observed seeing a locked app's content briefly before
  being kicked to the PIN/fingerprint screen (a real security concern, e.g. if the phone were
  stolen). An instant black overlay was added to shorten this, but it is NOT and cannot be fully
  eliminated on a non-rooted device - this is an accepted, disclosed limitation, not a fixed bug.
- Continuous listening used to go fully dormant (refuse to listen at all) whenever media was
  playing - user found this blocked normal use (couldn't ask Elene anything while music/video
  played). Changed to request real AudioFocus (pausing well-behaved media) instead of refusing
  to listen. ** Not yet tested on-device. **

- Real on-device test (2026-07-26/27), via adb screenshots + dumpsys, not assumed: Elene's
  floating bubble was confirmed visible over the actual Android lock screen after locking via the
  power button while an app was in the foreground (screenshot + `isKeyguardShowing=true` from
  `dumpsys window` both captured at once). Fixed (see errors/) and re-confirmed hidden across two
  separate lock/wake cycles after the fix. This is exactly the kind of thing the standing rule
  below exists to catch - it would never have shown up from a plain `gradlew compile` check, only
  from actually driving the real device through the exact sequence (lock via timeout/power button,
  not via switching apps).

- (2026-07-27) Messaging assistant / calendar / call awareness / voice memo feature: built and
  installed. Confirmed so far via adb (build, install, launch with no crash, all four new
  permissions - READ_CALENDAR/READ_PHONE_STATE/ANSWER_PHONE_CALLS/READ_CALL_LOG - silently
  granted, accessibility service still connected). **Not yet confirmed**: an actual message
  reply/compose-and-send round trip with a real contact, calendar meeting-awareness with a real
  event, the ringing-announcement with a real incoming call, whether `acceptRingingCall()`
  actually answers on this Samsung device (the one genuinely unknown piece per the plan), and
  voice-memo playback. None of this should be treated as done until each of those has a real,
  observed result - same standing rule as below. Backend deployed to Cloud Run (revision
  elene-backend-00022-nvv, 2026-07-27) and smoke-tested with a real HTTP request - responds
  correctly. The new verbs/context fields are now actually live and reachable from the device,
  not just written - but still unconfirmed end-to-end (a real "reply to my last message" etc.
  hasn't been tried against the deployed backend yet).

- (2026-07-27) Backend redeployed again (revision elene-backend-00023-qc4) after the WhatsApp/
  call/voice-memo follow-up fixes, specifically to document the new end_call verb. Smoke-tested,
  responding correctly. Standing instruction from the user: always redeploy the backend after a
  main.py change, don't wait to be asked each time.

- (2026-07-27) Retried the install after "device offline" - fixed with `adb reconnect offline`
  (no cable/settings change needed, just a stale adb session). Confirmed via real device query
  (not assumed): all four new permissions (READ_CALENDAR, READ_CALL_LOG, ANSWER_PHONE_CALLS,
  READ_PHONE_STATE) show `granted=true` under `dumpsys package`, the accessibility service shows
  connected under `enabled_accessibility_services`, and the app launches with no FATAL/
  AndroidRuntime in logcat. The call-screening role correctly shows *not* granted yet in
  `dumpsys role` - expected, since `ROLE_CALL_SCREENING` requires a real user tap through the
  Security screen's system role prompt, unlike the silently-grantable permissions. **Still not
  confirmed**: an actual live cellular call decline through the role, or any of the message/
  calendar/call-answer/voice-memo round trips listed as unconfirmed above - this entry only
  confirms the install is healthy, not that the features work end to end.

- (2026-07-27) Shizuku setup, done for real on this exact phone (Android 16 / SDK 36) and
  confirmed working via adb, not assumed: Play Store didn't have it available for this OS
  version, so the official release APK (v13.6.0, from `github.com/RikkaApps/Shizuku` releases -
  that specific version's changelog explicitly lists "Support Android16 QPR1") was downloaded
  and sideloaded with `adb install` instead. Real steps that worked, in order:
  1. Wireless debugging was already on (Settings > Developer options > Wireless debugging).
  2. Open the Shizuku app > Wireless debugging section > "Pair device with pairing code." Its
     own auto-search for the pairing service hung/never completed - worked instead by opening
     Settings > Developer options > Wireless debugging > "Pair device with pairing code" directly
     (which shows the IP, port, and a 6-digit code) and entering those into Shizuku manually
     rather than waiting on its auto-discovery.
  3. Pairing alone does NOT start the service - confirmed via `dumpsys activity services
     moe.shizuku.privileged.api` showing nothing right after a successful pairing. The service
     only actually starts after separately tapping **Start** in the Shizuku app's main screen
     (a different button from pairing).
  4. Confirmed truly running via `adb shell ps -A`: a `shizuku_server` process owned by `shell`
     (real ADB-shell privilege level, not just app-level), plus SciFiLauncher's own
     `com.example.scifilauncher:shizuku` user-service process bound to it. Both were present only
     after step 3, not after step 2.
  5. Granting SciFiLauncher's own access (Security > SHIZUKU > tap the row) worked cleanly once
     the service was actually running - it pops Shizuku's own system permission dialog, separate
     from anything this app draws itself.
  Per the code's own doc comment in `ShizukuManager.kt`: pairing is remembered across reboots, but
  the running service is not - expect to reopen Shizuku and tap Start again after most restarts
  unless the device is rooted. Shizuku must stay installed for this to keep working; uninstalling
  it makes `ShizukuManager.isAvailable()` go back to false and the app silently falls back to the
  weaker `killBackgroundProcesses()` path instead of a real force-stop.

- (2026-07-27) Real bug found and root-caused via live on-device evidence, not guessed: the
  install-watcher (PackageInstallWatcher) never fired for real installs, confirmed by installing
  a genuinely fresh APK (F-Droid client) and finding `install_flags_prefs.xml` never got created.
  Ruled out several plausible causes one at a time with real checks before finding the actual one:
  manifest registration (confirmed correct via `dumpsys package`), package-visibility filtering
  (added QUERY_ALL_PACKAGES, confirmed it genuinely expanded visibility via `dumpsys package
  ... Queries:`, receiver still didn't fire), battery/standby restrictions (app already in the
  best/EXEMPTED standby bucket). The real cause, found via `dumpsys activity broadcasts`: manifest-
  declared receivers for PACKAGE_ADDED are being skipped with "Background execution not allowed"
  on this Android 16 / One UI build - and tellingly, Samsung's own Galaxy Store install receiver
  (`com.sec.android.app.samsungapps/.receiver.PackageAddedReceiver`) was being skipped for the
  identical reason in the same broadcast history, confirming this is a platform/OEM change, not
  something specific to this app. Fixed by moving the logic to a receiver dynamically registered
  on the already-running ScifiAccessibilityService (same proven pattern as screenStateReceiver/
  voipCallReceiver) instead of a manifest `<receiver>`. Re-tested with another fresh F-Droid
  install after the fix: `install_flags_prefs.xml` now correctly records it (sideloaded=true,
  flagged permissions correctly identified) - confirmed working, not assumed.

- (2026-07-27) Screen perception (describe_screen) + turn-based game auto-play (play_game/
  stop_game) built per the plan in declarative-toasting-glacier.md. Confirmed so far: builds
  clean, installs with no crash, accessibility service reconnects. Backend endpoints
  (/elene/describe_screen, /elene/game_move) smoke-tested directly via curl with a real image -
  both return correctly-shaped responses (a real description, and a real {action, x, y,
  reasoning, game_over} decision). Backend deployed (revision elene-backend-00024-rbq).
  **Not yet confirmed**: the actual voice/chat-triggered end-to-end flow - the MediaProjection
  consent dialog appearing, a real screenshot being captured and correctly described, the
  game-loop actually tapping a real game, the app-switch auto-stop, and the safety caps. Tried
  to verify headlessly via adb UI-tapping and stopped partway through - the tap landed on the
  cross-app accessibility bubble while it happened to be floating over a real WhatsApp screen
  from actual prior device use, which is exactly the kind of unpredictable real state blind
  automated tapping shouldn't be guessing around. This needs real live testing (say "what's on
  my screen" / "play this game" to the phone), same as every other voice-triggered feature in
  this project - not something to mark done from a clean build alone.

- (2026-07-27) Two real bugs found via the user's own live testing of screen perception (not
  guessed): (1) telling Elene "play this game" while already in it opened the MediaProjection
  consent flow while continuous listening was still open (or reopened moments later, before the
  user had even tapped the consent dialog) - this OEM plays an audible "screen sharing started"
  confirmation once granted, which the still-open mic picked up and misread as a spoken command,
  sending Elene briefly haywire before she recovered. Same class of bug as the earlier incoming-
  call/mic collision - fixed by holding the mic closed (haltListeningForCapture(), a new
  awaitingCaptureConsent guard in startListening()) for the whole describe_screen/play_game
  bootstrap window, not just the instant it's triggered, clearing it once a real frame (or a
  failure) comes back. (2) "click on [a contact]'s chat" in WhatsApp kept landing on that
  contact's avatar (opening their profile picture) instead of the chat row - root cause:
  findNodeByText() took the very first accessibility-tree match for the name with no regard for
  what kind of element it was, and an avatar's content-description is often the same name as the
  row's own text label, with the avatar frequently coming first in tree order. Fixed to prefer an
  actual TextView/EditText match over an image/icon one when both match, falling back to the
  first match only if nothing text-like matched at all. Both fixes built, installed, no crash -
  **not yet re-confirmed live** by the user that either specific bug is actually gone.

- (2026-07-27) Updates screen (Stage 1 of self-updating Elene) built per the plan in
  declarative-toasting-glacier.md: a fingerprint-gated approval queue (UpdateProposalLog +
  UpdatesScreen, reached via Security > Activity > Updates), reusing the exact same
  requestDeviceActionConfirmation/BiometricAuthActivity flow every other device-owner action
  already goes through - not a separate/parallel approval mechanism. Confirmed so far: builds
  clean, installs with no crash, accessibility service reconnects. Backend redeployed (revision
  elene-backend-00025-fkq) with the propose_update:user:.../propose_update:elene:... prompt
  instructions. **Not yet confirmed**: a real voice-triggered proposal actually creating an
  entry and showing the live confirmation panel, an Elene-self-initiated proposal landing
  quietly in the Updates screen without interrupting, and reviewing/approving a queued entry
  from the screen itself with a real fingerprint scan. Deliberately Stage 1 only - no
  code-generation/build/deploy pipeline exists yet behind an APPROVED entry.

- (2026-07-27) Sandbox (isolated-process verification for Shizuku shell commands) built per the
  plan in declarative-toasting-glacier.md, after evaluating a pasted technical document: kept its
  genuinely real insight (`android:isolatedProcess="true"` is a real permission-stripped-UID
  mechanism, stronger than the existing `:recorder` crash-only isolation), declined its two
  unrealistic/inappropriate parts (an on-device compiler for semantic code analysis - not
  achievable on a phone; and a "Tier 3 - Owned Network" consent tier that re-authorized packet
  injection/ARP spoofing, already rated 5/hard-no in declined.md earlier this project - that
  verdict doesn't change on relabeling). Confirmed via real evidence, not assumed: builds clean,
  installs with no crash, and `aapt dump xmltree` on the actual built APK confirms
  `isolatedProcess=true`/`process=":sandbox"`/`exported=false` all compiled through correctly
  into the manifest binary. **Not yet confirmed**: the actual live gate working end-to-end (a
  real force-stop command being verified and passed/rejected by the isolated service) - Shizuku's
  own service needs its "Start" tapped again after this reinstall (a known, already-documented
  requirement) before that path can even be exercised, and the actual round trip needs a real
  triggered command while watching logcat, not a headless simulation.

- (2026-07-27) Real live-testing round on screen perception: user reported describe_screen "doesn't
  seem to work" and the AI's own voice affecting listening. Root-caused both from real logcat, not
  guessed: (1) describe_screen - the backend was replying in "chat" mode with a plausible-sounding
  fabricated answer ("Current screen shows the Game view...") instead of ever issuing the
  describe_screen command (confirmed via logcat: commands=[] both times "describe screen" was
  said) - fixed by strengthening the backend prompt's instruction, redeployed (revision
  elene-backend-00026-4hg). (2) Also found live, via the stuck-service investigation: after a
  single-shot capture or a MediaProjection self-revoke, ScreenPerceptionService could get stuck
  running forever (confirmed - the :recorder process and its foreground notification were still
  alive many minutes later, well after MediaProjection/BufferQueue logs showed the OS side was
  already torn down) - root cause was mediaProjection.stop() re-triggering the same onStop()
  callback re-entrantly with no idempotency guard; fixed with a tornDown flag + defensive
  runCatching around the callback body. (3) The mic-reopens-with-AI's-own-voice complaint was
  addressed by giving the two "TTS truly just finished" resume paths (speechDoneListener,
  playOverlayAudioBytes) a 500ms buffer before reopening the mic instead of 0ms, to let acoustic
  echo from the phone's own speaker settle first - **not yet confirmed this is the full/correct
  root cause**, since a manual bubble-tap re-engaging after "stop listening" was heard is an
  equally plausible innocent explanation for what was seen in the same logs; needs the user to
  specifically notice whether the issue persists after this delay change.
- (2026-07-27) Force-open Option A (Sequence Mode WhatsApp alert only) built: a narrow,
  automated-only keyguard bypass via Device Owner's setKeyguardDisabled(), scoped tightly to the
  exact span SequenceAlertWorker's WhatsApp send needs the screen, re-enabled the instant that
  send's own callback reports done (success or failure) - not a standing bypass anyone can
  trigger, per the declined.md precedent this was checked against. Builds clean, installs with
  no crash. **Not yet confirmed live**: an actual Sequence Mode alert firing while the phone is
  genuinely locked, confirming the keyguard actually gets bypassed just for that automated send
  and is fully restored afterward.

- (2026-07-27) Batch of four smaller features built together: (1) traffic proxy routing -
  TrackerBlockVpnService now reads an optional "ip:port" from Security > Network Protection and
  routes traffic through it via VpnService.Builder.setHttpProxy() (real Android API, API 29+) -
  deliberately does NOT reimplement packet reading/blocking/editing itself, since mitmproxy/Burp
  Suite (run by the user on their own laptop) already do that far better; this app's only job is
  routing. (2) Keep-screen-on while actively talking to Elene - both the cross-app overlay bubble
  (FLAG_KEEP_SCREEN_ON on its WindowManager.LayoutParams, toggled in setBubbleState) and the home
  screen's own bubble (a LaunchedEffect on bubbleState toggling the Activity window's flag) -
  cleared the instant either goes back to dormant, not a standing keep-awake. (3) Real research
  (not guessed) on offline/on-device LLM feasibility for this exact phone (Galaxy A54, Exynos
  1380, no real NPU) - conclusion: a full on-device LLM is technically possible but not advisable
  (2-5 tok/s realistic, real thermal throttling on repeated use in comparable-tier benchmarks),
  Gemini Nano/AICore is a hard no (requires 12GB+ RAM and a 2025-2026 flagship SoC), and the
  actually-correct approach for this app's narrow ~40-verb command set is a lightweight offline
  intent classifier (on-device SpeechRecognizer + fuzzy/embedding matching against known verbs),
  not a scaled-down LLM - logged for a future properly-scoped offline-fallback pass, nothing
  built yet. (4) Memory feature - RememberedFactLog (durable fact store, separate from the
  existing avoid_topics denylist) + MemoryScreen (Settings > Memory) + a new "remember_fact" verb
  + a remembered_facts context field fed back to the backend every turn specifically because the
  backend's own conversation history is only in-memory and resets on Cloud Run recycle - this is
  the durable fallback for that real, already-known limitation. Backend redeployed (revision
  elene-backend-00027-szt). All four: builds clean, installs with no crash. **Not yet confirmed
  live**: the proxy actually routing real traffic to a running mitmproxy/Burp instance, the
  keep-screen-on actually holding through a real conversation, and remember_fact/remembered_facts
  actually surviving a real backend memory reset in practice.

## Standing meta-note from the user (2026-07-26)
User explicitly flagged that we were "bouncing from one thing to another" - building fix after
fix without confirming each one actually works before moving to the next. This planner exists
to stop that: before starting something new, check in_progress/ and don't add to done/ until
there's a real confirmed test result logged here.
