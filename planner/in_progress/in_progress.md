# Working on right now

## Immediate: STOP and test before adding anything else
Per the user's explicit note (2026-07-26): before picking up anything new, the last batch of
changes needs real on-device confirmation, specifically:
1. Does your own voice now pass reliably with the multi-sample pool (not averaged)?
2. Does asking Elene something while music/video is playing actually pause it and answer?
3. Is the Recents lock loop actually gone, or just less frequent?
4. Does your sister's voice (or any non-enrolled voice) actually get refused for real commands now?

Nothing new should start until these four have a real answer logged in experience/.

## Blocked, needs a decision (not being worked on until resolved)
Wake-word engine ("Hey Imperial"): a real custom wake-word model needs training data (50+
recorded samples of the phrase) and a training run this environment cannot perform standalone -
it's not a pure coding task. Options to resolve, not yet chosen:
- Record the samples yourself and run training separately (on a computer with more compute),
  then hand the resulting model back to drop into the app
- Use a simpler text-based wake-phrase check via the STT already running (loses the "1% CPU,
  always-on, offline" efficiency property of a real wake-word model, but works today)
- Accept one of openWakeWord's existing pretrained words instead of a custom phrase
