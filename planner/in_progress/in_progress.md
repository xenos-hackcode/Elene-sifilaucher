# Working on right now

*Rewritten 2026-07-30 — the previous version of this file was stale (dated 2026-07-26, referencing
a batch of questions that were superseded by later work). The real, current backlog lives in
`planner/experience/experience.md`; this file is just the live index into it.*

## Standing rule (unchanged, still active)
Per the user's 2026-07-26 note: don't start new feature work until the last batch has a real,
on-device confirmed result logged in `experience/`. A LOT has been built across 2026-07-27/28
(screen perception, Updates screen, Sandbox, Sequence Mode force-open, the four-feature batch,
Intruder Attempts rebuild, "Hey Elene" wake word, ECAPA quantization + Voice ID enrollment fixes,
Link to Phone) and almost none of it has been confirmed live yet. Clearing this backlog is the
current task — see `planner/possibilities` / this file's checklist below, not new feature work.

## Live-confirmation checklist (2026-07-30)
Each line is one "not yet confirmed live" item pulled from `experience.md`, in rough order of how
easy it is to test with just this one phone and no extra setup. Check items off (and log the real
result in `experience.md`) as they're actually tested — don't mark done from a clean build alone.

### Testable solo, right now, on this one device
- [x] Capability question: does Elene now give the fuller "can't self-update, but can queue a
      proposal" answer to "can you update yourself?" **Confirmed 2026-07-31** - user asked live,
      Elene answered correctly. (Note: this whole checklist was blocked for about a day by a real
      GCP billing incident, now resolved - see `experience.md` 2026-07-30/31 entry.)
- [ ] "Hey Elene" wake word: reliably detected in practice? Real battery cost? Actually stops
      listening during battery saver?
- [ ] Memory recency-wins: tell two contradicting facts, confirm the newest is listed first and
      marked, and that Elene trusts it in conversation.
- [ ] remember_fact / remembered_facts: does a remembered fact survive a backend restart/recycle?
- [x] Voice ID enrollment: re-enrolled after the factory reset wiped it - **confirmed 2026-08-01**
      via real embedding data in voice_id_prefs.xml (all 5 styles: ALPHABET, LONG, MEDIUM,
      NUMBERS, SHORT).
- [x] Voice ID general-command gate: found and fixed a real, likely long-standing bug via live
      testing - the gate's verification recording only started *after* the full backend
      round-trip, by which point the user had gone silent for several seconds, so it silently
      fail-opened on effectively every real command (0% real capture rate). Fixed by running the
      verification recording concurrently with the backend call instead of after it. **Confirmed
      2026-08-01** - real scores now get captured and low-confidence matches genuinely block
      command execution, not airtight for every short utterance but a real, working improvement
      over the previous fully-decorative gate. See experience.md for full detail.
- [ ] Screen perception (describe_screen / play_game): real voice-triggered flow, MediaProjection
      consent, a real screenshot correctly described, game-loop actually tapping a real game,
      app-switch auto-stop, the mic-stays-closed-during-bootstrap fix, the avatar-vs-chat-row tap
      fix in WhatsApp.
- [ ] Updates screen: a real voice-triggered proposal creating an entry + live confirmation panel,
      an Elene-self-initiated proposal landing quietly, reviewing/approving from the screen with a
      real fingerprint scan.
- [x] WiFi status panel: connected SSID/signal/link-speed/IP displaying correctly; "Reveal
      password" now showing the honest "Android hides this" / "only a derived key" message instead
      of wrong data. **Confirmed 2026-07-31.**
- [ ] Sandbox: Shizuku "Start" re-tapped, then a real Shizuku shell command actually gated
      (passed/rejected) by the isolated `:sandbox` process.
- [x] Intruder Attempts: a real failed fingerprint scan producing an actual photo + location entry
      visible in Security > Storage. **Confirmed 2026-07-31** - 5 real entries with real photo/
      GPS/timestamp/reason. Also added tap-to-fullscreen photo viewing, confirmed working.
- [x] Anti-tampering/RASP hardening pass (APK integrity, root/Magisk/SELinux, exported-component
      signature verification, native layered Frida detection) - **confirmed 2026-07-31**, all four
      checks live-tested correct on the real device. See done.md.
