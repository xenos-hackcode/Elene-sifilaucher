# Experience - what real on-device testing actually showed

- Multi Control (2026-08-10): added the `multi_control` voice verb to
  `backend/elene/main.py`, deployed to Cloud Run as `elene-backend-00046-5hp`, smoke-tested with
  `curl` against `/pink` (`{"status":"okay"}`) before calling it live. On-device: user tested both
  a 2-app and a 3-app freeform launch live; real logcat pull afterward (`AndroidRuntime:E` +
  `WindowManager:I` filtered) showed genuine `com.android.wm.shell.freeform.FreeformContainerView`
  windows created for each run and zero crash lines - confirms this is real OS freeform windowing,
  not a preview, and confirms the `ActivityOptions.setLaunchWindowingMode` approach actually works
  on this device from a Device Owner/launcher app.
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

- (2026-07-27) Intruder Attempts rebuilt for real - the old version (MainActivity.IntruderLog,
  StorageScreen) was confirmed dead: no photo/location fields at all, and nothing anywhere in the
  codebase ever populated it (leftover scaffolding from the per-app lock system removed earlier
  this project). New: SilentCameraCapture (headless Camera2 front-camera single-shot, no preview
  surface) + IntruderCaptureLog (photo path/location/timestamp), triggered from
  BiometricAuthActivity.onAuthenticationFailed() specifically - the real "a submitted fingerprint
  didn't match" signal, distinct from onAuthenticationError (covers the legitimate owner tapping
  Deny/cancelling/lockout, not an intrusion signal). Reuses SequenceMode's existing
  captureLastLocation/loadLastKnownLocation rather than building new location code.
  StorageScreen now actually displays photo thumbnail + maps link + timestamp per entry. Also:
  Memory feature got a recency-wins fix (no fact is auto-deleted on a contradiction - e.g. an old
  vs new stated name - but the newest fact is always listed first and explicitly marked, with a
  backend prompt instruction to trust it over an older contradicted one) plus confirmed the
  already-built tap-to-forget in the Memory screen satisfies "I should be able to delete what it
  remembers." Backend redeployed (revision elene-backend-00028-ng8). Builds clean, installs with
  no crash. **Not yet confirmed live**: an actual failed fingerprint scan producing a real photo +
  location entry visible in Security > Storage, and the memory recency-wins behavior actually
  working when two contradicting facts are told to Elene in sequence.

