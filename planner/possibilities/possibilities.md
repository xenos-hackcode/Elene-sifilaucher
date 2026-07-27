# Possibilities — what happens when two things overlap

*Started 2026-07-27, after the call-awareness feature surfaced three real bugs (mic reopening
on a locked phone via a call announcement, the voice-memo recording picking up Elene's own
confirmation, a ringing call colliding with an active conversation) that none of the individual
feature reviews caught, because each was reviewed on its own. This document exists to think
through **interrupting-event × feature** combinations deliberately, before they're found by
accident on the real phone. Every entry gets an honest verdict: **Fixed** (built and in the
codebase now), **Real gap** (a genuine bug or unhandled case, not yet fixed), or **Platform
limit** (Android itself doesn't give a non-privileged app enough access to solve this, disclosed
rather than faked). Nothing in here is a hypothetical nation-state scenario - every entry is
something that could plausibly happen during ordinary daily use of this phone.*

## A. An incoming call, while something else is happening

- **Talking to Elene (mid-listening or mid-reply) when a call rings.** *Fixed.* The announcement
  used to fire straight into `speakOut()` while `SpeechRecognizer` could still be open, fighting
  it for the mic and risking the announcement's own audio being misheard as something the user
  said. `announceIncomingCall()` now cleanly stops any active recognition session first.
- **Phone is locked when a call rings.** *Fixed* - and this was the more serious of the two,
  since the failure mode was a real lock-screen bypass, not just an annoyance. `startListening()`
  had no keyguard check of its own; it relied entirely on the bubble-hide logic to keep the mic
  closed while locked, but the call announcement's own completion callback
  (`retryListeningSoon` → `startListening`) was a path that reopened the mic that never went
  through that bubble-hide logic at all. Fixed at the actual source: `startListening()` now
  checks `isKeyguardLocked` itself, so it can never be tricked into opening the mic while locked
  regardless of what triggers it.
- **A voice memo is recording when a call rings.** *Fixed* (partially - see the recording section
  below for the real gap). The announcement now mentions "you're still recording a voice memo" so
  it isn't forgotten about.
- **A voice memo is recording and the call is answered/goes active.** *Fixed for cellular calls
  only.* `onCallActive()` (hooked to `CALL_STATE_OFFHOOK`) stops the memo the moment a cellular
  call actually connects, since `VoiceMemoService`'s `MediaRecorder` and an active call both want
  exclusive access to `AudioSource.MIC`. **Real gap:** the same thing happening because a *VoIP*
  call (WhatsApp) gets answered isn't detected at all - VoIP calls never touch
  `TelephonyManager`/`CALL_STATE_OFFHOOK`, and there's currently no signal for "the VoIP call the
  user was just ringing is now actually connected." A voice memo left running through an answered
  WhatsApp call will keep recording, contending for the mic with the call itself.
- **A device-action confirmation (uninstall/force-stop/permission grant) is on screen, waiting
  for a fingerprint, when a call rings.** *Real gap, not yet fixed.* Two separate risks here:
  1. The confirmation's own spoken prompt ("Uninstall X? Because Y. Yes, no, or ask again
     later?") can get cut off mid-sentence by the call announcement, since `speakOut` uses
     `QUEUE_FLUSH`. The confirmation panel itself stays fully visible and functional - nothing
     is lost - but the user may not have heard the *reason* being given for the request.
  2. Whether Android's own incoming-call UI can interrupt or background the
     `BiometricAuthActivity` mid-fingerprint-scan is genuinely unknown without a real test - if
     it does, the pending `onSuccess`/`onFailure` callback might never fire. This isn't as bad as
     it sounds: the existing 90-second auto-snooze (`LaunchedEffect(confId) { delay(90_000);
     snoozeConfirmation(30) }`) already covers a confirmation that gets stuck for any reason, so
     the worst case is "the request quietly reschedules itself for 30 minutes later," not a
     permanently broken state. Still worth a real test to know for sure.
- **Screen recording is active when a call rings.** Low risk, not fixed because it likely doesn't
  need fixing - Android's own incoming-call UI drawing over a screen recording is completely
  normal behavior (any screen recorder captures whatever the OS actually shows), and the spoken
  announcement being caught in the recording (if `RecordAudioMode.MIC` is on) is an accurate
  record of what happened, not a bug.
- **A second call arrives while already on a call (call waiting).** *Platform limit, disclosed
  rather than guessed at.* `PhoneStateListener`/`TelephonyCallback` only expose single-call
  granularity (ringing/active/idle) - there's no signal in that API for "a second call is now
  also ringing while the first stays active." Properly seeing and managing multiple simultaneous
  calls needs the full `InCallService`/Telecom `Call` object model, which - same as real call
  answering/declining - effectively requires being the default dialer app. This app currently has
  no way to detect or announce a call-waiting scenario at all.
