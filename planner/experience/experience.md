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

## Standing meta-note from the user (2026-07-26)
User explicitly flagged that we were "bouncing from one thing to another" - building fix after
fix without confirming each one actually works before moving to the next. This planner exists
to stop that: before starting something new, check in_progress/ and don't add to done/ until
there's a real confirmed test result logged here.
