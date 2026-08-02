# Not started / not even touched yet

*Deepened 2026-07-26. Each item below now includes what it actually involves technically, why it
hasn't started, what it depends on, and what decision (if any) is blocking it — not just a
one-line description. The goal is that a future session can read this and know exactly where to
pick up, without re-deriving the reasoning from scratch.*

## Voice ID — rest of Phase 3 (the real-time barge-in/wake-word rebuild)

These five items are not five independent tasks. They share one underlying prerequisite: right
now, general continuous listening runs through Android's built-in `SpeechRecognizer`, which is a
black box — it never hands back the raw `AudioRecord` stream it's reading from. Voice ID's own
recording path (`VoiceCapture.kt`) already owns its raw audio directly, which is exactly why AEC,
denoising, and VAD already work *there* and nowhere else. Everything below except the wake-word
model itself needs that same raw-ownership rebuild applied to the *main* listening path before it
can exist at all. That was an explicit discovery mid-planning, not assumed up front — it reshapes
the order this has to happen in: the raw-audio-pipeline rebuild is the real first task, and AEC,
the bandpass filter, and barge-in are really "things that become possible once that's done," not
separate line items.

- **Wake-word engine ("Hey Imperial")** — an always-on, ~1% CPU keyword spotter that listens
  24/7 for the wake phrase and only opens the main command pipeline after hearing it, going back to
  wake-word-only listening after 5 seconds of silence following a command. The intended engine is
  openWakeWord (TFLite, offline) — Porcupine was tried first and didn't work out for this project.
  This is genuinely blocked, not just unstarted: training a *custom* wake phrase needs real
  recorded samples of someone actually saying "Hey Imperial" (50+ takes) and a training run this
  coding environment cannot perform standalone — it needs either real compute elsewhere or a
  different scope. Three ways forward were identified and none has been chosen yet:
  1. Record the samples and run training on a separate machine with real compute, then drop the
     resulting model file into the app — full "Hey Imperial" experience, most effort.
  2. A simpler always-on text match against the STT stream instead of a real trained model — works
     today, but gives up the always-on/~1%-CPU/offline efficiency a real wake-word model has,
     since it means keeping the heavier speech recognizer running continuously instead of a tiny
     keyword spotter.
  3. Accept one of openWakeWord's existing pretrained wake words instead of a custom phrase — least
     effort, but "Hey Imperial" specifically wouldn't exist; whatever pretrained word is picked
     becomes the actual wake phrase.
- **AEC on the main continuous-listening path** — Voice ID's own recording already has a real
  `AcousticEchoCanceler` (see `VoiceCapture.kt`). The main listening path doesn't, because it isn't
  reading from a raw `AudioRecord` session at all right now — it's whatever `SpeechRecognizer`
  gives back. This is the first concrete piece of the raw-ownership rebuild: once the main path
  owns its own `AudioRecord`, this becomes a straightforward reuse of the same AEC wiring that
  already exists for Voice ID.
- **Bandpass filter (~300Hz–3.4kHz) before VAD** — human voice range only, specifically to stop
  music/TV bass and treble (which spans roughly 100Hz–8kHz) from being misread as speech by Silero
  VAD. This is a filtering stage that has to sit ahead of VAD in the raw audio pipeline, so it has
  the same raw-ownership dependency as AEC above — there's no owned audio stream to filter yet on
  the main path.