- (2026-07-27) Always-listening "Hey Elene" wake word built (Settings toggle, off by default,
  approximated via real SpeechRecognizer sessions on a ~4s repeating cycle since Android has no
  low-power wake-word primitive exposed to apps - disclosed honestly in the toggle's own info
  dialog rather than pretending it's free). Explicitly checks battery saver on every cycle and
  stays off the mic while it's on, per the user's own explicit requirement, auto-resuming once
  it's off again with no separate re-enable step. Bubble stays visually dormant during passive
  checks (not a real "engaged" turn) and only becomes a real listening turn once the wake phrase
  is actually heard. Builds clean, installs with no crash. **Not yet confirmed live**: whether
  the wake phrase is reliably detected in practice, the real battery cost of the ~4s polling
  cycle over hours of use, and that it genuinely stops during battery saver.
- (2026-07-27) Declined adding a pasted "security research capability" + rewritten propose_update
  block to the backend system prompt. Two concrete problems, not just a general refusal: (1) it
  told Elene to never refuse/hedge for RATs/exploit code/payloads based on a self-declared
  "authorized penetration tester" claim baked into her own prompt with no real verification -
  a jailbreak-shaped pattern, and Elene has real device-command capabilities (Shizuku, screen
  control) that make that a genuine risk, not a theoretical one; (2) its "Stage 2 sandbox
  validation" description was factually false - claimed an automatic scanner for "permission
  escalations" and "data exfiltration patterns" that doesn't exist; what's actually built
  (SandboxVerificationService) only pattern-matches a handful of Shizuku shell command shapes.
  Shipping that text would have made Elene misrepresent the app's real security posture to the
  user. Not added; main.py unchanged from this specific request.

- (2026-07-27) Real audio-pipeline improvements. Discovered during research that two of the four
  requested items (WebRTC AEC / RNNoise, ECAPA quantization) were already partly built earlier in
  this same long session but not captured in this log: VoiceCapture.kt already applies a real
  Android AcousticEchoCanceler and a real RNNoise C library (app/src/main/cpp/rnnoise, JNI) to the
  Voice ID capture path, and SpeakerEmbedder.kt already uses ECAPA-TDNN via ONNX Runtime with
  multi-sample-pool enrollment (already the "improved enrollment strategy" ask, in part). Confirmed
  the one true architectural wall: AEC/RNNoise can't reach the main conversational SpeechRecognizer
  pipeline (the 500ms-delay hack) because SpeechRecognizer owns its own internal mic capture with
  no raw-audio hook for the app - user chose to keep the delay hack rather than replace
  SpeechRecognizer with a custom capture pipeline (real regression risk to the most bug-prone part
  of this app for a smaller win). What was actually built this pass: (1) ECAPA-TDNN model
  dynamically INT8-quantized on its Gemm/MatMul layers only (attention pooling + final FC) via
  ONNX Runtime's quantize_dynamic - Conv layers left float32 since dynamic quantization of Conv
  produces ConvInteger ops this ORT build's CPU EP doesn't implement (confirmed by a real failure,
  not assumed), and static/QDQ quantization of the Conv backbone would need real calibration audio
  to be safe for a model gating actual authentication, which wasn't pursued this pass. 24.86MB ->
  21.94MB (11.8% smaller), 0.999+ cosine similarity vs. float32 on synthetic inputs - safe,
  verified pre-ship, but still needs a real on-device enroll/verify check against actual speech.
  (2) Fixed a real correctness gap in VoiceCapture.kt's trimSilenceVad: previously, if Silero VAD
  found zero speech in a take (mic hiccup, late start, dead air), it silently fell back to
  returning the full untrimmed (possibly all-silence) buffer instead of failing - meaning a bad
  take could get permanently embedded into the enrollment reference pool. Now returns null (a real
  capture failure) when no speech is found or the trimmed region is under 300ms. (3) Enrollment in
  SecurityScreen.kt now retries an individual bad take up to 2 extra times instead of aborting the
  whole 15-take (5 style x 3 take) sequence on one bad take. (4) New VoiceIdConfidenceLog - logs
  score/pass-fail from the two real production verify() call sites (ScifiAccessibilityService's
  command gate, MainActivity's confirmation voice-match) but deliberately not the Security screen's
  manual "Test voice match" button, so real-world drift isn't biased by test conditions; a UI nudge
  now shows in Settings > Voice ID when 3+ of the last 5 real attempts failed. Builds clean,
  installs with no crash (confirmed via logcat). Also added, same pass: a NETWORK section at the
  top of Settings showing connected SSID/signal/link-speed/IP, plus a fingerprint-gated "Reveal
  password" using WifiManager.getConfiguredNetworks()/preSharedKey - AOSP carries an explicit
  exception letting Device Owner apps read this where ordinary apps get an empty/redacted result
  since Android 10; this app is Device Owner, so it should work, but this specific OEM-build
  behavior (Android 16 / One UI) has NOT been confirmed live yet. **Not yet confirmed live**:
  quantized-model real accuracy (only synthetic-input verified), the bad-take rejection/retry
  actually triggering on a genuine bad take, the drift nudge actually appearing after real repeated
  failures, and the WiFi password reveal actually working on this specific device/OS build.

- (2026-07-28) Link to Phone built - phone-to-phone remote control mirroring the already-working
  Link to Laptop feature (token-keyed WebSocket relay through the same Cloud Run backend). Backend:
  new PhoneSession/PHONE_SESSIONS dict + /phone/ws/agent/{token} + /phone/ws/controller/{token}
  routes (main.py), fully isolated from LaptopSession/LAPTOP_SESSIONS - zero shared state, no
  changes to the existing laptop routes. Deployed (revision elene-backend-00029-dxz for the
  routes, elene-backend-00030-vbc for a separate small prompt fix done in the same window - see
  below). Android agent role (the phone being controlled): reuses ScreenPerceptionService's
  existing loop-mode capture completely unchanged except for one addition - a new "linkphone"
  capture mode purely for accurate foreground-notification text ("A linked device can see and
  control this phone" instead of the misleading "Elene is looking at your screen"). Orchestration
  (WebSocket connection, frame-request timer, command dispatch) lives in
  ScifiAccessibilityService.kt, consistent with this codebase's established pattern that
  ScifiAccessibilityService owns orchestration while ScreenPerceptionService stays a "dumb"
  capture box - reused tapAt/swipeCoords/typeText/goBack/goHome/openRecents directly, all of
  which already existed from the screen-perception game-loop feature. Mandatory disclosure screen
  (PhoneLinkAgentScreen) gates everything - no pairing token/QR is reachable until acknowledged;
  Accessibility Service and MediaProjection consent are both real, unbypassed system-level grants;
  "Log out" in Settings > "Linked device access" disconnects and regenerates the token so an old
  shared QR/code can't silently reconnect later. Controller role (PhoneControlScreen) mirrors
  LaptopControlScreen's LiveControl but with direct touch-to-touch mapping (tap->tap, drag->swipe)
  instead of faking a desktop cursor - genuinely simpler than the laptop case. QR generation used
  zxing-core's QRCodeWriter, already a transitive dependency (only the scanning side, via the
  journeyapps wrapper, was used before) - no new dependency needed. Builds clean, installs with no
  crash (confirmed via logcat - clean process restart, no FATAL/AndroidRuntime exceptions).
  **Not yet confirmed live**: the actual two-device pairing/streaming/command-dispatch flow -
  this needs a second real Android device (or emulator) to test end to end, which wasn't available
  this pass. Also not yet manually verified on this device: the disclosure screen's real
  unskippability, the Accessibility Service deep-link, and the QR/token actually rendering
  correctly - only confirmed via code review and a clean install, not a real walkthrough.

- (2026-07-28) Small backend prompt fix: Elene had no instruction for how to answer a general
  capability question like "can you update yourself?" - the propose_update prompt block only
  covered specific change requests ("add X") and her own proactive suggestions, not the meta
  question about whether the capability exists at all. Confirmed via the user reporting she
  answered with a flat "no" (not yet independently verified against a raw transcript, but the gap
  in the prompt is real and visible by inspection). Added an explicit instruction: answer
  honestly that she can't autonomously write/build/deploy code, but always mention in the same
  breath that she can queue a specific proposed change for fingerprint approval if asked. Small,
  independent fix, deployed same session as the Link to Phone backend routes (revision
  elene-backend-00030-vbc). **Not yet confirmed live**: whether she now actually gives the fuller
  answer when asked the same capability question again.

- (2026-07-28) Two real bugs found via the user's live testing of the same-day work above, both
  fixed and confirmed to at least build/install clean (behavior itself not yet re-verified live):
  (1) WiFi "Reveal password" was showing something hash-like, not the real password. Root-caused
  with real evidence, not guessed - `adb shell dumpsys wifi` on this device showed
  `PSK/SAE: *` for the actually-connected network, i.e. even Android's own privileged system dump
  redacts it. This means the "Device Owner apps keep access to preSharedKey" assumption from
  earlier the same day was incomplete - it appears to only hold for networks the app itself
  added, not ones configured through the normal system Wi-Fi UI (which is virtually always the
  case for a real daily-driver phone). `WifiConfiguration.preSharedKey` can also legitimately come
  back as an unquoted 64-character hex string - the PBKDF2-derived PSK, a real value but not the
  human-typed password and not reversible into one. WifiStatus.kt now detects both cases (the "*"
  placeholder and the hex-PSK shape) and reports honestly ("Android hides this..." /
  "only a derived key is stored...") instead of displaying wrong data as if correct.
  (2) Bigger one: Link to Phone's agent role ("Linked device access") was built directly into this
  same app's own Settings screen - meaning the user's OWN daily-driver phone could be put into
  "controllable by whoever has the pairing code" mode. That's backwards from what was actually
  wanted: the agent role is meant to ship as a separate, minimal APK sent to *other* people's
  phones, so the user's primary phone only ever holds the controller role. Immediate fix: pulled
  the "Linked device access" row out of SettingsScreen.kt entirely - there's now no UI path to
  reach the agent role from the main app at all. The underlying PhoneLinkAgentScreen/
  ScifiAccessibilityService agent-role code was left in place (not deleted) since it's needed for
  the real fix - a genuinely separate minimal installable app - which hasn't been scoped/built yet
  as of this entry. Both fixes build clean, install with no crash (confirmed via logcat). **Not
  yet confirmed live**: that the honest Wi-Fi-password-unavailable messages actually display
  correctly, and (the bigger one) the actual separate agent-only APK doesn't exist yet at all.

- (2026-07-28) Link to Phone agent role extracted into a genuinely separate, minimal app -
  new `:agent` Gradle module (`agent/`), own `build.gradle.kts` with `applicationId =
  "com.example.phonelinkagent"`, fully independent from `com.example.scifilauncher`. Real,
  confirmed reason this had to be a separate module and not a product flavor of the same `app`
  module: `app/build.gradle.kts` pulls in `onnxruntime-android` (ECAPA-TDNN Voice ID) and a
  native CMake build (RNNoise/Speex) that aren't easily excludable per-flavor since the native
  build is module-wide - a flavor would have shipped all of that to a stranger's phone anyway.
  Confirmed the size difference is real, not assumed: agent-debug.apk is 9.58MB vs.
  app-debug.apk's 69.5MB (~7x smaller) after both built clean.
  Copied as-is (self-contained, no SciFiLauncher-specific dependencies): PhoneLinkAgentClient.kt,
  QrCodeGenerator.kt, PhoneLinkAgentScreen.kt (package renamed, disclosure text updated to
  reference this app's own Log Out button instead of "Settings > Linked device access" which
  doesn't exist in this minimal app). New, extracted-and-trimmed: PhoneLinkAccessibilityService.kt
  (just tapAt/swipeCoords/typeText/goBack/goHome/openRecents plus the phone-link session
  orchestration - none of ScifiAccessibilityService's ~1400 lines of unrelated Elene/WhatsApp/
  Sequence-Mode logic came along), ScreenCaptureService.kt (continuous-capture-only, no
  single-shot/game-loop modes since this app never needs those), a new minimal MainActivity.kt.
  New manifest deliberately has no `android.intent.category.HOME` and no Device Admin receiver -
  confirmed live via `adb shell dumpsys package com.example.phonelinkagent`, which showed only
  `category.LAUNCHER` in the Activity Resolver Table and no DEVICE_ADMIN entry anywhere in the
  dump. Corresponding cleanup in the main `app` module: removed the whole agent-role block from
  ScifiAccessibilityService.kt, reverted the "linkphone" mode addition to
  ScreenPerceptionService.kt back to its original state, removed the token/disclosure/screen-state
  wiring from MainActivity.kt, removed the now-unused `onOpenPhoneLinkAgent` param from
  SettingsScreen.kt, deleted the two now-relocated files from the app module's source set. Real
  bug caught and fixed along the way: MainActivity.kt's `onResume()` override was a literal no-op
  that didn't actually update any state - `LaunchedEffect(Unit)` only runs once on first
  composition, not on every resume, so the original draft would never have picked up the user
  turning on Accessibility Service in system Settings and coming back. Fixed by hoisting
  `accessibilityOnState` to a class-level `mutableStateOf` field `onResume()` can actually write
  to. Both `:agent:assembleDebug` and `:app:assembleDebug` build clean; both install with no
  crash (confirmed via logcat) alongside each other on this device (different applicationIds).
  Separately this same session: fixed the WiFi "Reveal password" feature, which was showing a
  hash-like value instead of the real password - root-caused with real evidence
  (`adb shell dumpsys wifi` showed `PSK/SAE: *` even in Android's own privileged system dump for
  a network added through the normal system Wi-Fi UI), revealing the earlier "Device Owner apps
  keep preSharedKey access" assumption only holds for networks the app itself added. WifiStatus.kt
  now detects the "*" redaction placeholder and the unquoted-64-hex-char derived-PSK shape and
  reports honestly instead of displaying wrong data as if it were the real password.
  **Not yet confirmed live**: the actual two-device pairing/streaming/command-dispatch flow for
  the extracted agent app (needs a second real Android device, not available this pass), the
  disclosure screen's real content on-device (only confirmed via code + manifest facts, not a
  visual walkthrough), and the honest Wi-Fi-password-unavailable messages actually displaying
  correctly in the Settings UI.

- (2026-07-30/31) GCP billing incident, unrelated to app code but directly blocked all backend-
  dependent live testing: `elene-backend` returned HTTP 500/503 to every request. Root-caused via
  real Cloud Logging evidence, not guessed - a self-built `billing-killswitch` Cloud Function
  (Pub/Sub-triggered off a Billing Budget alert) had detached billing from the `cedal-fd4a2`
  project entirely after this month's cumulative cost crossed a £10 budget cap
  (`Cost 10.04 >= budget 10.0 - disabling billing`), confirmed via `gcloud billing projects
  describe` showing `billingEnabled: false`. Re-linked billing once
  (`gcloud billing projects link`) and it worked immediately - but re-tripped within hours,
  because the £10 cap is checked against *cumulative cost for the calendar month*, not payment
  status; paying an invoice does not reset it, only the next billing period (the 1st of the
  month) does. Root-caused a second real finding while investigating: `elene-backend`'s Cloud Run
  service had `autoscaling.knative.dev/minScale: '1'`, meaning it billed 24/7 for a warm instance
  regardless of use - this was very likely the single biggest driver of the monthly cost. Fixed
  with `gcloud run services update elene-backend --min-instances=0` (confirmed deployed, revision
  `elene-backend-00031-xj2`); Cloud Run natively supports scale-to-zero, so no custom app-side
  toggle was needed for this part. Audited every other Cloud Run service in the same project (23
  others, e.g. `android-builder`, `gui-runner`, `cedal-server`, various `*assistant` services) -
  confirmed none else had `minScale` set, and confirmed the three other billing-linked projects
  (`cedal-ai`, `work-force-493823`, `xenos-1230e`) have never enabled Cloud Run or Cloud SQL at
  all, so no equivalent risk there. Separately found a real second always-billing resource: a
  Cloud SQL instance `cedal-db` (`db-f1-micro`, `activationPolicy: ALWAYS`) backing `cedal-server`
  (confirmed via the `run.googleapis.com/cloudsql-instances` annotation - it's the *only* service
  connected to that database), which the user confirmed is their in-development chat app and
  chose to leave on `ALWAYS` (~£5/month is acceptable). Learned and worth remembering: unlike
  Cloud Run, current-generation Cloud SQL has no scale-to-zero/auto-wake mechanism at all - gcloud
  only accepts `always`/`never` for `--activation-policy` (the older on-demand auto-start/stop
  behavior doesn't exist for this instance), so the only real lever for Cloud SQL idle cost is a
  manual or scheduled stop, not an automatic one. `cedal-db` was left `SUSPENDED` after the
  incident and did not clear the moment billing was restored - Google's own backend needs to
  reconcile a suspension against a newly-active billing account, which isn't instant (a direct
  `patch` attempt correctly failed with `409: not in an appropriate state`, confirming this isn't
  fixable by retrying the same call, just by waiting). Final resolution: raised the "Elene backend
  budget" from £10 to £15 (`gcloud billing budgets update`, budget ID
  `d2a04f63-027c-430f-a214-3be7ad93a329`) since the month's spend was already past the old cap and
  would have re-tripped the killswitch again on its next check regardless of re-linking, then
  re-linked billing a second time - confirmed actually healthy this time via a real HTTP request to
  `elene-backend`'s root path (a 404, which is correct/expected since no root route is defined; a
  ~9.5s response time was the normal `minScale=0` cold-start, not an error). **Not yet reconfirmed
  at time of writing**: whether `cedal-db` has cleared `SUSPENDED` on its own yet.