- [x] Sequence Mode force-open / a real Sequence Mode alert firing while genuinely armed -
      **confirmed 2026-07-31**, but as a side effect of testing the new failed-fingerprint
      auto-arm trigger below, not the originally-planned deliberate test - a real WhatsApp/SMS
      alert reached one real contact before the user's own disarm cancelled the rest.
- [x] Weather feature (Dashboard display + Elene voice) - **confirmed 2026-07-31**, after fixing
      two real gaps found live: a dead hardcoded dashboard placeholder, and no location fix
      existing system-wide on this freshly-reset phone (built an active location-request
      fallback, see experience.md).
- [x] Sequence Mode auto-arm triggers (new, not previously planned) - N-failed-fingerprint-attempts
      and accelerometer motion-spike detection, both with a confirm-or-arm grace period for the
      weaker motion signal - **confirmed 2026-07-31**, both real bugs found via on-device
      evidence and fixed (alert-interval timing, motion threshold tuning). See experience.md for
      full detail.
- [ ] Mic-reopens-with-AI's-own-voice: does the 500ms buffer after TTS actually stop the mic from
      picking up Elene's own voice, or does the issue persist?
- [x] Anti-theft mode toggle (2026-08-07, see done.md) - **confirmed 2026-08-07**, user tested
      live on the real device, works as intended.
- [x] Phoenix Protocol evacuation backup (2026-08-07, see done.md) - **confirmed 2026-08-08**, user
      tapped "Test evacuation backup now" on the real device and it succeeded, after a real
      SocketTimeoutException bug was found via logcat and fixed (readTimeout/writeTimeout weren't
      actually raised the first time, only callTimeout was).
- [ ] Hey Elene personalized wake-word matching (2026-08-08, see done.md) - needs the user to
      actually record 5 takes in Settings, then test over real repeated use whether "Hey Elene"
      now reliably triggers for their accent (the actual problem this was built to fix) and
      whether the auto-calibrated DTW threshold needs adjustment either direction.
- [x] Full UI localization, Settings screen (2026-08-10, see done.md) - **confirmed 2026-08-11**,
      user tested live, everything works.
- [ ] Full UI localization, Security screen (2026-08-11, see done.md) - built on the same
      tr()/LocalLanguage system, languageOption lifted to MainActivity so both screens share live
      state; not yet confirmed live - user needs to switch to Yoruba and check Security this time
      (Settings already confirmed working).
- [ ] Tracker/ad blocking VPN fixes (2026-08-11, see done.md) - concurrent DNS forwarding + shorter
      timeout (fixes real slowness), plus a structured-concurrency fix so stopping the VPN actually
      cancels in-flight work immediately (fixes "not answering" after toggling off). Neither
      re-tested live yet after the second fix.
- [ ] Security screen fixed header (2026-08-11, see done.md) - back button restructured to stay
      pinned while scrolling, matching Settings. Not yet confirmed live.

## Full UI localization: remaining screens (2026-08-10, ongoing)
User explicitly chose full scope ("every screen, every string") over a smaller Settings-only or
spoken-phrases-only option. Settings and Security screens are complete (see done.md) - every other
screen (Dashboard, Capabilities, About, Memory, Nearby Devices, Multi Control, Icon Pack
panel, etc.) still has hardcoded English strings and needs the same tr()-based migration. This is
real, large, ongoing work (every screen has its own set of strings) - continue incrementally,
screen by screen, verifying each compiles and renders correctly before moving to the next, rather
than attempting all of them in one uncheckable pass.

## Self-update routine unattended deploy - status split (2026-08-09)
**No longer in progress - resolved, moved to done.md.** The original `gcloud`-in-sandbox approach for
the **backend** deploy path is confirmed permanently dead (hard 403 network-policy block on every
official Google Cloud SDK distribution channel) and the routine honestly reports that limitation and
stops rather than pretending to deploy. The **Android app** path, by contrast, is now live: routed
around the same `gcloud` gap entirely by triggering Cloud Build over raw REST (JWT auth, no CLI),
proven with a real successful build of this app, then wired into the production routine with the
user's explicit go-ahead. See `done.md` for both accounts (the backend dead-end and the Android
wiring) and `not_started.md` for the debugging history.