- **Sequence Mode (anti-theft lockdown) is active when a call rings.** Worth being aware of, not
  necessarily a bug: Sequence Mode's "total silence" DND (`INTERRUPTION_FILTER_NONE`) genuinely
  silences the phone's own ringtone by design - that's what Total Silence does system-wide,
  including calls. Elene's own `speakOut()` announcement is a separate audio path (TTS/media
  playback, not a notification), so it's plausible it could still announce the caller out loud
  even while the ringtone itself is silenced by DND - which would partially work against the
  point of a silent lockdown. Not independently confirmed either way; worth a real test during
  Sequence Mode specifically.

## B. Deleting/freezing/managing an app, while something else is happening

- **An uninstall confirmation is pending and a call rings.** Covered under A above (the same
  entry applies - it's the concrete version of the general "confirmation + call" case).
- **Uninstalling an app while `PackageInstallWatcher` is mid-processing a *different* new
  install.** Low risk - the watcher only reacts to `ACTION_PACKAGE_ADDED`, uninstalls don't
  trigger it, and Android serializes package-manager operations at the OS level regardless.
- **Force-stopping or freezing an app that's currently holding audio focus (playing music).**
  Not a real conflict - stopping the app releases its audio focus naturally, nothing in this
  app's own audio-focus handling depends on that app still running.
- **Uninstalling the Cedal sibling app while a Cedal Shared System sync broadcast is in flight.**
  Low risk, narrow window, and the broadcast is fire-and-forget (`_safe_send_text`-style pattern)
  with no expectation of a guaranteed delivery.
- **A scheduled action (delayed uninstall/download, `ScheduledActionReceiver`) fires while the
  device-action confirmation panel is already showing a *different*, unrelated request.** Not
  checked directly - `ScheduledActionReceiver` executes independently of the Compose-side pending-
  confirmation state (by design, since it may run in a completely different process/lifecycle
  than the UI). Worth a real test: does a scheduled uninstall firing while an unrelated
  confirmation panel is up on screen cause any visible confusion, or do they simply not interact
  since the scheduled action doesn't route back through the same confirmation UI at all?

## C. Recording (screen or voice memo), while something else is happening

- **Voice memo recording + incoming call (ringing only, not yet answered).** *Fixed* - see
  section A, the announcement mentions the memo is still running; the memo itself correctly
  keeps recording since a merely-ringing call hasn't taken the mic yet.
- **Voice memo recording + a cellular call gets answered.** *Fixed* - see section A.
- **Voice memo recording + a VoIP call gets answered.** *Real gap* - see section A.
- **Voice memo recording + screen recording with `RecordAudioMode.MIC` running at the same
  time.** *Real gap, not yet fixed or even tested.* Both `VoiceMemoService` and
  `ScreenRecordService` (in MIC mode) call `MediaRecorder.setAudioSource(MediaRecorder
  .AudioSource.MIC)` independently, with no coordination between them at all. Nothing currently
  stops a user from saying "start recording" (voice memo) while a screen recording with mic
  audio is already going, and what actually happens - one silently gets no audio, one throws, or
  Android's audio policy quietly time-shares them - is genuinely unknown without a real test.
- **Voice memo recording + Elene needing to say *anything else* during it** - a notification
  announcement, a reply to an unrelated question, anything beyond the one "Recording started."
  confirmation that was specifically fixed. **Real gap, and probably the most likely one of all
  of these to actually happen.** The fix already built only sequences the *start* confirmation
  correctly (delays the mic opening until that one phrase finishes). It does nothing about
  anything Elene says *after* that, for the whole rest of the recording - a new notification
  arriving mid-memo, or the user asking Elene something else while it's running, would speak
  through the same `speakOut`/TTS path and could bleed into the recording exactly the same way
  the original bug did, just later in the timeline instead of at the very start.
- **Whether continuous listening can even coexist with an active voice memo at all** - i.e., can
  the user say "stop recording" out loud while `VoiceMemoService`'s `MediaRecorder` is already
  holding the mic, or does `SpeechRecognizer` simply fail to hear anything because the mic is
  monopolized? **Genuinely unknown, not guessed at.** This matters a lot: there's currently no
  button or UI affordance to stop a voice memo other than the voice command itself, so if the mic
  can't actually be shared between `MediaRecorder` and `SpeechRecognizer` on this device, "stop
  recording" may not be reliably hearable while it's needed most. This needs a real on-device
  test before being trusted, and if it turns out not to work, the fix is probably adding a manual
  stop affordance (a notification action on the recording's own foreground-service notification)
  rather than depending on voice alone.
- **Phone gets locked while screen recording is active.** Not fixed because it's expected to
  already be fine - `MediaProjection`-based recording is a system-level screen mirror that keeps
  capturing through a lock/unlock cycle by design; nothing about the call-awareness or lock-state
  fixes in this pass changes that.

## D. Everything else worth naming, even without a paired scenario yet

These didn't come from a specific "X while Y" report, but follow the same reasoning and are worth
having on record rather than only writing this document reactively:

- **Two Elene commands genuinely overlapping** - e.g. a scheduled action firing at the same
  moment the user is mid-conversation with Elene about something unrelated. The existing 350ms
  gap between multi-step commands and the dedup-consecutive-duplicates guard exist for a related
  but narrower problem (one backend response containing several steps); a scheduled action firing
  from a totally separate trigger (`AlarmManager`) isn't covered by either.
- **Storage genuinely full** while screen recording, voice memo recording, or Sequence Mode's
  intruder-photo capture is trying to write a file. None of the three explicitly check available
  space first - `MediaRecorder.prepare()`/`start()` failing is wrapped in `runCatching` in the
  places built this session, so it should fail safely rather than crash, but "safely" here hasn't
  been confirmed to mean "tells the user why," just "doesn't take the process down."
- **Audio focus contention between Elene's own TTS and a call announcement's TTS**, if both
  happen to be triggered in the same instant by two independent events (e.g. a scheduled action's
  spoken confirmation firing at the exact moment a call starts ringing). `QUEUE_FLUSH` means one
  wins and the other is silently dropped rather than queued - probably the right behavior, but
  which one wins hasn't been deliberately decided, just whichever happens to call `speakOut`
  last.
- **Device Owner status being lost mid-session** (e.g. an OS update or factory-reset-adjacent
  event revokes it) while a silently-granted permission is mid-use, or while Sequence Mode /
  Kiosk mode is relying on Device Admin. Not something introduced this session, but not
  previously written down anywhere either - the app doesn't currently re-check `isDeviceOwnerApp`
  defensively at points beyond where it's already used.

## E. What Elene can actually perceive (she does not see the screen)

This isn't a paired "X while Y" scenario like the rest of this document, but it's a real,
standing architectural fact that explains *why* several of the entries above behave the way they
do, so it belongs here explicitly rather than staying implicit: **Elene has no visual perception
of the screen at all.** There is no screenshot capture, no OCR, no vision model anywhere in the
app. Every app-control action she can take goes through `ScifiAccessibilityService` reading the
Android Accessibility Tree - `findAccessibilityNodeInfosByText()`/`clickByText()` and the
Play-Store-card-description scraper (`collectPlayStoreCardDescriptions`) all match on the
*text, content-description, or resource-id* a UI element exposes to accessibility services, not
on anything resembling what a person looking at the screen would see. "System data," as put in
the correction that prompted this section, is the accurate description - not "sight."

This is worth naming plainly because it's easy to describe her actions in sighted-sounding
language ("she taps through the Continue-to-Chat screen," "she navigates to Settings") when the
actual mechanism is closer to "she searches a tree of labelled UI nodes for a string match and
clicks whichever one matches first." The distinction has real, concrete consequences already
visible elsewhere in this document and the codebase:

- The WhatsApp "Continue to Chat" tap-through (`SequenceMode.sendWhatsAppAlert`) works only
  because it polls for a fixed list of known candidate strings ("Continue to Chat," "CONTINUE TO
  CHAT," "Continue"). If WhatsApp ever renders that interstitial as an icon-only button, changes
  the wording, or the phone's language setting changes the label, the poll loop finds nothing to
  tap and the message silently never sends - with no visual fallback to catch it, because there's
  no visual layer underneath the text-matching to fall back to.
- Any future "tap this button for me" style command is bounded by the same limit: a button that's
  a bare icon with no `contentDescription` set by the app's own developer is functionally
  invisible to Elene, full stop - not "harder to find," actually absent from what she can query.
- The `[INTERNAL CONTEXT]` block sent to the backend (battery mode, theme, `last_message_text`,
  `in_meeting`, etc.) is the *entire* picture of device state she reasons over - deliberately
  curated, explicit key/value context, not an ambient sense of "what's currently on screen." If
  something isn't one of those explicit fields, she has no way to know about it, and no way to
  notice that she doesn't know about it either - a wrong or stale context value looks, from her
  side, identical to a correct one.

None of this is a bug to fix - it's the actual shape of the platform-provided accessibility API,
same category of honest limitation as the call-waiting platform limit in section A. It's recorded
here so future feature descriptions (in `full.md`, in conversation, anywhere) stay accurate to
"reads system/accessibility data" rather than drifting toward language that implies something
closer to seeing.

## What this document is not

Every entry above is something that could plausibly happen from ordinary use of a phone that
takes calls, records things, and runs a voice assistant at the same time - not a constructed
attack scenario. Genuinely exotic combinations (the kind covered in `planner/declined/
declined.md`'s "disproportionate threat model" entry) don't belong here; this document is about
correctness and UX under realistic concurrent use, not adversarial edge cases.
