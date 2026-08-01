# Done (built + installed, not all confirmed working yet - see experience/)

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