- (2026-07-31) Live-tested the capability-question fix from 2026-07-28 (the "can you update
  yourself?" prompt instruction) for real, after the billing incident above was resolved: asked
  Elene directly, and the user confirmed she now answers that she can't update or change her own
  code, but can queue proposed changes (for fingerprint approval) - the fuller answer the prompt
  fix was meant to produce, not the old flat "no." **Confirmed working**, first real item cleared
  off the standing "not yet confirmed live" backlog.

- (2026-07-31) Live-tested the WiFi status panel + "Reveal password" honesty fix from 2026-07-28:
  user confirmed on-device the NETWORK section shows real signal (-75dBm) and real link speed
  (194Mbps), and tapping "Reveal password" now correctly shows the honest "Android hides this -
  only readable for a network added by the app itself, not ones set up through system WiFi
  settings" message instead of the old wrong hash-like value. **Confirmed working.**

- (2026-07-31) Real, unrelated-to-app-code infrastructure incident found and resolved while trying
  to install a small UI fix (tap-to-fullscreen on Intruder Attempts photos, see below): a plain
  `adb install -r` of a freshly-built APK failed with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
  Root-caused via real cert comparison (`keytool`/`apksigner verify --print-certs`), not guessed:
  the dev machine's entire `~/.android` profile (debug.keystore, adbkey, avd, everything) had been
  created fresh on 2026-07-30 - a full day after the currently-installed app (last updated
  2026-07-28) was built and signed with a debug key that no longer exists anywhere on this
  machine. Confirmed this blocks only *new* code changes going forward, not anything already
  tested earlier this session (that was all against the pre-existing 2026-07-28 install).
  Investigated the real fix cost before acting: Device Owner provisioning
  (`dpm set-device-owner`) has a hard Android platform restriction - it refuses outright if any
  account already exists on the device - and this phone had 6 real Google accounts plus WhatsApp/
  Instagram/GitHub/Meet already configured (confirmed via `dumpsys account`), meaning the fix
  wasn't a simple uninstall/reinstall but a full factory reset. Flagged this plainly (including the
  real Factory Reset Protection risk of getting locked out post-reset) before doing anything, and
  the user made an informed call to proceed since their accounts/other apps live primarily on a
  laptop or are self-authored. One real app (Harmix, `com.harmix.player.editmusic`) couldn't be
  easily re-acquired, so its base + 3 split APKs were pulled via `adb pull` and backed up locally
  *before* the reset, then reinstalled via `adb install-multiple` afterward - confirmed successful.
  After the reset: confirmed no accounts existed yet (`dumpsys account` empty), installed the fresh
  APK, then successfully re-ran `adb shell dpm set-device-owner
  com.example.scifilauncher/.SequenceDeviceAdminReceiver` - confirmed real (not test-only) Device
  Owner status via `dumpsys device_policy` showing `testOnlyAdmin=false`. **Real, expected
  consequence, not yet re-confirmed**: this reset wiped all of this app's own on-device state too
  (Voice ID enrollment, Sequence Mode config, location history, the intruder capture entries just
  confirmed working above, remembered facts) - none of it is backed up anywhere, so it all needs
  rebuilding from scratch, same as a genuinely new install.
- (2026-07-31) Added tap-to-fullscreen viewing for Intruder Attempts photos in `StorageScreen.kt`
  (a 56dp thumbnail was too small to actually see anything useful) - a `Dialog` with the photo
  scaled via `ContentScale.Fit` on a black background, dismissed by tapping again. Split the row's
  click handling so the photo and the reason/timestamp text have independent tap targets (photo ->
  fullscreen, text -> expand for the maps link), instead of one handler doing both. Builds clean.
  **Not yet confirmed live** - blocked on the signing-key/factory-reset saga above at the time this
  was written; needs a real on-device tap-through once the phone is back to a normal working state.
- (2026-07-31) GCP billing killswitch kept re-tripping through this whole session even after
  raising its budget - root-caused for real by downloading and reading the actual Cloud Function
  source (`gcloud storage cp` on its `gcf-v2-sources-...` bucket object, not guessed): the code
  itself is correct and reads `budgetAmount` dynamically from each incoming Pub/Sub alert payload,
  no hardcoded value - the repeated `budget=10.0` readings were because Google's own Budget-alert
  pipeline hadn't yet propagated the raised amount into new alert payloads, a real delay on
  Google's side, not a bug in this project's code. Also found there were two separate £10 budgets
  on the account both wired to the same Pub/Sub topic (only one had been raised the first time,
  which is why it re-tripped again immediately) - both now raised to £15. Set up a background
  auto-relink watcher (polls the backend, checks `billingEnabled`, re-links automatically) as a
  stopgap while the propagation catches up, per the user's explicit choice to keep the safety net
  fully live rather than pause it. Confirmed working live - the watcher caught and fixed at least
  three real trips autonomously during this same session without the user having to notice/report
  each one.

- (2026-07-31) Anti-tampering/RASP hardening pass built per the plan in
  `planner/not_started/not_started.md`, blocked from on-device testing partway through by a
  separate, real Samsung Auto Blocker issue (see below) - a real, current example of exactly the
  "build then confirm before moving on" discipline this project's standing rule exists for, since
  none of this can be marked done from a clean build alone. Four pieces, all compile clean
  (including a real native/NDK build):
  1. **APK integrity self-check** (`AppIntegrityCheck.kt`) - compares the running APK's real
     signing certificate against one baked in at build time. Computed *dynamically* from the
     actual debug keystore via a small Gradle-script function (`debugCertSha256()` in
     `app/build.gradle.kts`), not hardcoded - specifically because this exact debug key was found
     to be regenerable/machine-local earlier this same session (see the signing-mismatch incident
     entry above), so a hardcoded value would have gone stale immediately. Honestly documented
     limitation: this only protects against *someone else's* modified copy, not a stolen signing
     key, and debug-signed apps are inherently less stable identities than a real release key
     (this project has never had a release keystore).
  2. **Root/Magisk/SELinux checks** (`RootDetection.kt`) - common su binary paths, known Magisk
     package IDs, real `/sys/fs/selinux/enforce` (falling back to `getenforce`) - not treated as
     a guarantee, since Magisk's own Zygisk/DenyList hiding features exist to spoof exactly this.
  3. **Exported-component review** - checked every `android:exported="true"` component in the
     manifest, not assumed: most already carry the *strongest* available protection
     (`BIND_INPUT_METHOD`/`BIND_DEVICE_ADMIN`/`BIND_VPN_SERVICE`/`BIND_SCREENING_SERVICE` are
     OS-only system permissions, stronger than a custom signature permission), `MainActivity` is
     deliberately open since it's the launcher itself, and `CedalSharedSystemReceiver` already had
     a real signature-level manifest permission. Found and fixed the one genuine gap: that
     receiver trusted a self-reported `from_package` intent extra against an allowlist without
     ever re-verifying the claimed sender's actual installed signature - fixed by adding
     `CedalSharedSystem.isVerifiedSibling()`, re-checked in `onReceive` before trusting anything.
     Honestly noted in the code: `BroadcastReceiver` has no direct calling-UID API the way a
     Binder call does, so re-verifying the claimed sender's real signature is the strongest
     defense-in-depth actually available here, not a true calling-identity check.
  4. **Layered native Frida detection** (`frida_detect_jni.cpp`, new `libfridadetect.so`, NDK/
     CMake) - five independent signals: a `/proc/self/maps` scan for a loaded Frida agent/
     gadget, a probe of Frida's default port 27042, a `/proc/self/task` thread-name scan, a
     `/proc/[pid]/cmdline` scan for a running Frida server process, and a coarse CPU-timing
     heuristic (deliberately treated as the weakest signal, since real device variance can trip
     it alone). Detection strings are XOR-obfuscated at compile time via a constexpr helper so a
     static `strings` pass on the shipped `.so` doesn't hand over the exact signatures. Reports a
     signal *count* rather than one hard yes/no, matching the plan's own "no single check works
     alone" reasoning. Deliberately native/JNI, not Kotlin, since Frida hooks the Java/ART layer
     far more easily than raw `/proc` reads and sockets.
  All four wired into a new **Security > Integrity & Tamper Detection** panel section -
  deliberately passive/informational (status rows + an explanatory dialog), matching this app's
  existing transparency pattern (the Capabilities screen) rather than an aggressive auto-block on
  detection, given the real false-positive risk especially from the timing heuristic. **Not yet
  confirmed live** - blocked mid-session by an unrelated real issue: Samsung's Auto Blocker
  (re-armed after the same-day factory reset) greyed out both USB and wireless debugging with no
  discoverable toggle to disable it (checked Quick Settings, Security and privacy, its Security
  tab, and a pending-software-update check - none surfaced it), so nothing built this pass has
  been installed or exercised on the real device yet.

  **Update, same day**: the Auto Blocker issue resolved via a Samsung "Reset settings" (not a
  full factory reset) - adb access came back, and this pass was installed and exercised for
  real. All four results confirmed correct: App integrity showed "Verified", Root/Magisk showed
  "Not detected", Instrumentation (Frida) showed "Not detected" - and confirmed via logcat this
  is a genuine negative, not a silent failure (`libfridadetect.so` shows a real `dlopen ... ok`
  load), and SELinux showed "Unknown" - also confirmed correct, not a bug: the code deliberately
  reports unknown rather than guessing when `/sys/fs/selinux/enforce` isn't readable and
  `getenforce` isn't runnable from an app process, itself expected on a hardened Samsung/Knox
  build tighter than stock AOSP. **Confirmed working**, a real on-device result, not assumed
  from a clean build.
