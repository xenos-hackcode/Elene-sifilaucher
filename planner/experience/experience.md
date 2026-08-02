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
  **Not yet confirmed live end-to-end**: no real proposal has gone through the full chain yet
  (approve on phone -> issue created -> picked up by the hourly routine -> implemented, tested,
  deployed, verified, issue closed) - the user's GCP billing needs resolving first before any of
  this can be exercised for real, same blocker as the other pending live-test items from this
  session.

## Standing meta-note from the user (2026-07-26)
User explicitly flagged that we were "bouncing from one thing to another" - building fix after
fix without confirming each one actually works before moving to the next. This planner exists
to stop that: before starting something new, check in_progress/ and don't add to done/ until
there's a real confirmed test result logged here.
