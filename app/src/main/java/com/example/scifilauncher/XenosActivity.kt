package com.example.scifilauncher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/**
 * "Xenos" - a visual home for the AI (Elene, speaking as Xenos), not a navigable map like
 * Globe/MyLocation. Was internally "ReactorActivity" (user-facing name was already "Xenos") until
 * the user asked for the internal name to match too. Visual is [XenosSkeleton] - a red face-
 * skeleton, not the [EyeVisual] Welcome uses (user corrected an early mix-up here: "xenos should
 * be the skelton not the eye"), replacing the earlier dotted-glow Three.js globe.
 *
 * XenosSkeleton runs its own camera to know whether a face is currently in view - not to mirror
 * it (the skeleton's shape never reflects your real landmarks, only Xenos's own talking/idle
 * state - user: "the skeleton is owned by the ai... only folows it own emotion"), but purely to
 * gate the mic: user: "the skeleton only appears when u bring ur face... once no skeleton shows
 * then it cant hear or reply u" - [facePresentState] tracks this, checked in [onMicTapped].
 *
 * Below it, a CHAT button opens a real multimodal chat: text, live speech (see below), a photo
 * (camera capture), or an image file (gallery) - photos reuse EleneApiClient.describeScreen's
 * existing image+question vision call, the same one the "what's on my screen" feature already
 * uses, just pointed at a photo instead of a screenshot.
 *
 * Listening is automatic, not tap-to-talk - user: "i dont want to click on him to talk i want
 * that as long as the face visible he can her me meaning i dont need to click chatb although what
 * we said should be show in chat". [startContinuousListening]/[runListenCycle] run a real
 * `SpeechRecognizer` session back-to-back (same pattern ScifiAccessibilityService's own "Hey
 * Elene" always-listening loop already uses - Android has no separate low-power listening
 * primitive, a real cost accepted there too) for as long as [facePresentState] is true and the
 * mic isn't [isMuted] - each result is sent to chat and immediately followed by a fresh session.
 * Paused (not stopped) while Xenos is actually speaking a reply, so it doesn't transcribe its own
 * voice back into a new turn. [onMicTapped] is now just a manual "nudge" if a session ever gets
 * stuck - never required to actually talk to it.
 *
 * Xenos's replies are also spoken aloud (system TextToSpeech, same fallback engine MainActivity's
 * Elene voice uses) - the mic button pulses while that's playing (user: "when he is talking let
 * the microphone move"), mouth movement is gated the same way (see XenosSkeleton) - user: "when
 * am talking his mouth shouldnt move he should listen and when he wants to talk back his mouth
 * can move". A separate mute toggle stops the listening loop entirely (user: "u can mute the
 * microphone so he dosnt hear").
 *
 * Scope note: "send files" is images only for this first pass (reusing describeScreen, which only
 * understands images) - real arbitrary-file-type understanding (PDFs, documents) would need new
 * backend work, not attempted here.
 */
class XenosActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private data class ChatMessage(val isUser: Boolean, val text: String, val thumbnail: Bitmap? = null)

    private var xenosContainer: View? = null
    private var chatContainer: View? = null
    private var messageList: LinearLayout? = null
    private var messageScroll: ScrollView? = null
    private var inputField: EditText? = null
    private var sendButton: TextView? = null
    private val messages = mutableListOf<ChatMessage>()
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingPhotoBitmap: Bitmap? = null

    private var tts: TextToSpeech? = null
    private var micButton: TextView? = null
    private var muteButton: TextView? = null
    private var isMuted = false
    private var micPulseAnimator: ValueAnimator? = null

    // Compose State so XenosSkeleton's setContent{} block (below) recomposes automatically when
    // these change, even though the rest of this Activity is plain-View based.
    private val isSpeakingState = mutableStateOf(false)
    private val facePresentState = mutableStateOf(false)
    // "smile" | "frown" | "curious" | "neutral" - set from the backend's real "emotion" field on
    // each reply (see EleneApiClient.EleneResponse.emotion / main.py's "Your face" section), plus
    // an instant local "smile" pre-set on a direct request so it doesn't wait on the network -
    // see sendText below.
    private val expressionState = mutableStateOf("neutral")

    private var mediaPlayer: android.media.MediaPlayer? = null

    // Continuous listening loop state - see class doc.
    private var speechRecognizer: SpeechRecognizer? = null
    private var listeningActive = false
    private var isCurrentlyListening = false
    private val micHandler = Handler(Looper.getMainLooper())

    private val REQUEST_CAMERA = 5391
    private val REQUEST_RECORD_AUDIO = 5392

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) sendPhoto(bitmap)
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.openInputStream(uri)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()?.let { sendPhoto(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        tts = TextToSpeech(this, this)

        val themeColor = currentThemeColorArgb()
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val xenos = buildXenosView(themeColor)
        xenosContainer = xenos
        root.addView(xenos, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val chat = buildChatView(themeColor)
        chatContainer = chat
        chat.visibility = View.GONE
        root.addView(chat, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
        addAiMessage("Xenos is online. Speak, type, or show me something.")
    }

    private fun buildXenosView(themeColor: Int): View {
        val container = FrameLayout(this)

        // Xenos's own visual identity - a red face-skeleton, always fully present/centered, no
        // camera involved (see class doc).
        val skeletonView = ComposeView(this).apply {
            setContent {
                XenosSkeleton(
                    isSpeaking = isSpeakingState.value,
                    expression = expressionState.value,
                    onFacePresenceChanged = { present ->
                        facePresentState.value = present
                        if (present && !isMuted) startContinuousListening() else stopContinuousListening()
                    }
                )
            }
        }
        container.addView(skeletonView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        container.addView(buildBackButton(themeColor) { finish() })

        val chatOpenButton = TextView(this).apply {
            text = "▾ CHAT"
            setTextColor(themeColor)
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.argb(160, 0, 0, 0))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener { showChat() }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(32)
            }
        }
        container.addView(chatOpenButton)
        return container
    }

    private fun buildChatView(themeColor: Int): View {
        val scroll = ScrollView(this)
        messageScroll = scroll
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        messageList = list
        scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val input = EditText(this).apply {
            hint = "Message Xenos..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.argb(160, 30, 30, 30))
                cornerRadius = dp(8).toFloat()
            }
            maxLines = 4
        }
        inputField = input

        val mic = iconTextButton(themeColor, "🎤") { onMicTapped() }
        micButton = mic
        // Separate mute toggle - user: "u can mute the microphone so he dosnt hear" - distinct
        // from tapping the mic itself to talk.
        val mute = iconTextButton(themeColor, "🔊") { toggleMute() }
        muteButton = mute
        val cameraButton = iconTextButton(themeColor, "📷") { startCameraCapture() }
        val attachButton = iconTextButton(themeColor, "📎") { startFilePick() }
        val send = iconTextButton(themeColor, "➤") { sendCurrentInput() }
        sendButton = send

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            gravity = Gravity.CENTER_VERTICAL
            addView(mic)
            addView(mute)
            addView(cameraButton)
            addView(attachButton)
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            })
            addView(send)
        }

        // Back button needs a FrameLayout parent for its own FrameLayout.LayoutParams
        // (gravity/margins) to actually apply - a bare LinearLayout would silently drop those
        // via generateLayoutParams's width/height-only fallback.
        val backRow = FrameLayout(this).apply {
            addView(buildBackButton(themeColor) { showXenos() })
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(backRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(inputRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return column
    }

    private fun iconTextButton(themeColor: Int, label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 20f
            setTextColor(themeColor)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { onClick() }
        }

    private fun showChat() {
        xenosContainer?.visibility = View.GONE
        chatContainer?.visibility = View.VISIBLE
    }

    private fun showXenos() {
        chatContainer?.visibility = View.GONE
        xenosContainer?.visibility = View.VISIBLE
    }

    /** Manual nudge only, never required - listening is automatic while your face is visible
     * (see [startContinuousListening]). This just force-restarts the cycle in case a session
     * ever gets stuck. */
    private fun onMicTapped() {
        if (isMuted) {
            Toast.makeText(this, "Microphone is muted.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!facePresentState.value) {
            Toast.makeText(this, "Show your face to Xenos to talk to it.", Toast.LENGTH_SHORT).show()
            return
        }
        stopContinuousListening()
        startContinuousListening()
    }

    private fun toggleMute() {
        isMuted = !isMuted
        muteButton?.text = if (isMuted) "🔇" else "🔊"
        if (isMuted) {
            stopContinuousListening()
        } else if (facePresentState.value) {
            startContinuousListening()
        }
    }

    /** Starts the back-to-back SpeechRecognizer loop if it isn't already running - safe/idempotent
     * to call repeatedly (e.g. on every face-presence-changed tick while a face stays visible). */
    private fun startContinuousListening() {
        if (listeningActive) return
        listeningActive = true
        runListenCycle()
    }

    private fun stopContinuousListening() {
        listeningActive = false
        speechRecognizer?.let { runCatching { it.stopListening(); it.destroy() } }
        speechRecognizer = null
        isCurrentlyListening = false
    }

    /** One SpeechRecognizer session; on a result or error it tears itself down and, if
     * [listeningActive] is still true, starts the next one - the "continuous" part. Skips
     * starting a fresh session while Xenos is actually speaking ([isSpeakingState]), so it never
     * transcribes its own TTS reply back into a new turn - [speak]'s onStart/onDone explicitly
     * stop/resume this loop around that window too, for the session already in flight. */
    private fun runListenCycle() {
        if (!listeningActive || isCurrentlyListening || isSpeakingState.value) return
        val hasAudio = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasAudio) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onResults(results: Bundle) {
                isCurrentlyListening = false
                runCatching { recognizer.destroy() }
                if (speechRecognizer === recognizer) speechRecognizer = null
                val spoken = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!spoken.isNullOrBlank()) sendText(spoken)
                if (listeningActive) runListenCycle()
            }
            override fun onError(error: Int) {
                isCurrentlyListening = false
                runCatching { recognizer.destroy() }
                if (speechRecognizer === recognizer) speechRecognizer = null
                // Brief delay before retrying - a tight error loop (e.g. ERROR_NO_MATCH firing
                // instantly every cycle in silence) would otherwise burn CPU/battery for nothing.
                if (listeningActive) micHandler.postDelayed({ runListenCycle() }, 400L)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        isCurrentlyListening = true
        runCatching { recognizer.startListening(intent) }.onFailure { isCurrentlyListening = false }
    }

    /** Speaks a reply aloud - real ElevenLabs audio (same voice MainActivity's Elene speaks with,
     * via EleneApiClient.fetchTtsAudio) first, falling back to the bare on-device system voice
     * only if that fetch/playback fails. User: "it using female voice when we already have a
     * particular voice we given it" - the bare `tts.speak(...)` this used before never tried the
     * real configured voice at all. Pulses the mic button for the duration (user: "when he is
     * talking let the microphone move"), independent of [isMuted], which only gates listening. */
    private fun speak(text: String) {
        activityScope.launch {
            val audio = runCatching { EleneApiClient.fetchTtsAudio(text) }.getOrNull()
            val played = audio != null && playAudioBytes(audio)
            if (!played) speakSystemTts(text)
        }
    }

    private fun onSpeechPlaybackStart() {
        startMicPulse()
        isSpeakingState.value = true
        // Stop the in-flight listening session (if any) so Xenos doesn't pick up and transcribe
        // its own reply as a new turn - resumed in onSpeechPlaybackDone below.
        speechRecognizer?.let { runCatching { it.stopListening(); it.cancel(); it.destroy() } }
        speechRecognizer = null
        isCurrentlyListening = false
    }

    private fun onSpeechPlaybackDone() {
        stopMicPulse()
        isSpeakingState.value = false
        // Settle back to neutral once Xenos has actually finished saying its reaction, rather
        // than holding an expression indefinitely until the next exchange.
        expressionState.value = "neutral"
        if (listeningActive) runListenCycle()
    }

    /** Plays [bytes] as mp3 via MediaPlayer, same approach MainActivity's own playAudioBytes
     * uses. Returns true if playback actually started (async - the real onSpeechPlaybackStart/
     * Done calls happen from the player's own listeners, not this function's return). */
    private suspend fun playAudioBytes(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(cacheDir, "xenos_tts_${System.currentTimeMillis()}.mp3")
            file.writeBytes(bytes)
            withContext(Dispatchers.Main) {
                mediaPlayer?.let { runCatching { it.release() } }
                val player = android.media.MediaPlayer()
                mediaPlayer = player
                player.setOnPreparedListener {
                    onSpeechPlaybackStart()
                    it.start()
                }
                player.setOnCompletionListener {
                    onSpeechPlaybackDone()
                    runCatching { it.release() }
                    if (mediaPlayer === it) mediaPlayer = null
                    runCatching { file.delete() }
                }
                player.setOnErrorListener { mp, _, _ ->
                    onSpeechPlaybackDone()
                    runCatching { mp.release() }
                    if (mediaPlayer === mp) mediaPlayer = null
                    runCatching { file.delete() }
                    true
                }
                player.setDataSource(file.absolutePath)
                player.prepareAsync()
            }
            true
        }.getOrDefault(false)
    }

    private fun speakSystemTts(text: String) {
        val engine = tts
        if (engine == null) {
            onSpeechPlaybackDone()
            return
        }
        val utteranceId = "xenos_${System.currentTimeMillis()}"
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread { onSpeechPlaybackStart() }
            }
            override fun onDone(utteranceId: String?) {
                runOnUiThread { onSpeechPlaybackDone() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread { onSpeechPlaybackDone() }
            }
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun startMicPulse() {
        val target = micButton ?: return
        micPulseAnimator?.cancel()
        val scaleX = android.animation.PropertyValuesHolder.ofFloat("scaleX", 1f, 1.35f, 1f)
        val scaleY = android.animation.PropertyValuesHolder.ofFloat("scaleY", 1f, 1.35f, 1f)
        micPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(target, scaleX, scaleY).apply {
            duration = 500
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopMicPulse() {
        micPulseAnimator?.cancel()
        micPulseAnimator = null
        micButton?.scaleX = 1f
        micButton?.scaleY = 1f
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
        }
    }

    private fun startCameraCapture() {
        val hasCamera = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCamera) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQUEST_CAMERA)
            return
        }
        runCatching { cameraLauncher.launch(null) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            runCatching { cameraLauncher.launch(null) }
        }
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.any { it == PackageManager.PERMISSION_GRANTED } && listeningActive) {
            runListenCycle()
        }
    }

    private fun startFilePick() {
        runCatching { galleryLauncher.launch("image/*") }
    }

    private fun sendCurrentInput() {
        val text = inputField?.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        inputField?.setText("")
        sendText(text)
    }

    private fun sendText(text: String) {
        addUserMessage(text)
        // Instant local pre-set on a direct "smile" request, so it doesn't wait on the network
        // round-trip - user: "if i tell it to smile am expecting it to but it not". The real
        // backend "emotion" below overrides this once the actual reply lands.
        if (text.contains("smile", ignoreCase = true)) expressionState.value = "smile"
        // Elene's backend runs on Cloud Run at minScale=0 - a cold start alone can take several
        // seconds (documented elsewhere in this project as ~9.5s), on top of the model call
        // itself. A placeholder that gets replaced in place (not a separate "done" message) so
        // the wait reads as "working," not stalled/broken.
        val thinkingIndex = messages.size
        addAiMessage("Xenos is thinking...")
        activityScope.launch {
            val response = EleneApiClient.sendText("launcher-user", text, emptyMap())
            val reply = response?.reply ?: "Xenos didn't respond - check your connection."
            replaceMessage(thinkingIndex, reply)
            // The AI's own genuine reaction, per main.py's "Your face" section - never mirrors
            // your expression, only Xenos's - user: "let it express eemotion like frown or
            // curios... let it know he can do that".
            expressionState.value = response?.emotion ?: "neutral"
            speak(reply)
        }
    }

    /** Reuses describeScreen (image + optional question -> plain-text description), the same
     * vision call the existing "what's on my screen" feature already relies on - not a new
     * backend endpoint, just pointed at a photo the user took/picked instead of a screenshot. */
    private fun sendPhoto(bitmap: Bitmap) {
        val question = inputField?.text?.toString()?.trim()?.ifBlank { null }
        inputField?.setText("")
        messages.add(ChatMessage(isUser = true, text = question ?: "(photo)", thumbnail = bitmap))
        val thinkingIndex = messages.size
        addAiMessage("Xenos is looking...")
        activityScope.launch {
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            val description = EleneApiClient.describeScreen(bytes, question)
            val reply = description ?: "Couldn't make sense of that image - check your connection."
            replaceMessage(thinkingIndex, reply)
            speak(reply)
        }
    }

    private fun addUserMessage(text: String) {
        messages.add(ChatMessage(isUser = true, text = text))
        renderMessages()
    }

    private fun addAiMessage(text: String) {
        messages.add(ChatMessage(isUser = false, text = text))
        renderMessages()
    }

    /** Updates a "thinking..." placeholder in place once the real reply arrives, rather than
     * appending a second message - keeps one bubble per turn, not a visible "thinking" bubble
     * left behind. */
    private fun replaceMessage(index: Int, text: String) {
        if (index !in messages.indices) {
            addAiMessage(text)
            return
        }
        messages[index] = messages[index].copy(text = text)
        renderMessages()
    }

    private fun renderMessages() {
        val list = messageList ?: return
        list.removeAllViews()
        for (msg in messages) {
            msg.thumbnail?.let { bmp ->
                list.addView(ImageView(this).apply {
                    setImageBitmap(bmp)
                    layoutParams = LinearLayout.LayoutParams(dp(160), dp(160)).apply {
                        gravity = if (msg.isUser) Gravity.END else Gravity.START
                        bottomMargin = dp(4)
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                })
            }
            list.addView(TextView(this).apply {
                text = msg.text
                setTextColor(if (msg.isUser) Color.WHITE else Color.parseColor("#4CAF50"))
                typeface = Typeface.MONOSPACE
                textSize = 14f
                background = GradientDrawable().apply {
                    setColor(if (msg.isUser) Color.argb(160, 40, 40, 40) else Color.argb(160, 10, 30, 10))
                    cornerRadius = dp(10).toFloat()
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = if (msg.isUser) Gravity.END else Gravity.START
                    bottomMargin = dp(10)
                }
            })
        }
        messageScroll?.post { messageScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun currentThemeColorArgb(): Int {
        val idx = getSharedPreferences("theme_prefs", MODE_PRIVATE).getInt("theme_index", 0)
        return CedalThemes[idx % CedalThemes.size].primary.let {
            Color.argb((it.alpha * 255).toInt(), (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt())
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildBackButton(themeColor: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        text = "◂ BACK"
        setTextColor(themeColor)
        textSize = 14f
        typeface = Typeface.MONOSPACE
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply {
            setColor(Color.argb(128, 0, 0, 0))
            cornerRadius = dp(8).toFloat()
        }
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { onClick() }
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(dp(16), dp(16), dp(16), dp(16))
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        micPulseAnimator?.cancel()
        micHandler.removeCallbacksAndMessages(null)
        stopContinuousListening()
        mediaPlayer?.let { runCatching { it.release() } }
        mediaPlayer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