- (2026-07-31) Real Samsung Auto Blocker issue, found live: after the same-day factory reset,
  Auto Blocker came back enabled and now blocks both USB debugging and wireless debugging in
  Developer Options (both show greyed out with "Blocked by Auto Blocker"). Its own toggle is not
  discoverable anywhere tried so far - not in Quick Settings, not in Security and privacy's main
  list or its Security tab, and a pending software/Galaxy Store update check didn't surface it
  either. Root-caused why a code-based workaround isn't viable either: this app (Device Owner)
  has no already-installed feature for forcibly enabling ADB via
  `DevicePolicyManager.setGlobalSetting`, and even if one were built now, deploying it requires
  `adb install`, which is exactly what's blocked - a genuine chicken-and-egg dead end for any
  code-side fix while this specific restriction holds. Also flagged, not yet tested: this exact
  API already has a known failure mode in this codebase for a similar case - `setGlobalSetting`
  for `AIRPLANE_MODE_ON` was confirmed by direct testing to only flip the raw setting value
  without the OS actually enforcing it, since real enforcement needed a protected broadcast no
  non-system app can send - so even if adb access were restored some other way and this were
  tried, it's a real gamble, not a confirmed fix, and shouldn't be presented as one without
  testing. **Currently unresolved** - blocking all on-device testing/installation until the user
  finds a way to disable Auto Blocker or otherwise restores adb access.