- **Barge-in (interrupting Elene mid-sentence)** — while Elene's TTS is playing, the mic should
  stay live (using AEC to subtract the speaker's own output from what the mic hears) instead of
  being muted, so the user can talk over a reply instead of waiting it out. This is the item that
  most directly needs the full raw-audio rebuild plus AEC plus a real understanding of when Elene's
  own voice should be excluded from triggering a new listen cycle — it's the last piece to build in
  this group, once the pipeline underneath it exists.
- **Game-mode audio handling** — while a game is in the foreground, media-ducking behavior should
  switch from the normal "pause/duck ordinary media" approach to something much lighter: duck to
  around 70% instead of 20%, and use `AudioAttributes.USAGE_GAME` so Android treats the audio
  differently from regular media playback. A companion rule from the original spec: if the CPU is
  maxed while gaming, pause voice verification rather than the game — voice processing should never
  be the thing that causes a frame drop. Push-to-talk (rather than always-on listening) is the
  preferred mode specifically during games. This one is more independent of the raw-audio rebuild
  than the others — it's really about *when/how aggressively* ducking happens, which can be
  decided once the basic AudioFocus request behavior (already built) is in place.

## Voice ID — Phase 4: weekly re-enrollment for voice drift

The original idea was a background job that periodically prompts for a fresh enrollment sample, to
catch long-term voice drift — a voice genuinely changing over months, not just day-to-day (tired,
sick, mild accent shift). That day-to-day variation is now largely already handled by the
multi-sample pool built in Phase 3 (`VoiceIdManager` matches against whichever of up to 12 stored
samples per style is closest, instead of one averaged reference vector), which is exactly why this
phase says "partly superseded" rather than being dropped outright. What's still genuinely
unevaluated: whether *real* long-term drift (a voice sounding meaningfully different a year from
now than it does today) is something the growing pool already absorbs on its own as new samples get
added through ordinary "Add voice samples" use, or whether it actually needs a dedicated periodic
nudge to keep the pool fresh. That's a question that can only be answered by watching real
acceptance/rejection behavior over months, not something to build blind. If it does turn out to be
needed, the shape is straightforward: a `WorkManager` periodic job (weekly), gated behind whether
enrollment exists at all, prompting a single top-up recording rather than a full 15-recording
re-enrollment.

## Anti-tampering / RASP hardening — built and confirmed 2026-07-31, see done.md/experience.md

The four detection/hardening pieces originally planned here (layered native Frida detection,
signature-level permission + runtime caller verification on exported components, root/Magisk/
SELinux checks, and the APK integrity self-check) are now built, installed, and confirmed
working live on the real device — see `planner/done/done.md` for the summary and
`planner/experience/experience.md` (2026-07-31 entries) for the full detail, including the real
signing-key/factory-reset detour and the Samsung Auto Blocker issue hit along the way. Only the
last piece of this section is still genuinely not started:

- **The self-pentest itself** — running Frida/Objection, Drozer, Burp Suite/mitmproxy, apktool, and
  MobSF against the finished hardening, on the real device, with real tooling, once the layers
  above exist. Each tool actually tests something different, which suggests a rough order: apktool
  and MobSF first (static analysis — what's visible just from decompiling the APK, before any
  runtime tricks are needed at all), then Frida/Objection (dynamic instrumentation — attempting to
  hook and bypass the checks above at runtime, which is exactly what the Frida-detection layer is
  meant to catch), then Burp Suite/mitmproxy (network interception — checking for any traffic that
  isn't properly protected, relevant to the Elene backend calls and the laptop-link WebSocket),
  then Drozer (exported-component/IPC surface testing — specifically exercising the signature-level
  permission work above by trying to invoke exported components from another, unsigned app). This
  is the step that actually proves whether the hardening pass above holds, rather than trusting it
  blindly — findings from earlier tools in that order should genuinely inform what the later ones
  focus on, not run as five disconnected checklist items.

## Updates screen Stage 2 — built 2026-08-02, see done.md/experience.md, not yet live-tested

Requested 2026-08-01, built 2026-08-02 - moved to `planner/done/done.md`. The section below is
kept as the original design record; see `planner/experience/experience.md`'s 2026-08-02 entry for
what was actually built, the real infrastructure problems hit along the way (repo had silently
moved to a new owner/name, the old GitHub PAT lacked permissions), and what's still not confirmed
(no real proposal has gone through the full chain end-to-end yet - blocked on the same GCP billing
issue as everything else pending this session). The Updates screen (`Security > Activity > Updates`, built 2026-07-27) is
currently Stage 1 only: a fingerprint-gated approval queue with no code-generation/build/deploy
pipeline behind an approved entry - proposals just sit there. This is the planned Stage 2: give
that pipeline a real body, without touching the fingerprint gate itself.

Explicitly scoped, after a direct question to the user about the one part that actually matters
(whether to remove fingerprint verification from self-updates, mirroring how remote auto-update
below already got flagged as too consequential to build from a bare spec) - the user's own answer
was to **keep** fingerprint verification, not remove it. So the real shape of this feature is:

- Triggered only by an explicit user request ("update yourself to do X") - never self-initiated,
  never triggered by anything Elene decides on her own. This isn't a new decision, it's already
  the existing propose_update prompt behavior (`backend/elene/main.py`) - Stage 2 just gives the
  proposal something real to do once approved instead of nothing.
- Once approved via fingerprint (the existing gate, unchanged), the actual work - writing the
  change, building it, and deploying it - happens in a **sandbox it creates**, and the AI verifies
  the change genuinely works there before it's considered done, rather than shipping on faith.
  What "sandbox" means concretely here still needs real design work before any code: for an app-
  side change this plausibly means a real build + install to a genuinely isolated test target
  (not simply overwriting the daily-driver install), and for a backend change plausibly means a
  separate Cloud Run revision/service tested directly before promoting traffic to it - not
  guessed at here, needs its own scoping pass.
- Real, honest gap this doesn't solve on its own: nothing in this project currently gives Elene
  (the on-device voice assistant) any code-generation, repository, build-toolchain, or device-
  deployment access at all - she's a chat/vision model wired to a fixed command set, nothing
  more. Making this real means either (a) this capability is actually a Claude-Code-operated
  pipeline that Elene's approved proposals *hand off to* rather than something Elene herself
  executes, or (b) a genuinely new subsystem gets built giving Elene real repo/build/deploy
  access, which is a much bigger, more consequential thing than what's been asked for so far and
  would need its own explicit conversation before being assumed. Worth resolving which of these
  is actually meant before writing any code, not assumed either way.

## Needs a dedicated security-design pass before any code — not declined, not started

Two more items came out of the 2026-07-26 planning documents that aren't refused (unlike
`planner/declined/declined.md` — these don't target anyone else's device or data) but are too
consequential and hard-to-reverse to build directly from a hypothetical spec. Both need an explicit,
scoped-down design conversation with the user before any implementation starts.

- **Remote auto-update.** As proposed (a server hosting APKs, the app periodically checking a
  manifest, verifying a signature, and silently installing via `PackageInstaller` with Device Owner
  privilege) this creates a genuinely new class of risk that doesn't exist anywhere else in the
  app: a channel that can push and silently run arbitrary new code with full device control. If the
  update server, its signing key, or the channel itself is ever compromised, this becomes the single
  most dangerous thing in the app - worse than anything the RASP hardening work is meant to defend
  against, because it's a legitimate, built-in path around the protections that hardening adds. The
  safe first version, if this is still wanted: check-and-notify only - verify a newer signed APK
  exists, tell the user, and let them tap through Android's own installer UI. No silent
  `PackageInstaller` call until there's been a real, dedicated threat-modeling pass on the update
  channel itself, done after the RASP/anti-tampering work, not before.
- **Phoenix Protocol (full detect-compromise → freeze → evacuate-to-server → wipe → restore-on-new-
  device flow).** The instinct behind it is right - don't lose everything if the phone has to be
  wiped - but the full version as specified creates a new, complete off-device copy of the most
  sensitive data this app holds (intruder photos, location history, Voice ID embeddings), which is
  itself now something that can leak independent of whether the phone is ever actually stolen. Some
  of its proposed detection triggers (recovery-mode-boot via boot-count change, "USB debugging
  enabled without user action") aren't reliably detectable on stock, non-rooted Android, meaning
  they'd be built without a way to verify they actually work. A much smaller, real version of the
  same idea is already close to buildable: Sequence Mode's existing 30-day wipe already happens: the
  worthwhile addition is a single encrypted upload of just the intruder photos and location history
  (not the whole app's data) to the backend that's already trusted, immediately before that
  already-planned wipe runs, gated behind the same explicit "Full-device wipe" opt-in toggle that
  already exists in Security. That's worth building. The full evacuate/resurrect/auto-restore-on-
  new-hardware flow is not, until it's been scoped down and explicitly confirmed the same way this
  paragraph just did.

## Explicitly declined — see the dedicated file

A separate, much deeper document now exists at `planner/declined/declined.md`, covering both the
older refusals (cross-app data exfiltration via accessibility/device admin, silent CA certificate
installation to intercept other apps' traffic, RAT/Meterpreter-style payloads, keylogging,
uninstall-resistant persistence) and everything declined from the 2026-07-26 "threat model"
document (an offensive local-network toolkit — packet injection/ARP spoofing, Bluetooth pairing
brute-force, deauthentication, crawling other apps' private storage for credentials — plus a couple
of engineering-tradeoff items like fixing an unconfirmed bug and building defenses for
implausible attack scenarios). Each entry there has its own reasoning and a 1–5 severity rating,
from "not yet, bring evidence" up to "hard no, this attacks other people's devices." These aren't
"not started" — they were turned down — and now live somewhere with room to actually explain why,
rather than as a bare bullet list here.
