package com.example.scifilauncher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Lets Elene control whatever screen is currently in front - scroll, back, home, recents,
 * click things by name, highlight them, and read what's on screen - across any app, not
 * just this launcher. Enabled by the user once in Settings > Accessibility.
 */
class ScifiAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ScifiAccessibilityService? = null
        private const val TAB_WIDTH_DP = 22
        private const val TAB_HEIGHT_DP = 64
        private const val BUBBLE_SIZE_DP = 56

        // Hard safety backstop for the game auto-play loop - no server-side rate limit exists,
        // so these are the only thing preventing a runaway loop from silently burning API
        // credits/battery if something goes wrong or the user forgets it's running.
        private const val MAX_GAME_LOOP_ITERATIONS = 40
        private const val MAX_GAME_LOOP_DURATION_MS = 10 * 60_000L
        private const val GAME_LOOP_SETTLE_MS = 600L
    }

    private var highlightView: View? = null
    private var hideOverlayView: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenStateReceiver, filter)
            }
        }
        runCatching {
            val voipFilter = IntentFilter("com.example.scifilauncher.INCOMING_VOIP_CALL")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(voipCallReceiver, voipFilter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(voipCallReceiver, voipFilter)
            }
        }
        runCatching {
            // Manifest-declared PACKAGE_ADDED receivers are confirmed dead on this Android
            // build (see PackageInstallWatcher's doc comment) - registered dynamically here
            // instead, on this already-running service, so delivery doesn't depend on a
            // background cold-start the OS refuses to allow.
            val installFilter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
                addDataScheme("package")
            }
            // PACKAGE_ADDED is a protected system broadcast (confirmed - a spoofed local
            // broadcast attempt at it throws SecurityException even from adb shell), so
            // NOT_EXPORTED is safe and tighter here: protection is enforced by the OS refusing
            // anyone but itself to ever send it, not by this receiver's exported flag.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(installReceiver, installFilter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(installReceiver, installFilter)
            }
        }
        runCatching {
            // ScreenPerceptionService lives in the isolated :recorder process (different VM),
            // so frames can only be handed back here via broadcast, same shape as the
            // install-watch receiver above - both same-app, same-UID, NOT_EXPORTED is correct.
            val perceptionFilter = IntentFilter().apply {
                addAction(ScreenPerceptionService.ACTION_FRAME_READY)
                addAction(ScreenPerceptionService.ACTION_FRAME_ERROR)
                addAction(ScreenPerceptionService.ACTION_PROJECTION_STOPPED)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(perceptionReceiver, perceptionFilter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(perceptionReceiver, perceptionFilter)
            }
        }
        registerCallStateListener()
        scheduleWakeWordCheck(delayMillis = 4000L)
        MotionTheftDetector.register(this)
    }

    override fun onDestroy() {
        MotionTheftDetector.unregister(this)
        clearHighlight()
        removeHideOverlay()
        hideBubble()
        runCatching { speechRecognizer?.destroy() }
        runCatching { bubbleTts?.shutdown() }
        runCatching { unregisterReceiver(screenStateReceiver) }
        runCatching { unregisterReceiver(voipCallReceiver) }
        runCatching { unregisterReceiver(installReceiver) }
        runCatching { unregisterReceiver(perceptionReceiver) }
        bubbleHandler.removeCallbacks(wakeWordCheckRunnable)
        if (gameLoopActive) runCatching {
            startService(Intent(this, ScreenPerceptionService::class.java).setAction(ScreenPerceptionService.ACTION_STOP_CAPTURE))
        }
        unregisterCallStateListener()
        bubbleServiceScope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // VoIP calls (WhatsApp/etc.) never touch TelephonyManager, so this is a separate signal
    // from the cellular CALL_STATE_RINGING path below - fired by XenosNotificationListener when
    // it sees a CATEGORY_CALL notification.
    private val voipCallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val callerName = intent?.getStringExtra("callerName")
            val appName = intent?.getStringExtra("appName")
            wakeScreenBriefly(this@ScifiAccessibilityService)
            pendingForceListenForCall = true
            announceIncomingCall(
                if (!callerName.isNullOrBlank()) "Incoming call from $callerName." else "Incoming call${if (!appName.isNullOrBlank()) " on $appName" else ""}."
            )
        }
    }

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context == null || intent == null) return
            PackageInstallWatcher.handle(context, intent)
        }
    }

    // ---- Call awareness: announce who's calling, answer only on explicit "pick it up" ----
    // Never auto-answers - registering this listener only ever leads to speaking an
    // announcement; attemptAnswerCall() (CallAwareness.kt) is only ever invoked from the
    // "answer_call" verb, which only exists because the user explicitly said so.
    private var lastAnnouncedRingingNumber: String? = null
    private var telephonyCallback: Any? = null // TelephonyCallback on API 31+, else null
    @Suppress("DEPRECATION")
    private var phoneStateListener: android.telephony.PhoneStateListener? = null

    private fun onCallRinging(incomingNumber: String?) {
        // TYPE_WINDOW_STATE_CHANGED-style event settling isn't relevant here (this isn't an
        // accessibility event), but the underlying telephony stack can report RINGING more than
        // once for the same call - only announce once per distinct incoming number/ring.
        if (incomingNumber != null && incomingNumber == lastAnnouncedRingingNumber) return
        lastAnnouncedRingingNumber = incomingNumber
        wakeScreenBriefly(this)
        pendingForceListenForCall = true
        val name = incomingNumber?.let { runCatching { reverseLookupContactName(this, it) }.getOrNull() }
        announceIncomingCall(
            when {
                !name.isNullOrBlank() -> "Incoming call from $name."
                !incomingNumber.isNullOrBlank() -> "Incoming call from an unknown number."
                else -> "Incoming call."
            }
        )
    }

    /** Confirmed real gap (found from a user-described scenario, not assumed): if Elene is
     * actively mid-listening when a call rings, announcing over that would fight the still-open
     * SpeechRecognizer for the mic, and could plausibly get its own announcement audio picked up
     * as if it were something the user said. Cleanly stops the current recognition session
     * first - not the same as stopListening(), which sets listeningStopped permanently; this is
     * a pause, and the announcement's own completion still loops back into normal listening
     * afterward via retryListeningSoon(), same as any other turn ending. Also mentions if a
     * voice memo is currently recording, since a call arriving mid-memo is easy to forget about
     * otherwise. */
    private fun announceIncomingCall(baseText: String) {
        if (speechRecognizer != null) {
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
        }
        val text = if (VoiceMemoService.isRecording) {
            "$baseText You're still recording a voice memo."
        } else {
            baseText
        }
        runCatching { speakOut(text) }
    }

    private fun onCallIdle() {
        lastAnnouncedRingingNumber = null
    }

    /** Confirmed real gap (found from a user-described scenario, not assumed): a voice memo's
     * MediaRecorder and an active phone call both want AudioSource.MIC - nothing previously
     * stopped them from overlapping if a call got answered (by the user tapping normally, not
     * necessarily through Elene) while VoiceMemoService was running. Stops the memo the moment
     * the call actually goes active (not just rings, since ringing alone doesn't take the mic),
     * rather than leaving a corrupted/silent recording. */
    private fun onCallActive() {
        if (VoiceMemoService.isRecording) {
            VoiceMemoService.stop(this)
            runCatching { speakOut("Stopped your voice memo - a call just started.") }
        }
    }

    private fun registerCallStateListener() {
        runCatching {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return

            val telephonyManager = getSystemService(TELEPHONY_SERVICE) as? android.telephony.TelephonyManager ?: return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val callback = object : android.telephony.TelephonyCallback(), android.telephony.TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        // API 31+'s TelephonyCallback.CallStateListener doesn't carry the
                        // incoming number directly - fetched separately via CallStateUtil's
                        // ringing-number helper is unavailable, so this path announces without
                        // caller ID unless a richer API is added later; the pre-31 path below
                        // does carry the number.
                        if (state == android.telephony.TelephonyManager.CALL_STATE_RINGING) {
                            onCallRinging(null)
                        } else if (state == android.telephony.TelephonyManager.CALL_STATE_OFFHOOK) {
                            onCallActive()
                        } else if (state == android.telephony.TelephonyManager.CALL_STATE_IDLE) {
                            onCallIdle()
                        }
                    }
                }
                telephonyManager.registerTelephonyCallback(mainExecutor, callback)
                telephonyCallback = callback
            } else {
                @Suppress("DEPRECATION")
                val listener = object : android.telephony.PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        if (state == android.telephony.TelephonyManager.CALL_STATE_RINGING) {
                            onCallRinging(phoneNumber)
                        } else if (state == android.telephony.TelephonyManager.CALL_STATE_OFFHOOK) {
                            onCallActive()
                        } else if (state == android.telephony.TelephonyManager.CALL_STATE_IDLE) {
                            onCallIdle()
                        }
                    }
                }
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
                phoneStateListener = listener
            }
        }
    }

    private fun unregisterCallStateListener() {
        runCatching {
            val telephonyManager = getSystemService(TELEPHONY_SERVICE) as? android.telephony.TelephonyManager ?: return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                (telephonyCallback as? android.telephony.TelephonyCallback)?.let { telephonyManager.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                phoneStateListener?.let { telephonyManager.listen(it, android.telephony.PhoneStateListener.LISTEN_NONE) }
            }
        }
        telephonyCallback = null
        phoneStateListener = null
    }

    // Confirmed real gap (found via on-device testing, not assumed): locking the screen via
    // timeout or the power button - rather than switching to a new app - doesn't reliably fire a
    // TYPE_WINDOW_STATE_CHANGED accessibility event for the keyguard on this OEM, so the bubble
    // could stay showing (over whatever app was foreground when it locked) with no accessibility
    // event ever arriving to trigger a re-check. ACTION_SCREEN_OFF/ON are real system broadcasts
    // that fire reliably regardless of accessibility-event quirks, so they're used as a second,
    // independent path to the same keyguard check - belt and suspenders, since this bubble
    // showing on a genuinely locked phone is a real lock-screen bypass, not a cosmetic bug.
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Screen is off now; whatever comes back (keyguard or not) gets decided
                    // fresh on SCREEN_ON/USER_PRESENT - hide immediately in the meantime so
                    // there's no window where the bubble is composited over a soon-to-be-locked
                    // screen.
                    bubbleHandler.removeCallbacks(visibilityRunnable)
                    hideBubble()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    checkBubbleVisibilityNow()
                }
            }
        }
    }

    // ---- Cross-app Elene bubble ----
    // The launcher already has its own Elene bubble baked into its home screen UI - showing
    // this overlay ON TOP of that too would just be a second bubble for no reason, so it's
    // only shown while some OTHER app is in front.
    private var bubbleView: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var lastForegroundPkg: String? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var mediaFocusHandle: Any? = null
    private var bubbleTts: TextToSpeech? = null
    private val bubbleHandler = Handler(Looper.getMainLooper())
    private val bubbleServiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // A permission-confirmation dialog, a toast, or a brief system window can each fire their
    // own TYPE_WINDOW_STATE_CHANGED with confusing package attribution in quick succession -
    // reacting to every single one caused the bubble to visibly pop in and immediately vanish.
    // Waiting for events to settle before actually committing to show/hide fixes that.
    private var pendingVisibilityPkg: String? = null
    private var wasLocked = false

    // Package-name matching alone isn't a reliable way to detect the lock screen - the
    // keyguard's exact package varies by OEM/Android version, so relying on it (e.g. just
    // excluding "com.android.systemui") can miss cases entirely, and a floating bubble
    // that lets you talk to Elene - who can open apps, click things on screen, etc. -
    // showing up ON a locked phone would be a real bypass of the lock screen, not a
    // cosmetic issue. KeyguardManager.isKeyguardLocked() is the actual, OEM-independent
    // signal for "is this phone currently locked", checked fresh right before acting.
    private fun checkBubbleVisibilityNow() {
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val isLocked = keyguardManager?.isKeyguardLocked == true
        wasLocked = isLocked
        // Used to also hide on our own launcher (pkg == packageName) so the launcher's own
        // separate bubble wouldn't sit on screen next to this one - that bubble is gone now,
        // this is the only one, so it belongs on the home screen too.
        if (isLocked) hideBubble() else showBubble()
    }

    private val visibilityRunnable = Runnable {
        pendingVisibilityPkg ?: return@Runnable
        checkBubbleVisibilityNow()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // The lock screen is hosted by com.android.systemui on stock Android (may vary by
        // OEM) - it must NOT be filtered out here the way other systemui flicker (status bar
        // expand, etc.) is, or a screen lock would never re-trigger the visibility check at
        // all, leaving the bubble showing in whatever state it was already in. Only skip
        // updating "last real foreground app" for systemui - still let it re-run the check,
        // which re-reads isKeyguardLocked() fresh every time regardless of what triggered it.
        if (pkg != "com.android.systemui") {
            // Unlocking back into the SAME app that was foreground when it locked reports the
            // same package again - without the wasLocked check, that'd look like "nothing
            // changed" and the bubble would stay hidden even after unlocking.
            if (pkg == lastForegroundPkg && !wasLocked) return
            lastForegroundPkg = pkg
            pendingVisibilityPkg = pkg

            // The target app relaunched after MediaProjection consent is now actually back in
            // front - safe to request the first frame. Only fires once per pending capture
            // (awaitingCaptureRelaunch is consumed immediately) so later, unrelated window
            // changes don't re-trigger it.
            if (awaitingCaptureRelaunch && pkg == pendingCaptureTargetPkg) {
                awaitingCaptureRelaunch = false
                bubbleHandler.postDelayed({ requestFrame() }, 500L)
            }
            // Confirmed real risk, not hypothetical: a play-loop that keeps tapping into
            // whatever app you've switched to (a call answered, Home pressed, another app
            // opened) would be actively harmful, not just wrong. Auto-stop rides this exact
            // event stream already driving the cross-app bubble, same debounce window.
            if (gameLoopActive && pkg != gameLoopTargetPkg) {
                stopGameLoop("Stopped playing - looks like you switched apps.")
            }
        }
        bubbleHandler.removeCallbacks(visibilityRunnable)
        bubbleHandler.postDelayed(visibilityRunnable, 450L)
    }

    private fun currentThemeColorArgb(): Int {
        val idx = getSharedPreferences("theme_prefs", MODE_PRIVATE).getInt("theme_index", 0)
        return CedalThemes[idx % CedalThemes.size].primary.let {
            android.graphics.Color.argb(
                (it.alpha * 255).toInt(), (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()
            )
        }
    }

    private fun showBubble() {
        if (bubbleView != null) return
        if (!Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val tabW = (TAB_WIDTH_DP * density).toInt()
        val tabH = (TAB_HEIGHT_DP * density).toInt()
        val view = BubbleView(this, currentThemeColorArgb())
        val params = WindowManager.LayoutParams(
            tabW, tabH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = resources.displayMetrics.heightPixels / 3
        }
        view.setOnTouchListener(BubbleTouchListener(
            onDrag = { dx, dy ->
                params.x += dx
                params.y += dy
                runCatching { wm.updateViewLayout(view, params) }
            },
            onTap = { onBubbleTapped() }
        ))
        runCatching { wm.addView(view, params) }.onSuccess {
            bubbleView = view
            bubbleParams = params
        }
    }

    /** Swaps between the launcher's two real Elene looks: a small edge tab while dormant, a
     * black circle with a state-colored ring while listening/replying - resizing the overlay
     * window itself to match, not just redrawing within a fixed box. */
    private fun setBubbleState(newState: EleneBubbleState) {
        if (newState == EleneBubbleState.DORMANT && mediaFocusHandle != null) {
            // Elene's genuinely done for now (not just between one turn and the next retry) -
            // let go of the mic's audio focus so paused media can resume on its own.
            releaseAudioFocus(this, mediaFocusHandle)
            mediaFocusHandle = null
        }
        val view = bubbleView ?: return
        val params = bubbleParams ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val wasIdle = view.state == EleneBubbleState.DORMANT
        val willBeIdle = newState == EleneBubbleState.DORMANT
        view.state = newState
        if (wasIdle != willBeIdle) {
            val density = resources.displayMetrics.density
            if (willBeIdle) {
                params.width = (TAB_WIDTH_DP * density).toInt()
                params.height = (TAB_HEIGHT_DP * density).toInt()
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            } else {
                val size = (BUBBLE_SIZE_DP * density).toInt()
                params.width = size
                params.height = size
                // Screen shouldn't dim/lock while actively talking to Elene - held only for as
                // long as this overlay is genuinely listening/replying, cleared the instant it
                // goes back to dormant above, not a standing keep-awake.
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            }
            runCatching { wm.updateViewLayout(view, params) }
        }
    }

    private fun hideBubble() {
        val view = bubbleView ?: return
        bubbleView = null
        bubbleParams = null
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) }
        runCatching { speechRecognizer?.cancel() }
    }

    private fun onBubbleTapped() {
        if (bubbleView?.state == EleneBubbleState.LISTENING) return
        startListening()
    }

    // Real, confirmed-via-logs bug: speakOut() kicks off TTS/audio playback asynchronously and
    // returns immediately - the commands array (which may include stop_listening) then runs
    // essentially in parallel with that still-playing audio, not after it. So the sequence was:
    // stop_listening runs (mic closed) -> a few seconds later the EARLIER reply's audio finally
    // finishes playing -> its completion handler unconditionally calls retryListeningSoon(0),
    // silently reopening the mic and undoing the stop. Confirmed in logcat: "open whatsapp then
    // stop listening" correctly ran both commands, but the still-playing "Opening WhatsApp then
    // standing down" reply's completion reopened listening moments later. This flag is set the
    // instant an explicit stop happens and checked by every resume path, so a stale completion
    // callback from an utterance that started before the stop can never override it.
    @Volatile private var listeningStopped = false

    // Continuous listening: the bubble only ever goes dormant when "stop listening" is heard
    // (see handleSpokenText) - every other exit (no speech, recognizer error, backend
    // unreachable, reply finished speaking) loops back into listening instead of closing.
    //
    // [isWakeWordCheck] - true for a passive "Hey Elene" always-listening cycle (see
    // scheduleWakeWordCheck below): a real SpeechRecognizer session still runs (Android's API
    // has no separate low-power wake-word primitive - see the honest caveat in
    // SettingsScreen's info dialog about the real battery cost of this approach), but the
    // bubble stays visually dormant (this isn't an engaged turn yet) and the result is checked
    // for the wake phrase instead of being dispatched as a real command.
    private fun startListening(isWakeWordCheck: Boolean = false) {
        listeningStopped = false
        // Confirmed real gap (found via user-reported edge case, not assumed): the bubble
        // correctly hides itself when the keyguard is up (checkBubbleVisibilityNow), but that's
        // a SEPARATE mechanism from this function - nothing previously stopped startListening()
        // itself from being called and opening a live mic session while the phone is locked, as
        // long as SOMETHING triggered it (e.g. the incoming-call announcement's own completion
        // callback looping back into retryListeningSoon -> startListening). That's the same
        // class of lock-screen bypass as the bubble-visibility bug already fixed once - closed
        // here at the actual source instead of auditing every caller that could reach this.
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (keyguardManager?.isKeyguardLocked == true) {
            setBubbleState(EleneBubbleState.DORMANT)
            if (isWakeWordCheck) scheduleWakeWordCheck()
            return
        }
        // Calls are still an absolute block - never grab the mic mid-call, no exceptions.
        if (isPhoneBusyWithCall(this)) {
            setBubbleState(EleneBubbleState.DORMANT)
            if (isWakeWordCheck) scheduleWakeWordCheck()
            return
        }
        // Real bug found live: the MediaProjection consent dialog (and this OEM's audible
        // "screen sharing started" confirmation once granted) was getting picked up by a mic
        // that reopened during that window and misread as a command. See
        // haltListeningForCapture() - held closed for the whole describe_screen/play_game
        // bootstrap, not just the instant it's triggered.
        if (awaitingCaptureConsent) {
            setBubbleState(EleneBubbleState.DORMANT)
            if (isWakeWordCheck) scheduleWakeWordCheck()
            return
        }
        // Media playing used to also go straight to dormant (the same "don't misread the movie
        // dialogue as a command" concern as a call) - but that meant Elene simply couldn't be
        // used at all while music/video was playing. Taking real AUDIOFOCUS_GAIN instead
        // actually pauses well-behaved media apps for the turn (same as any real assistant),
        // which solves both problems at once: you get an answer, and there's no competing audio
        // left to misread once it's paused. Skipped for a passive wake-word check - not worth
        // pausing the user's music every few seconds just to listen for a wake phrase that
        // usually won't be there.
        if (isMediaPlaying(this) && mediaFocusHandle == null && !isWakeWordCheck) {
            mediaFocusHandle = requestAudioFocus(this, transient = false)
        }
        setBubbleState(if (isWakeWordCheck) EleneBubbleState.DORMANT else EleneBubbleState.LISTENING)
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            if (isWakeWordCheck) {
                scheduleWakeWordCheck()
            } else {
                setBubbleState(EleneBubbleState.UNRESPONSIVE)
                retryListeningSoon()
            }
            return
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        val recIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                runCatching { recognizer.destroy() }
                speechRecognizer = null
                if (isWakeWordCheck) {
                    if (!text.isNullOrBlank() && containsWakeWord(text)) {
                        // The wake phrase itself was just consumed - open a REAL turn now,
                        // same as a bubble tap, for the actual command that follows.
                        startListening(isWakeWordCheck = false)
                    } else {
                        scheduleWakeWordCheck()
                    }
                    return
                }
                if (text.isNullOrBlank()) {
                    setBubbleState(EleneBubbleState.UNRESPONSIVE)
                    retryListeningSoon()
                } else {
                    handleSpokenText(text)
                }
            }

            override fun onError(error: Int) {
                android.util.Log.d("EleneBubble", "SpeechRecognizer error code=$error")
                runCatching { recognizer.destroy() }
                speechRecognizer = null
                if (isWakeWordCheck) {
                    scheduleWakeWordCheck()
                    return
                }
                setBubbleState(EleneBubbleState.UNRESPONSIVE)
                retryListeningSoon()
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        runCatching { recognizer.startListening(recIntent) }
    }

    private fun continuousListeningEnabled(): Boolean =
        getSharedPreferences("theme_prefs", MODE_PRIVATE).getBoolean("elene_continuous_listening", true)

    // ---- Always listening ("Hey Elene") ----
    // Honest limitation, disclosed in SettingsScreen's own info dialog too: Android has no
    // separate low-power wake-word primitive exposed to apps - this is real SpeechRecognizer
    // sessions run back-to-back on a timer, which costs real battery, unlike a purpose-built
    // wake-word engine (e.g. Picovoice Porcupine) would. Explicitly disabled during battery
    // saver, per the user's own requirement - checked fresh on every cycle, not just once.
    private fun alwaysListeningEnabled(): Boolean =
        getSharedPreferences("theme_prefs", MODE_PRIVATE).getBoolean("elene_always_listening", false)

    private fun containsWakeWord(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return listOf("hey elene", "hey elena", "ok elene", "okay elene", "elene").any { normalized.contains(it) }
    }

    private val wakeWordCheckRunnable = Runnable { runWakeWordCycleIfEligible() }

    private fun runWakeWordCycleIfEligible() {
        if (!alwaysListeningEnabled()) return
        if (speechRecognizer != null) {
            // Something else already has the mic (a real conversation, a call announcement,
            // etc.) - don't compete with it, just check again later.
            scheduleWakeWordCheck()
            return
        }
        if (bubbleView?.state != EleneBubbleState.DORMANT) {
            scheduleWakeWordCheck()
            return
        }
        val batteryPrefs = getSharedPreferences("battery_prefs", MODE_PRIVATE)
        if (loadBatterySaverMode(batteryPrefs) != BatterySaverMode.OFF) {
            // Battery saver is on - stay off the mic entirely, but keep checking on the same
            // schedule so this resumes automatically the moment it's turned back off, with no
            // separate re-enable step needed.
            scheduleWakeWordCheck()
            return
        }
        startListening(isWakeWordCheck = true)
    }

    private fun scheduleWakeWordCheck(delayMillis: Long = 4000L) {
        bubbleHandler.removeCallbacks(wakeWordCheckRunnable)
        bubbleHandler.postDelayed(wakeWordCheckRunnable, delayMillis)
    }

    @Volatile private var voiceIdCheckInProgress = false

    // Set by the "start_recording" verb, consumed here - retryListeningSoon() is already the
    // one place every "Elene just finished (or gave up on) speaking" path in this file funnels
    // through (speechDoneListener, playOverlayAudioBytes completion/error, speakOut's
    // can't-speak case), so it doubles as the correct "safe to actually start the mic now" hook
    // without needing to thread a new completion callback through the whole TTS plumbing.
    private var pendingVoiceMemoStart = false

    // Confirmed real bug the user hit live: with continuous listening off (or after an earlier
    // "stop listening"), the incoming-call announcement finished speaking and then just went
    // dormant - there was no listening window at all for "pick it up" to ever be heard, making
    // the whole point of call awareness (hands-free answer) unusable outside an active
    // conversation. A ringing call is time-critical (it stops ringing / goes to voicemail
    // within seconds) and was explicitly asked to be answerable this way, so this one-shot flag
    // forces exactly one real listening window after the announcement, bypassing BOTH
    // listeningStopped and the continuous-listening setting - startListening()'s own safety
    // checks (keyguard, an already-answered call) still apply underneath it regardless.
    private var pendingForceListenForCall = false

    private fun retryListeningSoon(delayMillis: Long = 900L) {
        if (pendingVoiceMemoStart) {
            pendingVoiceMemoStart = false
            VoiceMemoService.start(this@ScifiAccessibilityService)
        }
        // This turn's actual work (listening, replying, running commands) just wrapped up -
        // let go of media focus now rather than holding it for the rest of a continuous-
        // listening session that might not hear anything else for a while. startListening()
        // re-acquires it immediately if media's still playing when the next turn actually
        // begins, so this just avoids pausing music indefinitely after a single command.
        if (mediaFocusHandle != null) {
            releaseAudioFocus(this, mediaFocusHandle)
            mediaFocusHandle = null
        }
        if (pendingForceListenForCall) {
            pendingForceListenForCall = false
            bubbleHandler.postDelayed({ startListening() }, delayMillis)
            return
        }
        if (listeningStopped) {
            // A stale callback from an utterance that started before an explicit stop already
            // happened - don't resume behind the user's back.
            return
        }
        if (!continuousListeningEnabled()) {
            setBubbleState(EleneBubbleState.DORMANT)
            return
        }
        bubbleHandler.postDelayed({
            // A per-command Voice ID check (see handleSpokenText) is mid-recording via its own
            // AudioRecord - starting SpeechRecognizer on top of that would fight it for the mic.
            // Push the resume back instead of starting a real listening session into that.
            if (voiceIdCheckInProgress) {
                retryListeningSoon(300L)
            } else {
                startListening()
            }
        }, delayMillis)
    }

    private fun stopListening() {
        listeningStopped = true
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        setBubbleState(EleneBubbleState.DORMANT)
    }

    private fun handleSpokenText(text: String) {
        android.util.Log.d("EleneBubble", "Heard: \"$text\"")
        if (isStopListeningPhrase(text)) {
            stopListening()
            return
        }
        setBubbleState(EleneBubbleState.REPLYING)
        bubbleServiceScope.launch {
            // Same user id as the home-screen assistant - was "device_user" here vs
            // "launcher-user" there, meaning they had two entirely separate conversation
            // histories on the backend. Also, this previously sent NO context at all (no
            // screen_text) - the backend had zero visibility into what's on screen when a
            // command came from in-app, which is exactly why "click close" and similar
            // requests couldn't reliably work from here.
            val screenText = describeScreen()
            val avoidTopics = loadAvoidTopics(getSharedPreferences("elene_memory_prefs", MODE_PRIVATE))
            // Elene could never actually answer "what apps do I have" or "which game do I play"
            // - not because it's unable to, but because the backend was never told what's
            // installed at all, only what's on screen right now. installed_apps is the real
            // list; recently_opened_apps (from real last-opened timestamps, most recent first)
            // is an honest recency-based proxy for "which do you use most" - not real play-time
            // tracking, so the prompt is told to describe it that way rather than overclaim.
            val installedApps = runCatching { AppResolver.findInstalledApps(this@ScifiAccessibilityService) }.getOrDefault(emptyList())
            val recentlyOpened = runCatching {
                val prefs = getSharedPreferences("last_opened_prefs", MODE_PRIVATE)
                val labelByPkg = installedApps.associate { it.packageName to it.label }
                prefs.all.entries
                    .mapNotNull { (pkg, ts) -> (ts as? Long)?.let { pkg to it } }
                    .sortedByDescending { it.second }
                    .take(8)
                    .mapNotNull { (pkg, _) -> labelByPkg[pkg] }
            }.getOrDefault(emptyList())
            val weatherDescription = runCatching {
                val lockPrefs = getSharedPreferences("lock_prefs", MODE_PRIVATE)
                // Don't just hope some other feature already populated the cache (Sequence
                // Mode arm, an Intruder capture, the 15-min periodic LocationHistoryWorker) -
                // a fresh install/reset has nothing cached yet, so actively request a
                // best-effort fix right here too (cheap - reads whatever fix providers already
                // have, doesn't wait on a fresh GPS lock).
                captureLastLocation(this@ScifiAccessibilityService, lockPrefs)
                val loc = loadLastKnownLocation(lockPrefs)
                    ?: requestAndCacheFreshLocation(this@ScifiAccessibilityService, lockPrefs)
                loc?.let { (lat, lng, _) ->
                    WeatherClient.currentWeatherDescription(lat, lng)
                }
            }.getOrNull()
            val ctxMap = buildMap {
                if (screenText.isNotBlank()) put("screen_text", screenText)
                if (!weatherDescription.isNullOrBlank()) put("current_weather", weatherDescription)
                if (avoidTopics.isNotEmpty()) put("avoid_topics", avoidTopics.joinToString(", "))
                val rememberedFacts = RememberedFactLog.asContextString(this@ScifiAccessibilityService)
                if (rememberedFacts.isNotBlank()) put("remembered_facts", rememberedFacts)
                if (installedApps.isNotEmpty()) put("installed_apps", installedApps.joinToString(", ") { it.label }.take(1500))
                if (recentlyOpened.isNotEmpty()) put("recently_opened_apps", recentlyOpened.joinToString(", "))
                // So Elene can honestly answer "do you recognize my voice" instead of denying a
                // real feature it has - was previously invisible to the backend entirely.
                put("voice_id_status", if (VoiceIdManager.isEnrolled(this@ScifiAccessibilityService)) "enrolled" else "not_enrolled")
                XenosNotificationListener.lastMessageInfo?.let { lastMsg ->
                    put("last_message_sender", lastMsg.title)
                    put("last_message_app", lastMsg.appName)
                    put("last_message_text", lastMsg.text)
                }
                currentMeeting(this@ScifiAccessibilityService)?.let { meeting ->
                    put("in_meeting", "true")
                    put("current_meeting_title", meeting.title)
                }
            }
            val response = EleneApiClient.sendText(userId = "launcher-user", text = text, context = ctxMap)
            android.util.Log.d("EleneBubble", "Response: reply=\"${response?.reply}\" commands=${response?.commands} intent=\"${response?.intent}\"")
            if (response == null) {
                speakOut("I couldn't reach my backend just now.")
                return@launch
            }
            // Backend occasionally repeats the same command back-to-back for one simple
            // request (seen as a single spoken instruction getting acted on - or misfiring the
            // "can't do that" fallback line - more than once). Collapsing exact consecutive
            // duplicates is a safe, local guard regardless of why the duplicate happened.
            val dedupedCommands = response.commands.filterIndexed { i, cmd -> i == 0 || cmd != response.commands[i - 1] }
            val reply = response.reply

            if (dedupedCommands.isEmpty()) {
                // Pure conversation, nothing actionable - answer regardless of who's speaking,
                // same as before. The gate below is specifically for real device actions.
                if (!reply.isNullOrBlank()) speakOut(reply) else retryListeningSoon(0)
                return@launch
            }

            // Commands are real actions - "open WhatsApp" used to run for anyone's voice
            // because this only ever logged a score afterward, never actually gated. Now it's
            // checked BEFORE anything runs, which is a real, deliberate added latency (a fresh
            // recording has to complete first) - accepted in exchange for it actually meaning
            // something.
            if (!voiceIdAllowsCommand(text)) {
                speakOut("Voice unrecognized.")
                return@launch
            }

            if (!reply.isNullOrBlank()) speakOut(reply)
            dedupedCommands.forEachIndexed { index, cmd ->
                handleOverlayCommand(cmd)
                if (index != dedupedCommands.lastIndex) kotlinx.coroutines.delay(350L)
            }
            if (reply.isNullOrBlank()) retryListeningSoon(0)
        }
    }

    /** The real gate for voice-triggered actions - returns true if Voice ID isn't enrolled
     * (nothing to check) or the enrolled voice matches; false only on a genuine, confident
     * mismatch. A failed recording (mic busy, permission hiccup) fails OPEN rather than
     * blocking a command over a capture problem that has nothing to do with who's speaking. */
    private suspend fun voiceIdAllowsCommand(heard: String): Boolean {
        if (!VoiceIdManager.isEnrolled(this@ScifiAccessibilityService)) return true
        voiceIdCheckInProgress = true
        try {
            val sample = recordVoiceSample(this@ScifiAccessibilityService) ?: return true
            val best = VoiceIdManager.verifyBest(this@ScifiAccessibilityService, sample) ?: return true
            val (style, score) = best
            android.util.Log.d("EleneVoiceID", "heard=\"$heard\" style=${style.name} similarity=$score")
            val passed = score >= style.threshold
            VoiceIdConfidenceLog.record(this@ScifiAccessibilityService, style, score, passed)
            return passed
        } finally {
            voiceIdCheckInProgress = false
        }
    }

    private val speechDoneListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {
            // Same acoustic-settle reasoning as playOverlayAudioBytes's completion listener -
            // real speech just played through the speaker, give it a moment before the mic
            // goes live again.
            bubbleHandler.post { retryListeningSoon(500L) }
        }
        @Deprecated("Deprecated in Java", ReplaceWith(""))
        override fun onError(utteranceId: String?) {
            bubbleHandler.post { retryListeningSoon(500L) }
        }
    }

    /** Standalone announcement (e.g. MotionTheftDetector's "are you running?" alert) - same real
     * TTS pipeline as speakOut() below (ElevenLabs first, on-device fallback), but deliberately
     * not calling into speakOut() itself, since that function is tightly coupled to the
     * conversational bubble's own listening state machine (it calls retryListeningSoon() on
     * failure) and this needs to work as a one-off system alert outside any conversation turn. */
    fun speakElene(text: String) {
        bubbleServiceScope.launch {
            val audio = runCatching { EleneApiClient.fetchTtsAudio(text) }.getOrNull()
            val played = if (audio != null) playOverlayAudioBytes(audio) else false
            if (!played) {
                val tts = bubbleTts ?: TextToSpeech(this@ScifiAccessibilityService) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        bubbleTts?.speak(text, TextToSpeech.QUEUE_ADD, null, "elene_motion_alert")
                    }
                }.also { bubbleTts = it }
                if (tts.isLanguageAvailable(java.util.Locale.getDefault()) >= TextToSpeech.LANG_AVAILABLE) {
                    tts.speak(text, TextToSpeech.QUEUE_ADD, null, "elene_motion_alert")
                }
            }
        }
    }

    /** Was plain on-device TextToSpeech only - a different voice than the home-screen
     * assistant's ElevenLabs voice, even though it's meant to be the same Elene. Now tries the
     * same ElevenLabs fetch first and only falls back to the system voice if that's
     * unreachable, matching MainActivity.speakWithCompletion. */
    private fun speakOut(text: String) {
        val themePrefs = getSharedPreferences("theme_prefs", MODE_PRIVATE)
        val batteryPrefs = getSharedPreferences("battery_prefs", MODE_PRIVATE)
        val eleneVoiceOn = themePrefs.getBoolean("elene_voice_on", true)
        val canSpeak = eleneVoiceOn && loadBatterySaverMode(batteryPrefs) == BatterySaverMode.OFF
        if (!canSpeak) {
            retryListeningSoon(0)
            return
        }
        bubbleServiceScope.launch {
            val audio = runCatching { EleneApiClient.fetchTtsAudio(text) }
                .onFailure { android.util.Log.e("EleneBubble", "fetchTtsAudio threw", it) }
                .getOrNull()
            val played = if (audio != null) playOverlayAudioBytes(audio) else false
            if (!played) {
                speakOutOnDevice(text)
            }
        }
    }

    private fun speakOutOnDevice(text: String) {
        val currentTts = bubbleTts
        if (currentTts == null) {
            bubbleTts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    bubbleTts?.setOnUtteranceProgressListener(speechDoneListener)
                    bubbleTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "elene_overlay")
                } else {
                    retryListeningSoon(0)
                }
            }
        } else {
            currentTts.setOnUtteranceProgressListener(speechDoneListener)
            currentTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "elene_overlay")
        }
    }

    private var bubbleMediaPlayer: android.media.MediaPlayer? = null

    private suspend fun playOverlayAudioBytes(bytes: ByteArray): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        runCatching {
            kotlinx.coroutines.withContext(Dispatchers.Main) { bubbleMediaPlayer?.release() }
            val file = java.io.File(cacheDir, "elene_overlay_tts_${System.currentTimeMillis()}.mp3")
            file.writeBytes(bytes)
            val player = android.media.MediaPlayer()
            player.setOnCompletionListener { mp ->
                mp.release()
                if (bubbleMediaPlayer === mp) bubbleMediaPlayer = null
                runCatching { file.delete() }
                // Real gap the user noticed live ("the AI's voice is affecting when it tries to
                // listen"): reopening the mic with zero delay right as playback completes gives
                // the phone's own speaker output no time to acoustically settle before the mic
                // is live again, on a device where speaker and mic sit close together. A short
                // buffer here (unlike the 0-delay used elsewhere for "nothing was said, go
                // straight back to listening" cases where no audio played at all) gives real
                // playback's own echo/tail time to die down first.
                retryListeningSoon(500L)
            }
            player.setOnErrorListener { mp, _, _ ->
                mp.release()
                if (bubbleMediaPlayer === mp) bubbleMediaPlayer = null
                runCatching { file.delete() }
                retryListeningSoon(500L)
                true
            }
            player.setDataSource(file.absolutePath)
            player.prepare()
            bubbleMediaPlayer = player
            kotlinx.coroutines.withContext(Dispatchers.Main) { player.start() }
            true
        }.getOrElse { false }
    }

    // This is now the ONLY command handler - there is no separate home-screen implementation
    // anymore. Anything that genuinely needs a visible Activity UI (Settings/Security
    // navigation, the Apps screen's search box, the screen-record consent dialog, app-lock's
    // PIN/biometric flow, freeze/unfreeze's app grid state, scheduled actions) brings
    // MainActivity to front carrying the raw command via bringHomeWithCommand(), which runs it
    // through MainActivity's own handleEleneCommand - same dispatcher, just reached from here
    // instead of directly. Everything else (screen control, device settings, lookups) is
    // answered directly, since bringing the launcher to front for those would be pointless or
    // actively wrong (e.g. click/scroll/type_text need whatever app is CURRENTLY in front).
    private suspend fun handleOverlayCommand(command: String) {
        val parts = command.split(":", limit = 2)
        val verb = parts[0]
        val arg = parts.getOrNull(1)
        android.util.Log.d("EleneBubble", "Command verb=\"$verb\" arg=\"$arg\"")
        val handled = when (verb) {
            "stop_listening" -> { stopListening(); true }
            "open_app" -> arg != null && openAppByLabel(arg)
            "search_app" -> arg != null && bringHomeWithSearch(arg)
            "open_page" -> arg != null && bringHomeWithCommand("open_page:$arg")
            "freeze_app" -> arg != null && bringHomeWithCommand("freeze_app:$arg")
            "unfreeze_app" -> arg != null && bringHomeWithCommand("unfreeze_app:$arg")
            "schedule" -> arg != null && bringHomeWithCommand("schedule:$arg")
            "hide_page" -> toggleHidePage()
            "toggle_dark_mode" -> {
                val prefs = getSharedPreferences("theme_prefs", MODE_PRIVATE)
                val current = loadDarkMode(prefs)
                saveDarkMode(prefs, if (current == DarkModeOption.DARK) DarkModeOption.LIGHT else DarkModeOption.DARK)
                true
            }
            "toggle_battery_saver" -> {
                val prefs = getSharedPreferences("battery_prefs", MODE_PRIVATE)
                val current = loadBatterySaverMode(prefs)
                saveBatterySaverMode(prefs, if (current == BatterySaverMode.OFF) BatterySaverMode.AGGRESSIVE else BatterySaverMode.OFF)
                true
            }
            // Simplified to always open Bluetooth settings for both "on" and "off" - the
            // one-tap silent enable MainActivity's ActivityResult launcher does for "on" needs
            // an Activity, and isn't worth a bridge round-trip just for that convenience.
            "bluetooth" -> runCatching {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            "go_back" -> { goBack(); true }
            "go_home" -> { goHome(); true }
            "open_recents" -> { openRecents(); true }
            "scroll_up" -> { scrollUp(); true }
            "scroll_down" -> { scrollDown(); true }
            // Right after open_app/download_app, the new app's window often hasn't finished
            // drawing when this next command runs (only ~350ms separates multi-step commands).
            // rootInActiveWindow would come back null/stale and this would fail immediately,
            // which is what was actually happening on "open Chrome then search X" - not a real
            // home-screen-only restriction. Polling briefly for the target to show up fixes it
            // without guessing a fixed delay per app.
            "click" -> arg != null && retryUntilTrue { clickByText(arg) }
            "highlight" -> arg != null && retryUntilTrue { highlightByText(arg) }
            "type_text" -> arg != null && retryUntilTrue { typeText(arg) }
            "highlight_off" -> { clearHighlight(); true }
            "force_stop_app" -> if (arg != null) {
                val pkg = AppResolver.resolvePackageName(this, arg)
                when {
                    pkg == null -> false
                    ShizukuManager.hasPermission() -> ShizukuManager.forceStopPackage(this@ScifiAccessibilityService, pkg).isSuccess
                    else -> {
                        val am = getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager
                        runCatching { am?.killBackgroundProcesses(pkg) }
                        speakOut("Stopped $arg's background processes - a full force-stop needs Shizuku set up in Settings.")
                        true
                    }
                }
            } else false
            "volume" -> if (arg != null) {
                val am = getSystemService(AUDIO_SERVICE) as? android.media.AudioManager
                if (am != null) {
                    val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    val current = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    val step = (max * 0.15f).toInt().coerceAtLeast(1)
                    val target = when {
                        arg == "up" -> (current + step).coerceIn(0, max)
                        arg == "down" -> (current - step).coerceIn(0, max)
                        arg.toIntOrNull() != null -> (arg.toInt().coerceIn(0, 100) * max) / 100
                        else -> current
                    }
                    runCatching {
                        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, android.media.AudioManager.FLAG_SHOW_UI)
                        true
                    }.getOrDefault(false)
                } else false
            } else false
            "brightness" -> if (arg != null) {
                if (!Settings.System.canWrite(this)) {
                    false
                } else {
                    val currentRaw = runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
                    val current = (currentRaw * 100 / 255).coerceIn(1, 100)
                    val target = when {
                        arg == "up" -> (current + 15).coerceIn(1, 100)
                        arg == "down" -> (current - 15).coerceIn(1, 100)
                        arg.toIntOrNull() != null -> arg.toInt().coerceIn(1, 100)
                        else -> current
                    }
                    runCatching {
                        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, target * 255 / 100)
                        true
                    }.getOrDefault(false)
                }
            } else false
            "flashlight" -> {
                if (arg == "on" || arg == "off") {
                    setFlashlight(this, arg == "on")
                    true
                } else false
            }
            "open_android_settings" -> runCatching {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            "download_app" -> if (arg != null) {
                val encoded = Uri.encode(arg)
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$encoded&c=apps"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    speakOut("Opening the Play Store for \"$arg\" - tap Install yourself from here.")
                    true
                }.getOrDefault(false)
            } else false
            "world_clock" -> if (arg != null) {
                runCatching {
                    val zone = java.time.ZoneId.of(arg)
                    val now = java.time.ZonedDateTime.now(zone)
                    val formatted = now.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
                    speakOut("It is $formatted in ${arg.substringAfterLast('/').replace('_', ' ')}.")
                    true
                }.getOrDefault(false)
            } else false
            "scan_wifi" -> {
                speakOut("Affirmative. Scanning the network.")
                bubbleServiceScope.launch {
                    val devices = scanLocalNetwork(this@ScifiAccessibilityService)
                    speakOut(
                        if (devices.isEmpty()) "Negative. I found no other devices on this network."
                        else "Found ${devices.size} device${if (devices.size == 1) "" else "s"}: ${devices.joinToString("; ") { it.ip }}."
                    )
                }
                true
            }
            "remember_avoid" -> arg?.let {
                addAvoidTopic(getSharedPreferences("elene_memory_prefs", MODE_PRIVATE), it); true
            } ?: false
            "forget_avoid" -> arg?.let {
                removeAvoidTopic(getSharedPreferences("elene_memory_prefs", MODE_PRIVATE), it); true
            } ?: false
            // Durable fallback for the backend's own conversation memory, which is only
            // in-memory and resets whenever the Cloud Run instance recycles - fed back into
            // ctxMap below on every turn so Elene can recall it even after that reset.
            "remember_fact" -> arg?.let { RememberedFactLog.record(this@ScifiAccessibilityService, it); true } ?: false
            "start_screen_recording" -> bringHomeToStartRecording()
            "stop_screen_recording" -> runCatching {
                startService(Intent(this, ScreenRecordService::class.java).setAction(ScreenRecordService.ACTION_STOP_AND_FINISH))
                true
            }.getOrDefault(false)
            // Elene has no visual perception otherwise (everything else works off the
            // Accessibility Tree's text, never pixels - see possibilities.md section E) -
            // these are the only two verbs that actually look at the screen.
            "describe_screen" -> { startDescribeScreen(arg); true }
            "play_game" -> { startGameLoop(arg); true }
            "stop_game" -> {
                if (gameLoopActive) stopGameLoop("Stopped playing.") else speakOut("Not currently playing.")
                true
            }
            // Both need MainActivity's confirm-before-send UI, so they bridge rather than
            // complete here - same pattern as search_app/schedule above.
            "reply_last_message" -> arg != null && bringHomeWithCommand("reply_last_message:$arg")
            "send_message" -> arg != null && bringHomeWithCommand("send_message:$arg")
            // Stage 1 of self-updating Elene - needs MainActivity's fingerprint confirmation UI
            // (user-originated) or just the Updates screen write (Elene-originated), same
            // bridge-rather-than-complete-here reasoning.
            "propose_update" -> arg != null && bringHomeWithCommand("propose_update:$arg")
            // No UI needed for these - complete directly, same as open_app above.
            "answer_call" -> { attemptAnswerCall(this@ScifiAccessibilityService); true }
            "end_call" -> { attemptEndCall(this@ScifiAccessibilityService); true }
            // Doesn't start the recorder here directly - see the comment on
            // pendingVoiceMemoStart / retryListeningSoon() for why: it needs to wait until the
            // "Recording started." reply (already in flight via speakOut, called just before the
            // command loop that reaches this) has actually finished playing, or the memo's own
            // first second is Elene announcing herself.
            "start_recording" -> runCatching {
                if (!VoiceMemoService.isRecording) pendingVoiceMemoStart = true
                true
            }.getOrDefault(false)
            "stop_recording" -> runCatching {
                if (VoiceMemoService.isRecording) VoiceMemoService.stop(this@ScifiAccessibilityService)
                true
            }.getOrDefault(false)
            else -> false
        }
        if (!handled) {
            // Was "I can only do a few things from outside the home screen" - misleading, since
            // this fires for ANY failed attempt (element not found, app not resolved, screen
            // not ready yet) regardless of which app is in front. Elene works across apps; this
            // specific attempt just didn't find what it needed.
            speakOut("I couldn't do that - I might not have found what you meant on screen.")
        }
    }

    /** Polls a screen-interaction action for a couple seconds instead of failing on the first
     * instant attempt - the target app's window is often still drawing right after it was just
     * launched by an earlier command in the same turn. */
    private suspend fun retryUntilTrue(attempts: Int = 6, delayMillis: Long = 250L, action: () -> Boolean): Boolean {
        repeat(attempts) { i ->
            if (action()) return true
            if (i != attempts - 1) kotlinx.coroutines.delay(delayMillis)
        }
        return false
    }

    // ---- Screen perception: describe_screen / play_game ----
    // Deliberately scoped to slow, turn-based games only (confirmed with the user) - cloud
    // vision round-trips are realistically 1-4+ seconds per move, which rules out reflex/timed
    // games. ScreenPerceptionService (in the isolated :recorder process) owns only the
    // MediaProjection/ImageReader capture pipeline; everything here - deciding when to capture,
    // calling the backend, dispatching gestures, safety caps, app-switch auto-stop - is owned
    // by this service, since it already owns every one of those pieces for every other verb.

    private var pendingCaptureTargetPkg: String? = null
    private var pendingCaptureQuestion: String? = null
    private var pendingCaptureIsSingle = true
    private var awaitingCaptureRelaunch = false
    private var pendingFrameRequestId: String? = null

    // True from the moment describe_screen/play_game is triggered until the first real frame
    // (or a failure) comes back - see haltListeningForCapture() for why this whole window,
    // not just the instant of triggering it, needs the mic held closed.
    private var awaitingCaptureConsent = false

    private var gameLoopActive = false
    private var gameLoopTargetPkg: String? = null
    private var gameLoopHint: String? = null
    private var gameLoopIterations = 0
    private var gameLoopStartedAtMs = 0L
    private val gameLoopRecentMoves = ArrayDeque<String>()

    private val perceptionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ScreenPerceptionService.ACTION_FRAME_READY -> onFrameReady(intent)
                ScreenPerceptionService.ACTION_FRAME_ERROR -> onCaptureFailed("Couldn't read the screen.")
                ScreenPerceptionService.ACTION_PROJECTION_STOPPED -> onCaptureFailed("Screen sharing stopped.")
            }
        }
    }

    /** Confirmed real gap (found from live on-device use, not assumed): the MediaProjection
     * consent flow - the system permission dialog, and on this OEM build an audible "screen
     * sharing started" confirmation once granted - happens while Elene's own continuous
     * listening could still be open (the turn that said "play this" naturally loops back into
     * listening once command dispatch finishes, well before the user has even seen the consent
     * dialog). That system audio was getting picked up by the still-open mic and misread as a
     * command, sending Elene briefly haywire until she recovered. Same class of bug as the
     * incoming-call/mic collision already fixed once - closed the same way: stop the mic and
     * hold it closed for the whole bootstrap window, not just the instant of triggering it. */
    private fun haltListeningForCapture() {
        awaitingCaptureConsent = true
        if (speechRecognizer != null) {
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
        }
        setBubbleState(EleneBubbleState.DORMANT)
    }

    fun startDescribeScreen(question: String?) {
        haltListeningForCapture()
        pendingCaptureTargetPkg = lastForegroundPkg
        pendingCaptureQuestion = question
        pendingCaptureIsSingle = true
        if (!bringHomeWithCommand("describe_screen_capture")) {
            awaitingCaptureConsent = false
            speakOut("Couldn't start the screen permission prompt.")
        }
    }

    fun startGameLoop(hint: String?) {
        val target = lastForegroundPkg
        if (target == null || target == packageName) {
            speakOut("Open the game first, then ask me to play.")
            return
        }
        haltListeningForCapture()
        pendingCaptureTargetPkg = target
        pendingCaptureQuestion = null
        pendingCaptureIsSingle = false
        gameLoopHint = hint
        if (!bringHomeWithCommand("play_game_capture")) {
            awaitingCaptureConsent = false
            speakOut("Couldn't start the screen permission prompt.")
        }
    }

    /** Called directly by MainActivity (same process) right after the MediaProjection consent
     * dialog is granted and ScreenPerceptionService has been started. */
    fun onPerceptionCaptureStarted(isSingle: Boolean) {
        pendingCaptureIsSingle = isSingle
        val target = pendingCaptureTargetPkg
        if (target == null || target == packageName) {
            // Already effectively on the launcher/home - no relaunch dance needed.
            bubbleHandler.postDelayed({ requestFrame() }, 500L)
            return
        }
        awaitingCaptureRelaunch = true
        runCatching { openAppByPackage(target) }
        // Safety timeout in case the relaunch never actually brings the target's window back
        // (app was killed, permission issue, etc.) - without this a failed relaunch would leave
        // the request hanging forever with no feedback.
        bubbleHandler.postDelayed({
            if (awaitingCaptureRelaunch) {
                awaitingCaptureRelaunch = false
                onCaptureFailed("Couldn't get back to that app.")
            }
        }, 5000L)
    }

    /** Called directly by MainActivity when the user denies the MediaProjection consent
     * prompt, or the launch attempt itself failed. */
    fun onPerceptionCaptureDenied() {
        val wasGameLoop = !pendingCaptureIsSingle
        awaitingCaptureConsent = false
        resetCaptureState()
        speakOut(
            if (wasGameLoop) "I need the screen-sharing permission to play games for you."
            else "I need the screen-sharing permission to see your screen."
        )
    }

    private fun requestFrame() {
        val requestId = System.currentTimeMillis().toString()
        pendingFrameRequestId = requestId
        runCatching {
            sendBroadcast(
                Intent(ScreenPerceptionService.ACTION_REQUEST_FRAME).setPackage(packageName).putExtra("requestId", requestId)
            )
        }
    }

    private fun onFrameReady(intent: Intent) {
        val requestId = intent.getStringExtra("requestId")
        if (requestId == null || requestId != pendingFrameRequestId) return
        pendingFrameRequestId = null
        val path = intent.getStringExtra("path")
        val bytes = path?.let { runCatching { java.io.File(it).readBytes() }.getOrNull() }
        path?.let { runCatching { java.io.File(it).delete() } }
        if (bytes == null) {
            onCaptureFailed("Couldn't read the screen.")
            return
        }

        // The bootstrap window (consent + relaunch) is over now that a real frame came back -
        // safe to let the mic reopen again from here on (still held closed if capture itself
        // fails below, via onCaptureFailed).
        awaitingCaptureConsent = false

        if (pendingCaptureIsSingle) {
            val question = pendingCaptureQuestion
            resetCaptureState()
            bubbleServiceScope.launch {
                val description = runCatching { EleneApiClient.describeScreen(bytes, question) }.getOrNull()
                speakOut(description ?: "I couldn't make sense of what's on screen.")
            }
            return
        }

        if (!gameLoopActive) {
            gameLoopActive = true
            gameLoopTargetPkg = pendingCaptureTargetPkg
            gameLoopIterations = 0
            gameLoopStartedAtMs = System.currentTimeMillis()
            gameLoopRecentMoves.clear()
            speakOut("Okay, I'm watching - I'll play slowly since I have to think about each move.")
        }
        runGameMoveDecision(
            frameWidth = intent.getIntExtra("frameWidth", 0),
            frameHeight = intent.getIntExtra("frameHeight", 0),
            fullWidth = intent.getIntExtra("fullWidth", 0),
            fullHeight = intent.getIntExtra("fullHeight", 0),
            bytes = bytes
        )
    }

    private fun runGameMoveDecision(frameWidth: Int, frameHeight: Int, fullWidth: Int, fullHeight: Int, bytes: ByteArray) {
        if (!gameLoopActive) return
        gameLoopIterations++
        if (gameLoopIterations > MAX_GAME_LOOP_ITERATIONS) {
            stopGameLoop("I've made a lot of moves, so I'll stop here rather than keep going forever.")
            return
        }
        if (System.currentTimeMillis() - gameLoopStartedAtMs > MAX_GAME_LOOP_DURATION_MS) {
            stopGameLoop("That's been ten minutes of playing - stopping here.")
            return
        }
        bubbleServiceScope.launch {
            val decision = runCatching { EleneApiClient.decideGameMove(bytes, gameLoopHint, gameLoopRecentMoves.toList()) }.getOrNull()
            // The loop may have been stopped (spoken "stop", app switch, safety cap) while this
            // call was in flight - let the current iteration finish, but don't act on a stale
            // decision for a loop that's no longer running.
            if (!gameLoopActive) return@launch
            if (decision == null) {
                stopGameLoop("Lost the connection while deciding a move - stopping.")
                return@launch
            }
            applyGameMove(decision, frameWidth, frameHeight, fullWidth, fullHeight)
        }
    }

    private fun applyGameMove(decision: GameMoveDecision, frameWidth: Int, frameHeight: Int, fullWidth: Int, fullHeight: Int) {
        if (!gameLoopActive) return
        if (decision.gameOver || decision.action == "give_up") {
            stopGameLoop(if (decision.gameOver) "Looks like the game's over." else "I'm not sure what to do next, so I'll stop here.")
            return
        }
        // Model's coordinates are in the downscaled frame's space, not full-screen pixels -
        // must be scaled back up by the known ratio before dispatching, or every tap silently
        // lands in the wrong spot with no error signal.
        val scaleX = if (frameWidth > 0) fullWidth.toFloat() / frameWidth else 1f
        val scaleY = if (frameHeight > 0) fullHeight.toFloat() / frameHeight else 1f
        when (decision.action) {
            "tap" -> {
                val x = decision.x
                val y = decision.y
                if (x != null && y != null) tapAt((x * scaleX).toInt(), (y * scaleY).toInt())
            }
            "swipe" -> {
                val x1 = decision.x
                val y1 = decision.y
                val x2 = decision.x2
                val y2 = decision.y2
                if (x1 != null && y1 != null && x2 != null && y2 != null) {
                    swipeCoords((x1 * scaleX).toInt(), (y1 * scaleY).toInt(), (x2 * scaleX).toInt(), (y2 * scaleY).toInt())
                }
            }
            // "wait" - deliberate no-op, just re-observe next cycle.
        }
        gameLoopRecentMoves.addLast("${decision.action}: ${decision.reasoning}".take(80))
        while (gameLoopRecentMoves.size > 5) gameLoopRecentMoves.removeFirst()

        bubbleHandler.postDelayed({
            if (gameLoopActive) requestFrame()
        }, GAME_LOOP_SETTLE_MS)
    }

    fun isGameLoopActive(): Boolean = gameLoopActive

    fun stopGameLoop(reason: String) {
        if (!gameLoopActive) return
        gameLoopActive = false
        gameLoopTargetPkg = null
        pendingFrameRequestId = null
        runCatching {
            startService(Intent(this, ScreenPerceptionService::class.java).setAction(ScreenPerceptionService.ACTION_STOP_CAPTURE))
        }
        speakOut(reason)
    }

    private fun onCaptureFailed(message: String) {
        pendingFrameRequestId = null
        awaitingCaptureConsent = false
        if (gameLoopActive) {
            stopGameLoop(message)
        } else {
            resetCaptureState()
            speakOut(message)
        }
    }

    private fun resetCaptureState() {
        pendingCaptureTargetPkg = null
        pendingCaptureQuestion = null
        pendingCaptureIsSingle = true
        awaitingCaptureRelaunch = false
    }

    private fun openAppByPackage(pkg: String): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { startActivity(launchIntent); true }.getOrDefault(false)
    }


    @Suppress("DEPRECATION")
    private fun openAppByLabel(query: String): Boolean {
        val pkg = AppResolver.resolvePackageName(this, query) ?: return false
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { startActivity(launchIntent); true }.getOrDefault(false)
    }

    /** Brings the launcher to front carrying a raw command string, which it runs through its
     * own handleEleneCommand - the same dispatcher a command typed/spoken from the home screen
     * would go through. Used for anything that genuinely needs a visible Activity UI
     * (Settings/Security navigation, the Apps screen's search box, the screen-record consent
     * dialog, confirmation panels, PIN entry) since a Service can't show any of that itself. */
    private fun bringHomeWithCommand(command: String): Boolean = runCatching {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_ELENE_COMMAND, command)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
        )
        true
    }.getOrDefault(false)

    private fun bringHomeWithSearch(query: String): Boolean = bringHomeWithCommand("search_app:$query")

    private fun bringHomeToStartRecording(): Boolean = bringHomeWithCommand("start_screen_recording")

    override fun onInterrupt() {}

    fun goBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun openRecents() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    fun scrollUp() = swipe(fromBottom = true)

    fun scrollDown() = swipe(fromBottom = false)

    private fun swipe(fromBottom: Boolean) {
        val metrics = resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val startY = if (fromBottom) metrics.heightPixels * 0.75f else metrics.heightPixels * 0.25f
        val endY = if (fromBottom) metrics.heightPixels * 0.25f else metrics.heightPixels * 0.75f

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 250))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /** Coordinate-based tap, unlike clickByText - needed for games (Unity/OpenGL/Canvas
     * rendering) that expose no Accessibility node tree at all to match text against. */
    fun tapAt(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipeCoords(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 250): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    // ---- Screen understanding: describe / click / highlight ----

    /** Short summary of the visible text on the current screen, for Elene's context. */
    fun describeScreen(): String {
        val root = rootInActiveWindow ?: return ""
        val texts = LinkedHashSet<String>()
        collectText(root, texts)
        return texts.joinToString(", ").take(600)
    }

    private fun collectText(node: AccessibilityNodeInfo, into: MutableSet<String>) {
        node.text?.toString()?.trim()?.let { if (it.isNotEmpty()) into.add(it) }
        node.contentDescription?.toString()?.trim()?.let { if (it.isNotEmpty()) into.add(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectText(it, into) }
        }
    }

    /** Finds and taps the first on-screen element whose text/description contains [query]. */
    fun clickByText(query: String): Boolean {
        val node = findNodeByText(query) ?: return false
        // Text/icon nodes are often wrapped by the actual clickable container.
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) {
            target = target.parent
        }
        return (target ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Draws a highlight box around the first on-screen element matching [query]. */
    fun highlightByText(query: String): Boolean {
        val node = findNodeByText(query) ?: return false
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        showHighlight(bounds)
        return true
    }

    /** Types into the currently focused text field (e.g. a chat's message box after you've
     * tapped into it), falling back to the first editable field found on screen if nothing has
     * input focus yet. There was previously no way at all for Elene to type into anything -
     * click/highlight only ever tapped or pointed at existing on-screen elements. */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditableNode(root) ?: return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findFirstEditableNode(child)?.let { return it }
        }
        return null
    }

    fun clearHighlight() {
        val view = highlightView ?: return
        highlightView = null
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        }
    }

    /** Confirmed real bug (found live, not assumed): "click <contact name>" in an app like
     * WhatsApp was landing on that contact's avatar instead of their chat row - the avatar
     * often carries the exact same accessible name as the row's own text label (both read
     * "John Doe"), and the avatar frequently comes first in the accessibility tree's match
     * order, so the old firstOrNull() picked it. Tapping an avatar's own target (opens the
     * profile picture/contact info) is a different action from tapping the row. Prefer an
     * actual text-bearing match (TextView/EditText) over an image/icon one when both match the
     * same query, falling back to the first match only if nothing text-like matched at all -
     * a genuinely icon-only button with a matching content-description still needs to work. */
    private fun findNodeByText(query: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val matches = root.findAccessibilityNodeInfosByText(query) ?: return null
        val textLike = matches.firstOrNull { node ->
            val cls = node.className?.toString().orEmpty()
            cls.contains("TextView") || cls.contains("EditText")
        }
        return textLike ?: matches.firstOrNull()
    }

    // ---- Play Store search assist (for the voice "download X" flow) ----
    // Play Store obfuscates nearly all its resource-ids in production builds, so matching by
    // id isn't possible - this reads the same content descriptions TalkBack would announce.
    // Each result card's content-desc is multi-line: "<App Name>\n<Developer>\n...\nStar
    // rating: X\nDownloaded N times" (confirmed by inspecting a live search on-device).

    /** First non-sponsored search result's (name, developer), or null if the results haven't
     * rendered yet / nothing recognizable is on screen. */
    fun findTopPlayStoreResult(): Pair<String, String>? {
        val root = rootInActiveWindow ?: return null
        val candidates = mutableListOf<String>()
        collectPlayStoreCardDescriptions(root, candidates)
        for (desc in candidates) {
            if (desc.contains("Sponsored", ignoreCase = true)) continue
            val lines = desc.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size >= 2) return lines[0] to lines[1]
        }
        return null
    }

    private fun collectPlayStoreCardDescriptions(node: AccessibilityNodeInfo, into: MutableList<String>) {
        val desc = node.contentDescription?.toString()
        if (desc != null && desc.contains("\n") && (desc.contains("Star rating:") || desc.contains("Downloaded"))) {
            into.add(desc)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectPlayStoreCardDescriptions(it, into) }
        }
    }

    private fun showHighlight(bounds: Rect) {
        clearHighlight()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val view = View(this).apply {
            foreground = GradientDrawable().apply {
                setStroke(6, Color.CYAN)
                setColor(Color.argb(40, 0, 255, 255))
            }
        }

        val params = WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
        }

        runCatching { wm.addView(view, params) }.onSuccess { highlightView = view }
    }

    // ---- Hide page: full-screen glitch privacy cover ----

    /** Toggles a full-screen animated glitch cover over whatever's currently on screen. */
    fun toggleHidePage(): Boolean {
        return if (hideOverlayView != null) {
            removeHideOverlay()
            false
        } else {
            showHideOverlay()
            true
        }
    }

    private fun showHideOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = GlitchCoverView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        runCatching { wm.addView(view, params) }.onSuccess { hideOverlayView = view }
    }

    private fun removeHideOverlay() {
        val view = hideOverlayView ?: return
        hideOverlayView = null
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        }
    }
}