- (2026-07-31) Weather feature built and confirmed live, after a real chain of on-device
  debugging - a good example of the standing "verify before moving on" rule catching real gaps a
  clean build never would have. Built `WeatherClient.kt` (Open-Meteo, free/no API key, matching
  this project's existing preference for no-signup services), wired `current_weather` into both
  chat context call sites (ScifiAccessibilityService and MainActivity's home-bubble path) plus
  the backend prompt, with an honest "say you don't have it" instruction if the field's missing
  rather than let the model invent a forecast. First real gap found live: the Dashboard already
  had a **dead hardcoded weather placeholder** (`"--°C CLEAR"`, never once updated) that the user
  pointed at directly on-device - wired it to real WeatherClient data too. Second real gap, found
  via actual `dumpsys location` evidence, not guessed: on this freshly-reset phone, no location
  fix existed *system-wide*, for any provider, even Google's own fused provider - the existing
  `captureLastLocation()` (a passive `getLastKnownLocation()` read, already used elsewhere in the
  app for Sequence Mode/Intruder Attempts) had nothing to read, because nothing had ever actually
  requested a fix since the reset. Built a real fix: `requestFreshLocation()` /
  `requestAndCacheFreshLocation()` in SequenceMode.kt, an active `requestLocationUpdates()` call
  across every enabled provider with a timeout, used as a fallback wherever the passive cache is
  empty. First attempt at a 15s timeout genuinely came back with zero location callbacks
  (confirmed via `dumpsys location` showing the real registration/request/timeout sequence, not
  assumed) - a real GPS cold-start limitation (no cached almanac/assistance data right after a
  reset), not a code bug, so the honest fix was raising the timeout to 45s and having the user
  try near a window, not writing more code to chase a physics problem. **Confirmed working** on
  the next attempt - a real fix landed (same real Edinburgh-area coordinates seen earlier in
  Intruder Attempts, confirming it's genuine), and the Dashboard weather display updated with
  real data. This location-fix improvement isn't weather-specific - Sequence Mode, Location
  History, and Intruder Attempts all read from the same cache and now benefit from it too on any
  future fresh install.
- (2026-07-31) Sequence Mode auto-arm triggers built, in response to the user directly asking
  "how does the app know it got stolen?" - a good question that surfaced a real gap: Sequence
  Mode had **no automatic trigger at all** since the old per-app-lock's "3 wrong PIN attempts"
  auto-arm was removed on 2026-07-26 and never replaced; arming was 100% manual (the Security
  screen's "Trigger lockdown now" button) this whole time. User's first idea was heart-rate-based
  detection (elevated pulse = maybe running from a theft) - correctly declined after explaining
  why it doesn't work for this device/threat model: the Galaxy A54 has no heart-rate sensor at
  all (that's a Galaxy Watch/S-Ultra camera+flash feature needing a finger on the camera, not
  something a running thief would do), and even with one, it would read the *owner's* pulse, not
  a thief's, unless the thief were also wearing the owner's wearable - which defeats a "grab the
  phone and run" scenario. Built two real triggers instead: (1) **N-failed-fingerprint auto-arm**
  (`shouldAutoArmFromFailedAttempts()` in SequenceMode.kt, wired into
  `BiometricAuthActivity.recordCapture()`) - 3 failed scans within 10 minutes arms immediately,
  reusing IntruderCaptureLog's own timestamps rather than a separate counter. (2)
  **Accelerometer-based motion-spike detection** (`MotionTheftDetector.kt`, registered/
  unregistered on ScifiAccessibilityService's lifecycle) - a real signal from the phone's own
  `TYPE_LINEAR_ACCELERATION` sensor (a sustained running-like peak cadence over 5s), explicitly
  never arms directly given real false-positive risk (genuine exercise, a bumpy car ride, quickly
  picking the phone up all look similar) - instead starts a confirm-or-arm flow matching what the
  user described: Elene speaks "are you running, or is everything OK? Confirm your fingerprint
  within 10 minutes" (new `ScifiAccessibilityService.speakElene()`, deliberately not reusing the
  existing private `speakOut()` since that's coupled to the conversational bubble's own listening
  state machine), a notification with a tap-to-confirm action
  (`BiometricAuthActivity`'s existing flow, extended with a `REASON_MOTION_CONFIRM` extra), and a
  `MotionConfirmTimeoutWorker` (WorkManager, matching the existing `SequenceWipeWorker` pattern)
  that only actually arms Sequence Mode if the 10-minute window elapses unconfirmed. Both
  triggers call the same `enterSequenceMode()` every other arm path already uses - no separate/
  parallel arming logic. Security screen's Sequence Mode section updated to disclose both
  triggers and their thresholds, plus a new "Awaiting confirmation (motion detected)" status
  state. Builds clean, installs with no crash (confirmed via logcat). **Not yet confirmed live**:
  blocked on Accessibility Service needing manual re-enable after the same-day factory reset
  (now done by the user) - the actual motion-spike detection and failed-attempt auto-arm haven't
  been triggered for real yet, only confirmed to build/install/run without crashing.

  **Update, same day - both triggers live-tested for real, with real bugs found and fixed along
  the way, not just confirmed on first try:**
  - Failed-fingerprint auto-arm: tested by failing 3 real fingerprint scans on the Security
    screen prompt - genuinely auto-armed. Along the way, found via real evidence
    (`SequenceAlertWorker` logcat showing `SUCCESS`, not the expected first-12h-delay behavior)
    that the *existing*, pre-dating-this-session `SequenceAlertWorker` periodic job's first run
    doesn't wait the full interval the way the code's own framing implied - it fired almost
    immediately after arming and reached the first contact in the list (the user's brother)
    before the user's own quick disarm cancelled it partway through the contact loop. A real
    WhatsApp/SMS alert did go out to one real contact during testing - user confirmed already
    telling that contact it was a test. Not a bug introduced this session, but a real, previously
    unknown behavior surfaced by actually exercising the new auto-arm trigger for the first time.
    Led to a deliberate, reasoned interval change (12h -> 1h, not the initially-requested 5min) -
    weighed WhatsApp's own spam/abuse detection risk of losing the channel entirely mid-emergency
    against wanting frequent updates, and a real safety consideration for the actual kidnapping/
    forceful-theft scenario the user described: frequent visible WhatsApp activity risks tipping
    off someone holding the phone, which real hostage/abduction-response guidance generally
    advises against - landed on 1h as a sustainable middle ground, with SMS (already sent
    alongside WhatsApp every cycle) as the quieter, more reliable fallback channel.
  - Motion-spike detection: first real attempt (a vigorous hand shake) produced no trigger at
    all. Root-caused with real evidence, not re-guessed - `dumpsys sensorservice`'s raw linear-
    acceleration samples showed the actual shake only reached ~4 m/s^2 peak, far under the
    original 11 m/s^2 threshold (picked blind, never validated against a real human shake).
    Lowered to 2.5 m/s^2 / 4 peaks (from 11 m/s^2 / 6 peaks) based on that real reading, with the
    same dumpsys data's quiet-baseline samples (~0.02-0.15 m/s^2) confirming a real margin still
    exists against false triggers from ordinary handling. Retested after the fix - triggered
    correctly, Elene's spoken "are you running?" prompt was genuinely heard, and the alert
    resolved (fingerprint-confirmed) without arming Sequence Mode. **Both triggers confirmed
    working for real**, not from a clean build - the real values needed real on-device
    measurement to get right, in both directions (the alert interval needed slowing down, the
    motion threshold needed speeding up/lowering).

- (2026-08-01) Real, pre-existing architectural gap found while re-testing Voice ID after
  re-enrollment (the enrollment itself confirmed working via real embedding data in
  voice_id_prefs.xml for all 5 styles - not the bug). Two real voice commands ("open WhatsApp",
  "open Settings") both executed correctly, but neither left any trace in
  `VoiceIdConfidenceLog` or the `EleneVoiceID` log tag at all - meaning
  `voiceIdAllowsCommand()`'s gate silently fail-opened both times without ever completing a
  real verification. Root-caused via exact logcat timestamps, not guessed:
  `ScifiAccessibilityService.handleSpokenText()` calls `recordVoiceSample()` (a fresh
  `AudioRecord` capture, separate from the `SpeechRecognizer` session that heard the command)
  only *after* `EleneApiClient.sendText()`'s full backend round-trip completes - confirmed via
  the ~4.8s gap between "Response received" and "Command executed" in the logs, matching
  `recordVoiceSample`'s own duration. By the time that capture starts, the user has already
  finished speaking and gone quiet for several seconds, so Silero VAD correctly finds no real
  speech in the recording and returns null (per the already-documented 2026-07-27 VAD-
  correctness fix: a take with no detected speech fails the capture rather than embedding
  silence) - which makes the gate fail-open by design every time, for a structural reason, not a
  regression. This means the general-command Voice ID gate has likely never been effectively
  verifying anyone in practice since that VAD fix landed, only ever hitting its intentional
  fail-open path. Ruled out concurrent-capture-during-SpeechRecognizer as the fix (recording a
  second raw stream *while* SpeechRecognizer is still listening) since that's the exact "replace
  SpeechRecognizer with a custom capture pipeline" risk the project already explicitly declined
  during the 2026-07-27 audio-pipeline work, for the same regression-risk-to-the-most-bug-prone-
  part-of-the-app reason. Real, lower-risk fix instead: run the verification recording
  *concurrently* with the backend network call (via coroutine `async`) rather than sequentially
  after it - `SpeechRecognizer` has already fully closed by the time this runs either way, so
  this doesn't touch the declined concurrent-capture territory; it just removes several seconds
  of unnecessary dead air the capture was waiting through, moving the recording window as close
  to "just after the user stopped talking" as this architecture allows without a bigger rebuild.
  Not a complete fix - a user who goes fully silent after a one-shot command will still
  sometimes get no real signal to verify against, since there's genuinely no live audio hook
  into `SpeechRecognizer` itself - but it's a real, honest improvement within the constraints
  already established, not a rebuild.

  **Confirmed live same day**: restructured `handleSpokenText()` to run
  `voiceIdAllowsCommand()` via `async` concurrently with `EleneApiClient.sendText()` instead of
  after it (new `kotlinx.coroutines.async` import), cancelling the recording early on the
  pure-conversation/no-response paths rather than letting it run unnecessarily. Retested with
  two real commands - the first turn (a garbled/likely-ambient pickup, STT heard "I couldn't do
  that I") got a real logged score for the first time ever (`VoiceIdConfidenceLog`: style=SHORT,
  score=0.109, passed=false) - confirmed via logcat that its commands genuinely never executed
  (no `Command verb=` line follows), meaning the low-confidence result actually blocked
  execution, not just logged it. The very next turn ("open WhatsApp") still produced no logged
  entry - fail-opened again, since that utterance was too short/fast for the concurrent window
  to catch anything. **Confirmed as a real, partial improvement**: went from a 0%-real-capture-
  rate gate (100% silent fail-open, effectively decorative) to one that genuinely captures and
  correctly blocks low-confidence matches at least some of the time - not airtight for every
  short command, which matches the honest limitation already documented above, not a new
  regression.

- (2026-08-02) Updates screen Stage 2 built - a real, working backend-only self-update pipeline,
  per the plan logged in `planner/not_started/not_started.md` (2026-08-01 entry) after the user's
  explicit call to keep fingerprint verification rather than remove it. Real chain, each piece
  built and connected in order:
  1. `backend/elene/main.py` gained `/elene/submit_update_request` - called only after a real
     on-device fingerprint approval (never anything else), creates a real GitHub issue labeled
     `approved-backend-update` on the repo. Uses the existing `GITHUB_PAT` secret, newly bound to
     the `elene-backend` Cloud Run service via `--set-secrets` (properly, via Secret Manager
     reference - not as a plaintext env var like the service's other existing secrets already
     were, a small security improvement made just for this new addition).
  2. `MainActivity.kt`'s two `onApprove` hooks (the immediate-prompt path and the Updates-screen
     review path) both now call a new `EleneApiClient.submitUpdateRequest()` right after the
     existing `UpdateProposalLog.updateStatus(..., APPROVED)` call - best-effort, the on-device
     approval already happened regardless of whether this bridge call succeeds.
  3. A real scheduled cloud agent (routine `trig_01XxHRhbPpqZWmDnmVBSCqSe`, hourly) polls for
     open issues with that label, and for each: implements the change in `backend/elene/` only,
     self-tests it against a real local run (not assumed), deploys via `gcloud run deploy`, then
     verifies against the actual live Cloud Run URL before commenting and closing the issue -
     explicitly scoped to never touch app/Kotlin code or any other GCP resource, and to leave an
     issue open with a clear comment (never silently fail or fake success) if anything - scope,
     local test, gcloud availability, or post-deploy verification - doesn't check out.
  Real, honest scope boundary surfaced and agreed before building anything: a cloud agent has no
  access to the physical phone at all, so this only covers backend/Cloud Run changes - app-side
  (APK) self-update would need either a real local Claude Code session or the separately-deferred
  remote-auto-update mechanism (still not started, its own real security-design pass), which the
  user explicitly confirmed as the next thing to tackle after this.
  Real infrastructure problems hit and fixed along the way, not just a clean build: (1) the
  routine's first two creation attempts failed - GitHub wasn't connected to Claude at all
  (fixed by the user installing the Claude GitHub App), then failed again with a repo-access
  error once it was; (2) root cause for the second failure was real and unexpected - the
  repo had actually moved, both a different GitHub username (`Xenos-deathcode` -> `xenos-hackcode`,
  a rename that happened a while ago) and a different repo name
  (`SciFiLauncher` -> `Elene-sifilaucher`) - the local git remote and the backend's hardcoded
  `GITHUB_REPO` constant were both still pointing at the old, stale path this whole session
  without either of us noticing, since local git operations never actually needed to push/fetch
  against it. Fixed both, redeployed. (3) The existing `GITHUB_PAT` secret (created 2026-07-10,
  likely scoped under the old username) genuinely didn't have permission to create issues/labels
  on the real repo (`403: Resource not accessible by personal access token`, confirmed via a
  direct API test, not assumed) - the user generated a fresh classic PAT with `repo` scope, which
  was verified working via a real label-creation call before being wired in.
  **Update (same day, after billing resolved)**: first real end-to-end attempt found and fixed a
  real bug in how this was deployed, not in the pipeline's own design. The user asked Elene to
  propose adding an `/elene/ping` endpoint (STT actually misheard this as "a linking" - a real,
  separate speech-recognition accuracy gap worth knowing about for future voice-driven proposals,
  not something fixed this pass), approved it via fingerprint - but no GitHub issue appeared.
  Root-caused via real Cloud Run logs, not guessed: `submit_update_request` was failing with a
  real `403 Forbidden` from GitHub, even though a direct curl test with the exact same
  `GITHUB_PAT` value succeeded (201) - ruling out the token itself. The actual cause: an earlier,
  wrong assumption that Cloud Run always reads a `:latest`-referenced secret automatically had no
  basis - secret values are only loaded into a container's environment at *startup*, and the
  already-warm instance serving requests had started before the PAT was updated, so it was still
  holding the old, broken token in memory the whole time despite the secret itself being correct.
  Fixed with a forced redeploy (`gcloud run services update --update-secrets`, new revision
  `elene-backend-00038-wt4`) to force a fresh container pickup - confirmed via a direct
  `/elene/submit_update_request` test call immediately after, which correctly created a real
  GitHub issue. **Real, generalizable lesson for this whole project**: updating a Secret Manager
  secret's value is not enough by itself if a Cloud Run service is already warm/serving traffic -
  a fresh deploy (or at minimum confirming a cold start actually happened) is needed before
  trusting a secret rotation actually took effect, contradicting the earlier assumption stated
  when this session first bound `GITHUB_PAT` to the service. **Still not fully confirmed**: the
  user's own real proposal (once redone, ideally with a request STT can transcribe cleanly) still
  needs to flow all the way through - issue created -> picked up by the hourly routine ->
  implemented/tested/deployed/verified -> issue commented and closed. That last, most important
  leg is still unconfirmed.

- (2026-08-07) Anti-theft mode toggle built, in response to the user pointing out the motion-based
  "are you OK?" check-in (built 2026-07-31) had no way to turn it off - it always fires once
  enabled at the OS-sensor level, with no per-user opt-out. Added `KEY_ANTI_THEFT_MODE_ENABLED` to
  `SequenceMode.kt` (default ON, so existing behavior doesn't silently change for anyone who
  already has it on), a "Anti-theft mode" `PanelToggleRow` + info dialog in Security > SEQUENCE
  MODE (`SecurityScreen.kt`), and wired it through `MainActivity.kt`. Turning it OFF does two
  things, not just one: (1) `MotionTheftDetector.onMotionSpikeDetected()` early-returns so no new
  alert starts, and (2) a new `cancelPendingMotionAlert()` immediately resolves and clears any
  alert *already in flight* (cancels the WorkManager countdown, clears the notification) instead
  of leaving the user to wait out up to 10 more minutes of "are you OK?" - the user's own explicit
  ask ("that toggle should also have the power to stop elene from asking if i am ok", not just
  gate future alerts). `MotionConfirmTimeoutWorker` also re-checks the toggle at fire time, so
  toggling off mid-countdown resolves the alert without arming Sequence Mode. Manual lockdown,
  N-failed-fingerprint auto-arm, location tracking, and full wipe are untouched - confirmed by
  inspection, none of those paths read this new pref. Found and fixed an unrelated cosmetic
  artifact while touching these files: six lines across `SecurityScreen.kt`, `MainActivity.kt`,
  and `MotionTheftDetector.kt` had lost their leading indentation from a previous session's edit
  (compiled fine either way, just messy diffs) - re-indented, no logic change. Builds clean
  (`compileDebugKotlin` and `assembleDebug`), installed on the real device with no crash
  (confirmed via logcat - clean launch, `ScifiAccessibilityService` reconnected, bubble visible).
  **Confirmed working 2026-08-07** - user tested live on the real device and confirmed the toggle
  works as intended.

- (2026-08-07) Phoenix Protocol, small version, built - the user picked this off three options
  offered after the anti-theft toggle was confirmed (the other two: a safe check-and-notify-only
  remote auto-update, or retrying the still-unconfirmed Updates-screen self-update end-to-end
  test). Scope was kept deliberately narrow, matching what `planner/not_started.md` had already
  judged "worth building" as distinct from the full deferred Phoenix Protocol: a one-way backup of
  just the intruder-capture photos and location history, uploaded right before Sequence Mode's
  existing ~30-day auto-wipe deletes them for good, gated behind the existing Full-device wipe
  toggle - not a restore flow, not the whole app's data, not any of the unreliable detection
  triggers the full version's own writeup had already flagged as unbuildable on stock Android.
  Real infra work, not just app code: created a new private GCS bucket
  (`gs://cedal-fd4a2-elene-evacuation`, uniform bucket-level access + public access prevention),
  granted Cloud Run's default compute service account `roles/storage.objectAdmin` scoped to just
  that bucket (not project-wide), added the `google-cloud-storage` dependency, and deployed a new
  `/elene/evacuate_backup` endpoint (revision `elene-backend-00039-mzv`). Verified this for real,
  not just trusted `{"ok": true}` from the API: sent a smoke-test request with a real base64 JPEG,
  then independently confirmed via `gcloud storage ls -r` that both a `manifest.json` and the
  actual `42.jpg` object existed in the bucket afterward - the backend genuinely persists data,
  not just returning a success shape. Android side: `EleneApiClient.evacuateBackup()` (given its
  own longer OkHttp timeout, since a batch of intruder photos is a bigger payload than the
  single-image calls elsewhere in that client) and a new `PhoenixEvacuation.kt` that reads
  `IntruderCaptureLog`/`LocationHistory` entries and base64-encodes each photo file straight off
  disk. `SequenceMode.performSequenceWipe()` is now `suspend` and runs the upload first - wrapped
  in `runCatching` so a failed upload can never block the real wipe, which stays the actual safety
  mechanism regardless. Also added a "Test evacuation backup now" row in Security > SEQUENCE MODE
  (same self-contained pattern as the existing "Test voice match" button) specifically so this can
  be verified without ever triggering a real, irreversible 30-day wipe just to test it. Being
  honest about the word "encrypted" from the original ask: this relies on HTTPS in transit plus
  Cloud Storage's default encryption at rest - the same trust model already used everywhere else
  in this backend (no endpoint here does client-side/end-to-end encryption) - not a new
  zero-knowledge scheme invented just for this feature; said so directly in the button's own info
  dialog rather than overclaiming. Builds clean, installed on the real device with no crash
  (confirmed via logcat, clean launch). **Not yet confirmed live**: actually tapping "Test
  evacuation backup now" on the device and watching a real intruder photo/location entry go
  through end-to-end into the UI's own success message - the backend half is independently
  proven, the on-device half (file read -> base64 -> real network round trip -> UI update) still
  needs the user's own hands.

- (2026-08-07) Real bug found via the user's own live test of "Test evacuation backup now" - it
  failed. Root-caused from real logcat, not guessed: a `java.net.SocketTimeoutException` on the
  OkHttp stream while waiting for response headers. The fix earlier the same day only raised
  `callTimeout` (90s) on the evacuation upload's dedicated OkHttp client - `connectTimeout`/
  `readTimeout`/`writeTimeout` were still inherited unchanged from the base client's 10s default,
  and the per-stream `readTimeout` is what actually tripped first. Confirmed why 10s wasn't enough
  even with nothing but location history to upload (46KB of JSON, no real intruder photos existed
  on this device - `intruders/` had already been cleared by an earlier wipe test, leaving only
  stale dangling paths in `intruder_capture_prefs.xml`, which `PhoenixEvacuation` already handles
  by nulling out the photo and continuing): `elene-backend` runs at `minScale=0` and has an
  already-documented ~9.5s cold start elsewhere in this same project (see the 2026-07-31 GCP
  billing entry) - that alone eats nearly the entire old 10s budget before any real request
  processing even starts. Fixed by explicitly setting `connectTimeout`(30s)/`readTimeout`(90s)/
  `writeTimeout`(90s) on the evacuation client, not just `callTimeout`(now 120s). Rebuilt, reinstalled,
  no crash (confirmed via logcat). **Confirmed working 2026-08-08** - user retried "Test
  evacuation backup now" and it succeeded.

- (2026-08-08) "Hey Elene" personalized wake-word matching built, in direct response to the user
  reporting the default STT-text-match wake check missed for their accent. Real design point: the
  default path (`ScifiAccessibilityService.containsWakeWord`) only ever checks Android's
  SpeechRecognizer transcription for the phrase as a substring - if the transcription itself is
  wrong for a given accent, no amount of correctly saying the phrase would ever match. Rather than
  widening the text-match list (a real option, but only a patch on the same underlying dependency),
  built a genuinely STT-independent path: `HeyEleneWakeWord.kt` records 5 reference takes, extracts
  the same Kaldi-style fbank features already used for Voice ID (`SpeakerFbank`'s vendored
  kaldi-native-fbank JNI, reused as-is), and matches a live ~2.5s raw capture against those
  templates via Dynamic Time Warping - a classic, deterministic template-matching technique
  (no model training needed, unlike the openWakeWord path already scoped as blocked in
  `planner/not_started.md`). The live wake-check cycle bypasses SpeechRecognizer entirely once
  enrolled, falling back automatically to the old STT path if nothing's been recorded. Threshold
  is calibrated from the recorded takes' own pairwise DTW distances with a safety margin, not a
  fixed guess - explicitly disclosed in the feature's own info dialog as something that might still
  need a re-record if it's too tight or loose in practice, same honesty pattern as every other
  perceptual threshold in this app. `VoiceCapture.recordVoiceSample` gained a `requestFocus`
  param (default true, so no behavior change for existing Voice ID callers) so the new background
  wake-check cycle can skip grabbing transient audio focus every few seconds - reusing exactly the
  reasoning already documented for why the STT wake-check skips AudioFocus too. Real UX bug caught
  by the user before any device testing even happened: the first version only showed the "say it
  now (2/5)" prompt as a tiny subtitle on a Settings row, easy to miss while also trying to speak -
  replaced with an actual modal dialog (large "Hey Elene" title, live "Take X of 5" counter, a
  Cancel button) that stays up for the whole sequence. Builds clean, installed with no crash
  (confirmed via logcat). **Not yet confirmed live**: recording real takes and testing whether
  "Hey Elene" now actually triggers reliably for this accent over repeated real use, and whether
  the auto-calibrated threshold holds up or needs a re-record.

- (2026-08-08) User asked directly whether the Updates-screen self-update pipeline (built
  2026-08-02) actually works - checked with real evidence instead of answering from memory (the
  last logged status was "blocked on GCP billing," which was known to be stale). Pulled the full
  comment history of the pipeline's own test issue (GitHub issue #3, "add simple pink Endpoint
  returning status okay") via `gh issue view --json comments` - real, direct evidence, not assumed.
  Found something more specific than expected: the approve → GitHub-issue → scheduled-agent →
  implement → self-test chain is genuinely real and working - every one of 19+ consecutive hourly
  runs across ~21 hours correctly read the request, wrote a working endpoint, started a real local
  uvicorn server, and curl-verified it. Every single run then hit the identical wall: the scheduled
  agent's own execution sandbox has never had the `gcloud` CLI installed, so it has no way to
  authenticate or deploy - and to its credit, every run honestly reported this and left the issue
  open rather than faking a close. One run (#17) went as far as committing its verified change to
  `master` (`bf09c1d`) without deploying it. A later run explicitly flagged the loop itself:
  "retrying this hourly isn't going to produce a different outcome... this needs [gcloud access, or
  a different environment, or a human to deploy manually]" - a real, correct piece of self-
  diagnosis from the automated pipeline, not something anyone had to point out to it.
  With the user's explicit go-ahead, manually finished this one case using this session's real
  `gcloud` access: applying `bf09c1d`'s diff directly would have deployed backend code from before
  the same day's Phoenix Protocol work, silently rolling back the already-live
  `/elene/evacuate_backup` endpoint - caught by comparing `bf09c1d`'s diff context against the
  current `main.py` before deploying, not discovered as a live incident. Applied just the `/pink`
  endpoint onto the current working tree instead, deployed (revision `elene-backend-00040-dcg`),
  verified live via a real HTTP request (`{"status":"okay"}`, HTTP 200) and re-verified
  `/elene/evacuate_backup` still worked afterward (no regression) - then commented on and closed
  issue #3 with that evidence, matching exactly what the pipeline's own comments said still needed
  to happen before a real close. **Real open question, not resolved**: whether to give the
  scheduled routine actual `gcloud`/GCP credentials so future approved changes deploy unattended -
  a genuinely consequential decision (handing deploy access to an unattended scheduled job) that
  wasn't made here, just surfaced. See `planner/not_started.md`.

- (2026-08-08) User answered that open question directly: "I want it to go live without any human
  approval." Confirmed this meant automating the deploy step specifically (the fingerprint approval
  that creates the GitHub issue in the first place was never in question, not touched). Checked the
  routine's actual config via `RemoteTrigger action=get` before doing anything - real finding: its
  prompt was already written to grant full autonomy ("Approval is the ONLY human checkpoint...
  authorized to implement, test, and deploy... with no further human sign-off"), and its own schema
  has no secrets/env-var mechanism, only a stored prompt and MCP connectors (none connected). Flagged
  the real tradeoff to the user before proceeding - the only viable mechanism is embedding a live
  GCP service-account key directly in the routine's stored prompt, which then persists there
  indefinitely (visible in the routines UI, resent every run) - user explicitly chose to proceed
  with a tightly-scoped key rather than skip this or hunt for an MCP alternative.
  Created `elene-backend-deployer@cedal-fd4a2.iam.gserviceaccount.com` and tried to scope
  `roles/run.developer` down to only the `elene-backend` service via an IAM Condition - this became
  a real, extended debugging chain, not a quick grant: three different resource-name formats were
  tried (`projects/cedal-fd4a2/locations/us-central1/services/elene-backend`, matching Cloud Run's
  documented v2 condition format; `namespaces/cedal-fd4a2/services/elene-backend`, matching the
  literal resource string shown in the actual `PERMISSION_DENIED` error; and
  `namespaces/717899371194/services/elene-backend`, the project-number variant found via
  `metadata.selfLink`), each retested, one retested again after an 8+ minute wait specifically to
  rule out IAM propagation delay as the cause. All three failed identically. Rather than keep
  guessing a fourth format, got an authoritative answer from `gcloud policy-troubleshoot iam` (had
  to `gcloud services enable policytroubleshooter.googleapis.com` first, itself needing two tries -
  the first used a resource-name format the troubleshooter itself rejected as invalid, a different
  format requirement than the IAM condition syntax uses): the condition evaluates as
  `UNKNOWN_CONDITIONAL` for the specific `run.services.get` permission check `gcloud run deploy`
  makes early on - genuinely undetermined, not false - a real limitation of resource-scoped IAM
  conditions for this exact permission path in this GCP project, not a syntax mistake worth a fourth
  guess. Presented this finding plus the concrete tradeoff to the user (drop the per-service
  condition and accept a broader unconditioned grant covering all ~24 Cloud Run services in the
  project, vs. keep debugging with uncertain payoff, vs. abandon deploy automation) rather than
  silently picking one - **user explicitly chose the broader unconditioned grant**. Also found and
  fixed along the way: the intended `roles/iam.serviceAccountUser` target
  (`717899371194@cloudbuild.gserviceaccount.com`, the legacy Cloud Build service account) doesn't
  exist in this project at all (`NOT_FOUND`) - this project's Cloud Build actually runs as the
  compute default SA instead (a real, project-specific fact, not assumed from generic docs), so the
  binding was corrected to target `717899371194-compute@developer.gserviceaccount.com` instead.
  **Paused here at the user's explicit "wait stop"**, before the final steps (retest with the
  now-unconditioned grant, generate the routine's real key, wire it into the routine's stored
  prompt, run the routine once for real end-to-end confirmation) - nothing about the live
  `elene-backend` service itself was touched or is at risk; see `planner/in_progress/in_progress.md`
  for the exact resume point and full current IAM state.

- (2026-08-08, later same day) User confirmed the machine reboot finished and said to continue.
  Verified the environment survived cleanly first (own `gcloud` auth still active, the paused
  session's test key/config still present in the temp scratchpad) before resuming, rather than
  assuming. Retesting the deploy with the now-unconditioned `run.developer` grant turned into a
  second real debugging chain, not a quick confirmation - `gcloud run deploy --source` needs more
  than just Cloud Run access, and each gap only showed up as a real error once the previous one was
  fixed: `artifactregistry.repositories.get` denied on the `cloud-run-source-deploy` repo (fixed
  with `roles/artifactregistry.writer` scoped to just that repo) → `storage.buckets.get` denied on
  `run-sources-cedal-fd4a2-us-central1` (a different, newer-style source-upload bucket than the
  `cedal-fd4a2_cloudbuild` one originally scoped - `gcloud run deploy --source` apparently moved to
  this bucket pattern; `roles/storage.objectAdmin` scoped to it still wasn't enough) → upgraded to
  `roles/storage.admin` on that one bucket (still failed - turns out `storage.objectAdmin` doesn't
  include `storage.buckets.get` at all) → then `storage.buckets.list` denied at the *project* level,
  which can't be scoped to a single bucket in GCS's IAM model at all (list is inherently project-
  wide). Rather than silently keep escalating (five real permission gaps deep at that point, well
  past the original "scope it as tightly as possible" plan), stopped and put the actual tradeoff to
  the user directly: grant project-wide `roles/storage.admin` (full control over every bucket in the
  project, including the Phoenix Protocol evacuation bucket) vs. keep debugging narrower roles vs.
  abandon. **User explicitly chose the project-wide grant.** With that granted, the deploy finally
  succeeded for real - revision `elene-backend-00041-shz`, independently verified live via `curl`
  against `/pink` (not just trusting the deploy command's exit code) and re-verified
  `/elene/evacuate_backup` still worked afterward (no regression from any of the IAM changes).
  Revoked the test key immediately after (it had only ever been used inside an isolated `gcloud`
  config directory, never the main session's own auth), deleted all local test artifacts, then
  generated a dedicated fresh key for the routine's actual long-term use - the debugging key and the
  routine's real key were never the same key. Wired the new key into the routine's stored prompt via
  `RemoteTrigger action=update`: fetched the exact current prompt text first (not reconstructed from
  memory), used a small Python script to do an exact-string-match replace of the old "check for
  gcloud, stop if missing" step with a new "authenticate with the embedded key, then proceed" step -
  the script asserted the old text matched verbatim before writing anything, specifically to avoid
  silently corrupting the prompt if the remembered text had drifted from what was actually stored.
  Confirmed the update landed by reading the routine back afterward. Ran the routine once manually
  (`RemoteTrigger action=run`) as a sanity check - but `gh issue list` showed zero open
  `approved-backend-update` issues at the time, so this only proves the updated prompt doesn't
  error, not that the deploy path fires correctly from inside the routine's own cloud sandbox.
  Deliberately did not fabricate a test issue to force a fuller test - the routine's whole trust
  model rests on that label only ever being applied by a real on-device fingerprint approval, and
  faking one would undermine the exact thing being protected. Real confirmation of the full
  unattended loop is still pending the user's next genuine approved proposal. Cleaned up every local
  scratchpad artifact (key files, gcloud test config, the prompt-diff script) once the update was
  confirmed landed - nothing about this work should require hunting through temp files later.

- (2026-08-09) The gcloud-deploy fix from a few hours earlier turned out to be built on an
  unverified assumption, and a separate line of conversation is what surfaced it. The user asked
  about expanding self-updates to Android/Kotlin app changes too - including a real one-off
  diagnostic that proved the cloud routine's sandbox can't build Android apps at all (hard 403 at
  the sandbox's egress gateway on `dl.google.com`/`maven.google.com`, the host every Android Gradle
  build needs just to resolve the Android Gradle Plugin itself, before ever touching this app's own
  native code or ONNX models - confirmed conclusively, not a maybe). The user then proposed a smart
  workaround (a custom Cloud Build container with everything pre-installed, since Cloud Build runs
  on Google's own infrastructure with a different network path than the sandbox) - and reasoning
  through *that* idea is what surfaced the real gap: any Cloud-Build-based fix still needs the
  sandbox to invoke `gcloud` (or an equivalent API call) from inside itself, and the historical
  evidence (19+ runs all reporting `gcloud: command not found`, before today's fix) meant that was
  never actually confirmed to work even for the backend pipeline - today's fix only added an
  authentication step, on the assumption a `gcloud` binary would already be there to run it, and
  that assumption was never tested end-to-end.
  Ran a second isolated diagnostic to check for real: tried all three official Google Cloud SDK
  install methods (apt repo via `packages.cloud.google.com`, the `sdk.cloud.google.com` installer
  script, a direct `dl.google.com` tarball) - all three hit a real 403 at the sandbox's own egress
  gateway, confirmed via the proxy's own status log (`connect_rejected` / policy denial), running as
  root so it wasn't a permissions issue. **Conclusion: `gcloud` cannot be installed in this sandbox
  by any method - the same class of hard network-policy block already found for Android tooling,
  not specific to Android at all.** This meant the service-account key embedded in the production
  routine's prompt a few hours earlier could never actually have been used for its intended purpose,
  regardless of how correctly it was IAM-scoped - real, if scoped, credential exposure with zero
  functional benefit.
  Separately, real credit due: that same diagnostic session, on its own initiative, refused to write
  the embedded private key to disk or use it for authentication, flagging that a live credential
  embedded in an unattended automated prompt - however thoroughly justified in the surrounding text
  - is indistinguishable in shape from a credential-exfiltration attempt, and that it had no way to
  verify who actually authored that justification. It was moot here (no `gcloud` binary existed to
  authenticate with anyway), but it's a genuine, independently-arrived-at safety judgment worth
  recording, not a false alarm to wave off.
  Acted immediately, same session, mid-conversation (the user asked to pause and restart their
  machine partway through this) rather than letting it sit: revoked the exposed key
  (`gcloud iam service-accounts keys delete`), fetched the routine's exact current prompt, and used
  the same verified exact-string-match-replace approach as the original fix to strip all key
  material out and restore step (e) to honestly checking for `gcloud` and stopping if absent - now
  explicitly documented as a confirmed, permanent environment limitation rather than a soft
  "currently missing" note, so a future session doesn't waste time re-investigating a question that's
  already conclusively answered. Confirmed via re-reading the routine that no key material remains.
  The service account and its IAM grants were left in place (dormant, unused, no ongoing cost or
  risk on their own) rather than torn down, since they'd become useful again if the Cloud-Build-based
  approach the user proposed is ever actually built and proven - that idea itself is real and
  worth pursuing, just not yet attempted; see `planner/not_started.md`.

- (2026-08-09) Followed through on the user's Cloud Build idea with real evidence at each step,
  not assumption. First: a reachability diagnostic (same safe pattern as prior ones - read-only
  curl checks, no credentials) confirmed `cloudbuild.googleapis.com`, `oauth2.googleapis.com`, and
  the rest of Google's API surface are reachable from the routine's restricted sandbox, while
  `dl.google.com` (the known-blocked baseline) failed instantly with the same gateway-rejection
  signature already seen twice before - confirming the sandbox blocks *software distribution*
  specifically, not Google's APIs generally. Genuinely good, unexpected news given how the day's
  earlier `gcloud` investigation had gone.
  Tried to validate the next layer - does a real JWT-auth-and-trigger round trip actually work, not
  just "is the host reachable" - via another one-off diagnostic routine, embedding a fresh
  short-lived key with an explicit, detailed justification for why it was legitimate this specific
  time (short-lived, narrowly-scoped, already-approved permissions, trivial harmless test action).
  **Claude Code's own auto-mode safety classifier blocked the attempt anyway.** Did not try to work
  around it - the block itself was reasonable given the exact same pattern (a live credential
  embedded in an unattended automated prompt) had already caused a real problem earlier the same
  day. Revoked the never-used key immediately, explained the block to the user plainly, and offered
  concrete alternatives rather than quietly giving up or trying to route around the classifier.
  User chose to run the same test interactively instead - a real session with a human directly
  present authorizing each step, the exact distinction the classifier's concern doesn't apply to.
  Generated another fresh short-lived key, confirmed `google-auth`/`requests` were already
  available locally (no new dependency needed), then ran a real Python script using
  `google.oauth2.service_account.Credentials` + `AuthorizedSession` - genuine JWT-based
  service-account auth with zero `gcloud` CLI involvement anywhere - to POST a trivial build
  directly to Cloud Build's REST API. **Real, complete success, watched end to end, not just
  trusted from the first response**: HTTP 200, a real build ID came back
  (`3493031b-9011-4bd9-ac99-5e78b1dfd526`), and polling the build status confirmed it actually
  reached `SUCCESS` in two ~10s cycles, with a real Cloud Build console log URL to show for it.
  Revoked the key immediately after, same as every other test key this session - nothing left
  live once the answer was in hand.
  Real, honest bottom line logged in `not_started.md`: the *mechanism* is now proven, not just
  theorized - but wiring it into the actual production routine still means embedding a credential
  into an unattended context again, the same category of decision that already went wrong once
  today for a different reason (the credential turned out to be useless, not that embedding it was
  inherently wrong) - so that's flagged as needing the user's explicit go-ahead again before being
  built, not treated as a foregone conclusion just because the underlying idea now checks out.

- (2026-08-09, later same day) User said to go ahead and build the actual builder image. Docker
  Desktop wasn't running locally, so rather than starting it, built the image via Cloud Build
  itself instead - `gcloud builds submit --tag`, using the normal session's own already-
  authenticated `gcloud` (no new service-account key needed for this part, unlike the auth tests).
  Pulled the exact SDK/build-tools/NDK/CMake version strings straight from
  `app/build.gradle.kts` rather than guessing generic ones. Created a new dedicated Artifact
  Registry repo (`android-builder`) rather than reusing the existing `cloud-run-source-deploy` one,
  to keep a persistent reusable image cleanly separated from ephemeral per-deploy source builds.
  Real result: built clean in 4 minutes, confirmed via the actual Cloud Build log, not just a "no
  error" assumption.
  Then ran the real test that actually mattered: used the new image to build **this app**, not a
  synthetic project - submitted the real repo source (`.gitignore` already excluded `build/`,
  `.gradle/`, `.cxx/`, `.env`, keeping the upload to ~235MB rather than everything) with a Cloud
  Build config running `./gradlew :app:assembleDebug`. The top-level result showed "FAILURE" -
  did not take that at face value. Read the actual log line by line instead: Gradle itself printed
  `BUILD SUCCESSFUL in 2m 53s` with all 42 tasks executed, the native RNNoise C code compiled
  cleanly through CMake/ninja, and a real 66,982,096-byte `app-debug.apk` existed exactly where
  Gradle puts it. The actual failure was `bash: line 5: file: command not found` - the diagnostic
  script's own last verification step called `file` on the APK as a sanity check, and that
  utility simply isn't in the minimal builder image. A gap in the throwaway test script, not in
  the build or the underlying idea - confirmed by tracing the real log rather than trusting the
  top-level Cloud Build status alone, the same "verify the actual evidence, not the summary"
  discipline this whole session has run on.
  Cleaned up the temporary `cloudbuild-android-test.yaml` from the repo root (a diagnostic
  artifact, never meant to be committed). Logged the full, honest result in `not_started.md` and
  `done.md`: the complete idea is proven end to end now - JWT-REST auth, Cloud Build triggering,
  and this specific app's real native-code build all confirmed working with real evidence, not
  assumed at any point. What's deliberately not done: wiring any of this into the actual
  production routine, which would mean embedding a credential into an unattended context again -
  flagged as needing the user's explicit decision, not treated as automatically following from
  today's proof.

## Elene "unhide screen" phrasing fix (2026-08-31)
User told Xenos/Elene "unhide screen" via chat and it wasn't understood - the `hide_page` system
prompt entry only documented the hide-direction phrasing ("hide page") and didn't mention that the
same toggle command also handles the un-hide direction. Updated `backend/elene/main.py`'s command
list entry for `hide_page` to explicitly list unhide-direction phrasings ("unhide screen", "show my
screen", "reveal the screen", "bring the screen back", "stop hiding") and state plainly there is no
separate unhide/show command - it's always `hide_page` either way.
Compiled clean (`python -m py_compile main.py`) before deploying. Deployed via
`gcloud run deploy elene-backend --source . --region us-central1 --project cedal-fd4a2 --quiet`,
revision `elene-backend-00052-xtk`, 100% traffic. Real smoke test against the live URL, not assumed:
`POST /elene/chat {"user_id":"smoketest","text":"unhide screen","context":{}}` returned
`{"intent":"command","command":"hide_page","commands":["hide_page"],"reply":"Unhiding the screen
now, Emperor."}` - confirmed working. Regression-checked the original phrasing too: `"hide the
screen"` still correctly returns `command":"hide_page"` with a hide-flavored reply. Both directions
confirmed live.

## find_my_location Elene command (2026-08-31)
Added a real safety-tool voice command alongside the new MyLocationActivity screen (a live GPS map
for someone genuinely lost - see combination.md for the full feature). Backend prompt's command
list now documents `find_my_location` for phrases like "I'm lost"/"where am I"/"find my location",
explicitly told to treat it as urgent and not ask clarifying questions first. Compiled clean
(`python -m py_compile main.py`), deployed via `gcloud run deploy elene-backend --source . --region
us-central1 --project cedal-fd4a2 --quiet`, revision `elene-backend-00054-vtb`, 100% traffic. Real
smoke test against the live URL: `POST /elene/chat {"user_id":"smoketest4","text":"i am lost,
where am I","context":{}}` returned `{"intent":"command","command":"find_my_location",
"commands":["find_my_location"],"reply":"Finding your location now, Emperor."}` - confirmed
working.

## Xenos "emotion" field (2026-09-03)
Added a real "emotion" field ("smile"/"frown"/"curious"/"neutral") to the `/elene/chat` JSON
schema and reply model, with a new "Your face" system-prompt section telling Xenos it has a real
visual face on the Xenos screen and instructing it to set the field to its genuine reaction, not a
forced/default value. Client (`EleneApiClient.EleneResponse.emotion`, `XenosActivity`,
`XenosSkeleton`) drives the skeleton's mouth-curve/head-tilt from this - replaces an earlier
client-only "smile" keyword hack with a real backend-driven multi-expression system. Compiled
clean, deployed via the standard `gcloud run deploy` command, revision `elene-backend-00055-nwd`,
100% traffic. Real smoke tests against the live URL, three cases:
- "you just told me a great joke and I am laughing" -> `"emotion":"smile"`
- "my dog just died and I feel awful" -> `"emotion":"frown"`
- "wait what does that error message even mean, im so confused" -> `"emotion":"curious"`
All three confirmed correct and distinct - the field genuinely reflects context, not a fixed
default.

## Standing meta-note from the user (2026-07-26)
User explicitly flagged that we were "bouncing from one thing to another" - building fix after
fix without confirming each one actually works before moving to the next. This planner exists
to stop that: before starting something new, check in_progress/ and don't add to done/ until
there's a real confirmed test result logged here.
