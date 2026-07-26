# Not started / not even touched yet

## Voice ID - rest of Phase 3
- Wake-word engine ("Hey Imperial") - blocked on a real decision, see in_progress/
- AEC on the MAIN continuous-listening path (only Voice ID's own recording has it so far -
  the main path uses Android's SpeechRecognizer, which doesn't expose its internal AudioRecord)
- Bandpass filter (~300Hz-3.4kHz) before VAD to reject music/TV bass-treble
- Barge-in (interrupting Elene mid-sentence) logic
- Game-mode audio handling (USAGE_GAME, don't fight games for volume)

## Voice ID - Phase 4
- Weekly background re-enrollment for voice drift (partly superseded by the multi-sample pool
  from Phase 3 - may need less than originally planned, not evaluated yet)

## Anti-tampering / RASP hardening (queued after Voice ID)
- Layered Frida detection (native /proc/self/maps scan, port probe, thread-name scan, obfuscated
  strings, timing checks)
- Signature-level permission on exported components + runtime caller-signature verification
- Root/Magisk/SELinux-enforcing checks
- APK signature/integrity self-check (detect a repackaged/resigned copy)
- Self-pentest with Frida/Objection/Drozer/Burp/mitmproxy/apktool/MobSF against the app itself

## Explicitly declined, not going to happen
- Cross-app data exfiltration via accessibility/device admin
- Silent CA cert installation to intercept OTHER apps' traffic
- RAT/Meterpreter-style payloads, keylogging, uninstall-resistant persistence
(These aren't "not started" - they were refused. Listed here only so it's not silently confused
with a queued task.)
