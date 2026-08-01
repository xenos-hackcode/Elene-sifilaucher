# Real errors hit and fixed (evidence-based, not guessed)

- Recording toolbar collapsed to 79x275px - LinearLayout.generateDefaultLayoutParams() defaults
  VERTICAL-orientation children to MATCH_PARENT width, not WRAP_CONTENT. Fixed by explicit
  LayoutParams everywhere. Recurred once (collapsed chip view), same fix.
- `@Volatile` on a local variable - invalid, doesn't compile. Switched to AtomicBoolean.
- `Shizuku.newProcess()` is private in the installed library version - pivoted to the AIDL
  IUserService/bindUserService pattern instead.
- "Show taps" toggle was fully non-functional - confirmed via logcat
  (`Cannot change private secure settings`) as a genuine OS restriction, feature removed entirely
  rather than left broken.
- FRILL model crash: `Cannot copy from a TensorFlowLite tensor (Identity) with shape [7, 2048]
  to a Java object with shape [1, 2048]` - the model outputs one embedding per ~1s frame, not a
  single pooled vector. Fixed by mean-pooling frames dynamically (queried real output shape,
  didn't hardcode 7).
- Missing kissfft header (`kiss_fft_log.h` not found) on first native build - fetched the missing
  file from the real upstream repo.
- Android 15+ 16KB page-size compatibility warning - fixed for our own native library via a
  linker flag; NOT fixable for onnxruntime's prebuilt .so (confirmed active, unresolved upstream
  Microsoft issue as of this writing).
- RNNoise linker errors (`undefined symbol: rnnoise_create()` etc.) - the vendored rnnoise.h had
  no `extern "C"` guard, so the JNI file (C++) expected mangled names while denoise.c (plain C)
  exported unmangled ones. Fixed by adding the guard.
- Speex resampler compile error (`define either FIXED_POINT or FLOATING_POINT`) then link error
  (`RANDOM_PREFIX` unset) - both needed as explicit compile definitions for the resampler's
  standalone build mode.
- Digit-based anti-replay challenge ("say: seven three two") kept failing because Android's
  recognizer collapses spoken digit sequences into a single numeral token ("732") - the matcher
  was looking for separate word tokens in sequence. Fixed the matcher, then the whole
  digit-challenge approach was scrapped anyway (see experience/) in favor of a real-word
  challenge, then simplified further back to plain record+verify.
- Real, confirmed-on-device: Elene's cross-app floating bubble could stay visible ON TOP of the
  actual Android lock screen. Root cause: the bubble's show/hide logic ran entirely off
  `TYPE_WINDOW_STATE_CHANGED` accessibility events, and locking the screen via timeout or the
  power button (as opposed to switching to a different app) doesn't reliably fire that event for
  the keyguard on this OEM (Samsung One UI) - so the bubble just stayed in whatever state it was
  last set to. Confirmed via `adb shell dumpsys window | grep isKeyguardShowing` (true) alongside
  a `screencap` showing the bubble still on screen. Fixed by adding a `BroadcastReceiver` for
  `ACTION_SCREEN_OFF`/`ACTION_SCREEN_ON`/`ACTION_USER_PRESENT` as a second, independent path to
  the same `isKeyguardLocked()` check - these system broadcasts fire reliably regardless of
  accessibility-event quirks. Re-tested twice after the fix (lock via power button while an app
  was foreground, wake without unlocking, screenshot) - bubble stayed hidden both times.