**Not yet confirmed live**: the production routine actually firing on a real approved Android-scoped
issue and completing the full j-o flow unattended - proven interactively and via manual build/test
so far, not yet through a real routine run.

### Needs something beyond this one phone — ask the user before attempting
- [ ] Sister's voice (or any non-enrolled voice) actually gets refused for a gated command —
      needs a second real person.
- [ ] A real incoming cellular call: ringing-announcement, `acceptRingingCall()` actually answering
      on this Samsung device, call-screening role live decline.
- [ ] Message reply/compose-and-send round trip with a real WhatsApp contact.
- [ ] Calendar meeting-awareness with a real calendar event.
- [ ] Voice-memo playback round trip.
- [ ] Traffic proxy routing to an actually-running mitmproxy/Burp instance on a laptop.
- [ ] Keep-screen-on while talking, holding through a real multi-turn conversation.
- [ ] Link to Phone: the full two-device pairing/streaming/command-dispatch flow — needs a second
      real Android device (or emulator) with the new `:agent` app (`com.example.phonelinkagent`)
      installed. Not available in the previous session.
- [ ] Agent app's disclosure screen — real visual walkthrough (only confirmed via code/manifest so
      far).
- [ ] Sequence Mode force-open (Option A): an actual Sequence Mode WhatsApp alert firing while the
      phone is genuinely locked, confirming the keyguard is bypassed just for that send and fully
      restored after. **Caution**: this deliberately manipulates the real lockscreen — coordinate
      timing with the user before triggering, don't do it unattended.

## Blocked on GCP billing, ready to test once it's paid (2026-08-01)
Built, installed, no crash - but not yet live-tested since the user's GCP bill needs paying
before backend calls are reliable enough to test against:
- The shared "last_visual_insight" context fix - bridges the real gap between
  `/elene/game_move`/`/elene/describe_screen` (vision-based) and `/elene/chat` (conversational)
  having zero shared context by default, found live via the user's own astute "two AIs fighting
  for control" observation while testing screen perception. Backend deployed (revision
  `elene-backend-00035-6xt`), app installed. **Not yet confirmed**: does asking "what's on my
  screen" mid-game now actually answer from the game loop's own real reasoning instead of giving
  a blind/disconnected answer.
- Also still open from the same testing round, not yet re-confirmed after the prompt fixes:
  does `describe_screen` reliably re-trigger on every repeat "what's on my screen" ask instead
  of reusing a stale result (two prompt iterations already tried - the first stopped outright
  fabrication, but repeat asks still weren't reliably re-triggering the real command); does
  reusing an already-active game-loop capture session (instead of re-requesting MediaProjection
  consent) actually stop the disruptive mid-game consent-dialog interruption.
- Updates screen Stage 2 (real backend self-update pipeline, built 2026-08-02) - end to end,
  untested: approve a real proposal on the phone -> confirm a GitHub issue actually gets created
  -> wait for the hourly routine (`trig_01XxHRhbPpqZWmDnmVBSCqSe`) to pick it up -> confirm it
  implements/tests/deploys/verifies correctly and comments+closes the issue. See experience.md
  2026-08-02 entry for the real infra problems already found and fixed getting this far (stale
  repo path, under-permissioned PAT).

## Blocked, needs a decision (not being worked on until resolved)
Wake-word *engine* upgrade (a real trained "Hey Imperial"-style model, not the current STT-polling
approximation used for "Hey Elene"): needs training data (50+ recorded samples of the phrase) and a
training run this environment cannot perform standalone — it's not a pure coding task. Options,
not yet chosen:
- Record the samples yourself and run training separately (a computer with more compute), then
  hand the resulting model file back to drop into the app.
- Keep the current STT-polling approximation ("Hey Elene" already ships this way) — works today,
  gives up the always-on/~1%-CPU/offline efficiency a real trained wake-word model would have.
- Accept one of openWakeWord's existing pretrained words instead of a custom phrase.