/** Mirrors the launcher's own two real Elene looks (see the bubbleVisible/bubbleColor logic
 * in MainActivity): a translucent theme-colored edge tab with an "E" while dormant, and a
 * near-black circle with a state-colored ring once actually listening/replying. Plain Canvas,
 * not Compose, since this has to render as a WindowManager overlay outside any Activity - the
 * colors, shapes and theme color are matched by hand rather than shared code. */
private class BubbleView(context: android.content.Context, private val themeColorArgb: Int) : View(context) {
    var state: EleneBubbleState = EleneBubbleState.DORMANT
        set(value) {
            field = value
            postInvalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private var pulsePhase = 0f
    private val tickRunnable = object : Runnable {
        override fun run() {
            pulsePhase += 0.05f
            postInvalidate()
            postDelayed(this, 60L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(tickRunnable)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tickRunnable)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (state == EleneBubbleState.DORMANT) drawIdleTab(canvas) else drawActiveCircle(canvas)
    }

    /** Matches the launcher's CenterStart edge tab: 22x64dp, rounded on the outer end,
     * translucent theme color fill, a single "E". */
    private fun drawIdleTab(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = themeColorArgb
        paint.alpha = 82 // ~0.32 alpha, matching themeColor.copy(alpha = 0.32f) in the launcher
        canvas.drawRoundRect(android.graphics.RectF(0f, 0f, w, h), w, w, paint)

        textPaint.color = themeColorArgb
        textPaint.alpha = 255
        textPaint.textSize = h * 0.26f
        val fm = textPaint.fontMetrics
        canvas.drawText("E", w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    /** Matches the launcher's active bubble: size(56dp) circle, near-black fill
     * (Color(0xFF101010) at 0.85 alpha), a themed stroke ring on top. */
    private fun drawActiveCircle(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = kotlin.math.min(width, height) / 2f - (4f * resources.displayMetrics.density)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(16, 16, 16)
        paint.alpha = 217
        canvas.drawCircle(cx, cy, radius, paint)

        val ringColor = when (state) {
            EleneBubbleState.LISTENING -> Color.WHITE
            EleneBubbleState.REPLYING -> {
                val glitchColors = intArrayOf(Color.RED, Color.rgb(0, 230, 118), Color.rgb(41, 121, 255))
                val segment = pulsePhase.toInt() % glitchColors.size
                glitchColors[segment]
            }
            else -> Color.rgb(60, 60, 60) // UNRESPONSIVE
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f * resources.displayMetrics.density
        paint.color = ringColor
        paint.alpha = 255
        canvas.drawCircle(cx, cy, radius, paint)
    }
}

/** Drag-to-move + tap-to-activate, the same interaction model as Messenger-style chat heads. */
private class BubbleTouchListener(
    private val onDrag: (dx: Int, dy: Int) -> Unit,
    private val onTap: () -> Unit
) : View.OnTouchListener {
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var moved = false

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = downRawX
                lastRawY = downRawY
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - lastRawX).toInt()
                val dy = (event.rawY - lastRawY).toInt()
                if (kotlin.math.abs(event.rawX - downRawX) > 12 || kotlin.math.abs(event.rawY - downRawY) > 12) {
                    moved = true
                }
                lastRawX = event.rawX
                lastRawY = event.rawY
                onDrag(dx, dy)
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) onTap()
            }
        }
        return true
    }
}

/** Animated static/scanline glitch cover, matching the launcher's visual style. */
private class GlitchCoverView(context: android.content.Context) : View(context) {
    private val paint = Paint()
    private val random = Random(System.currentTimeMillis())
    private val glitchColors = intArrayOf(Color.CYAN, Color.MAGENTA, Color.GREEN)
    private val sliceHeight = 14

    private val refreshRunnable: Runnable = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 90L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(refreshRunnable)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refreshRunnable)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        var y = 0
        while (y < height) {
            paint.color = glitchColors[random.nextInt(glitchColors.size)]
            paint.alpha = random.nextInt(60, 180)
            val xOffset = random.nextInt(-40, 40)
            canvas.drawRect(
                xOffset.toFloat(),
                y.toFloat(),
                (width + xOffset).toFloat(),
                (y + sliceHeight).toFloat(),
                paint
            )
            y += sliceHeight
        }
    }
}
