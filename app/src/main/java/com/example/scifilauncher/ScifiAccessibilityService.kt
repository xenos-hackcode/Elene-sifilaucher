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
    }

    override fun onDestroy() {
        clearHighlight()
        removeHideOverlay()
        hideBubble()
        runCatching { speechRecognizer?.destroy() }
        runCatching { bubbleTts?.shutdown() }
        runCatching { unregisterReceiver(screenStateReceiver) }
        bubbleServiceScope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
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
            } else {
                val size = (BUBBLE_SIZE_DP * density).toInt()
                params.width = size
                params.height = size
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
    private fun startListening() {
        listeningStopped = false
        // Calls are still an absolute block - never grab the mic mid-call, no exceptions.
        if (isPhoneBusyWithCall(this)) {
            setBubbleState(EleneBubbleState.DORMANT)
            return
        }
        // Media playing used to also go straight to dormant (the same "don't misread the movie
        // dialogue as a command" concern as a call) - but that meant Elene simply couldn't be
        // used at all while music/video was playing. Taking real AUDIOFOCUS_GAIN instead
        // actually pauses well-behaved media apps for the turn (same as any real assistant),
        // which solves both problems at once: you get an answer, and there's no competing audio
        // left to misread once it's paused.
        if (isMediaPlaying(this) && mediaFocusHandle == null) {
            mediaFocusHandle = requestAudioFocus(this, transient = false)
        }
        setBubbleState(EleneBubbleState.LISTENING)
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setBubbleState(EleneBubbleState.UNRESPONSIVE)
            retryListeningSoon()
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

    @Volatile private var voiceIdCheckInProgress = false

    private fun retryListeningSoon(delayMillis: Long = 900L) {
        // This turn's actual work (listening, replying, running commands) just wrapped up -
        // let go of media focus now rather than holding it for the rest of a continuous-
        // listening session that might not hear anything else for a while. startListening()
        // re-acquires it immediately if media's still playing when the next turn actually
        // begins, so this just avoids pausing music indefinitely after a single command.
        if (mediaFocusHandle != null) {
            releaseAudioFocus(this, mediaFocusHandle)
            mediaFocusHandle = null
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
            val ctxMap = buildMap {
                if (screenText.isNotBlank()) put("screen_text", screenText)
                if (avoidTopics.isNotEmpty()) put("avoid_topics", avoidTopics.joinToString(", "))
                if (installedApps.isNotEmpty()) put("installed_apps", installedApps.joinToString(", ") { it.label }.take(1500))
                if (recentlyOpened.isNotEmpty()) put("recently_opened_apps", recentlyOpened.joinToString(", "))
                // So Elene can honestly answer "do you recognize my voice" instead of denying a
                // real feature it has - was previously invisible to the backend entirely.
                put("voice_id_status", if (VoiceIdManager.isEnrolled(this@ScifiAccessibilityService)) "enrolled" else "not_enrolled")
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
            return score >= style.threshold
        } finally {
            voiceIdCheckInProgress = false
        }
    }

    private val speechDoneListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {
            bubbleHandler.post { retryListeningSoon(0) }
        }
        @Deprecated("Deprecated in Java", ReplaceWith(""))
        override fun onError(utteranceId: String?) {
            bubbleHandler.post { retryListeningSoon(0) }
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
                retryListeningSoon(0)
            }
            player.setOnErrorListener { mp, _, _ ->
                mp.release()
                if (bubbleMediaPlayer === mp) bubbleMediaPlayer = null
                runCatching { file.delete() }
                retryListeningSoon(0)
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
                    ShizukuManager.hasPermission() -> ShizukuManager.forceStopPackage(pkg).isSuccess
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
            "start_screen_recording" -> bringHomeToStartRecording()
            "stop_screen_recording" -> runCatching {
                startService(Intent(this, ScreenRecordService::class.java).setAction(ScreenRecordService.ACTION_STOP_AND_FINISH))
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

    private fun findNodeByText(query: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findAccessibilityNodeInfosByText(query)?.firstOrNull()
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
