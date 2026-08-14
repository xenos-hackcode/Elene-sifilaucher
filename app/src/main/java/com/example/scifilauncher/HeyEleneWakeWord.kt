package com.example.scifilauncher

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.sqrt

private const val PREFS = "hey_elene_wakeword_prefs"
private const val KEY_TEMPLATES = "templates"
private const val KEY_THRESHOLD = "threshold"
private const val FBANK_DIM = 80

/** ~2.5s - long enough to catch "Hey Elene" plus a late start, short enough to keep the
 * always-listening polling cycle responsive. Used for both enrollment takes and the live check
 * so template and live-capture lengths stay comparable. */
const val WAKE_WORD_CAPTURE_SAMPLES = (VOICE_SAMPLE_RATE * 2.5).toInt()

/**
 * Personalized, accent-independent "Hey Elene" detection - built after the default STT-text-
 * match wake check (ScifiAccessibilityService.containsWakeWord) kept missing for a real accent,
 * since that path depends entirely on Android's SpeechRecognizer transcribing the phrase
 * correctly before any matching even happens. This instead records the user's own reference
 * takes, extracts the same Kaldi-style fbank features already used for Voice ID (SpeakerFbank,
 * via the vendored kaldi-native-fbank JNI - reused as-is, no new native code needed), and matches
 * a live short capture against those templates with Dynamic Time Warping (DTW) - a classic,
 * deterministic template-matching technique that needs no model training (unlike the openWakeWord
 * path described in planner/not_started.md), well suited to a small personal reference set.
 * Never depends on STT correctness at all - the live capture bypasses SpeechRecognizer entirely.
 *
 * Honest limitation: DTW template matching is less robust than a real trained neural wake-word
 * model - it can still mis-trigger on other short speech-like sounds, more so than a purpose-
 * built detector would. That's an accepted tradeoff here, the same class of limitation already
 * accepted for the motion-based theft detector's confirm-or-arm flow - a false accept just opens
 * a normal listening turn, the same low-cost outcome a false STT match already has today, not a
 * security-relevant failure.
 */
object HeyEleneWakeWord {
    fun isEnrolled(context: Context): Boolean = loadTemplates(context).isNotEmpty()

    fun sampleCount(context: Context): Int = loadTemplates(context).size

    /** Replaces any existing enrollment with fresh templates extracted from [rawSamples] (16kHz
     * mono, already VAD-trimmed - see recordVoiceSample). The match threshold is calibrated from
     * the samples' own pairwise DTW distances (how consistent this user's own takes were against
     * each other), not a fixed guess - real-world use may still reveal it needs to be looser
     * (misses real attempts) or tighter (triggers on other speech), same as every other
     * perceptual threshold already tuned by real testing elsewhere in this app - re-recording is
     * the fix if so, not a settings knob, to keep this simple. */
    suspend fun enroll(context: Context, rawSamples: List<FloatArray>): Boolean =
        withContext(Dispatchers.Default) {
            if (rawSamples.size < 2) return@withContext false
            runCatching {
                val templates = rawSamples.map { SpeakerFbank.computeFbank(it, VOICE_SAMPLE_RATE) }
                var maxPairwise = 0f
                for (i in templates.indices) {
                    for (j in i + 1 until templates.size) {
                        maxPairwise = maxOf(maxPairwise, dtwDistance(templates[i], templates[j]))
                    }
                }
                // Margin over the worst-case pairwise distance among the user's own takes, so
                // natural day-to-day variation (not just the handful of takes just recorded)
                // still clears the bar - floored so unusually consistent takes don't collapse the
                // threshold to near-zero and reject everything in normal use.
                val threshold = maxOf(maxPairwise * 1.5f, 5f)
                saveTemplates(context, templates, threshold)
                true
            }.getOrElse {
                SystemEventLog.record(context, "HeyElene", "enroll failed: ${it.message}")
                false
            }
        }

    /** Captures one short clip directly (bypassing SpeechRecognizer/STT entirely, unlike the
     * default wake check) and compares it against the stored templates. Returns false (not just
     * null) on any capture or matching failure - same as "no match" - so a transient failure
     * never blocks the next scheduled retry a few seconds later. */
    suspend fun checkForWakeWord(context: Context): Boolean {
        val templates = loadTemplates(context)
        if (templates.isEmpty()) return false
        val threshold = loadThreshold(context) ?: return false

        // requestFocus = false: this runs silently every few seconds in the background, unlike
        // Voice ID's foreground capture - grabbing transient audio focus that often would
        // duck/pause the user's media for no reason most of the time.
        val clip = recordVoiceSample(context, WAKE_WORD_CAPTURE_SAMPLES, requestFocus = false)
            ?: return false

        return withContext(Dispatchers.Default) {
            runCatching {
                val live = SpeakerFbank.computeFbank(clip, VOICE_SAMPLE_RATE)
                templates.any { dtwDistance(live, it) <= threshold }
            }.getOrDefault(false)
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_TEMPLATES).remove(KEY_THRESHOLD).apply()
    }

    // ---- DTW ----

    /** Dynamic Time Warping distance between two flattened [numFrames * FBANK_DIM] fbank
     * sequences, normalized by path length so utterances of different length (natural speaking-
     * rate variation between takes) aren't penalized just for a frame-count difference the way a
     * fixed-length comparison would be. Lower = more similar. O(n*m); trivial at ~250 frames per
     * side for a 2.5s clip, run once every few seconds at most. */
    private fun dtwDistance(a: FloatArray, b: FloatArray): Float {
        val n = a.size / FBANK_DIM
        val m = b.size / FBANK_DIM
        if (n == 0 || m == 0) return Float.MAX_VALUE

        var prev = FloatArray(m + 1) { Float.MAX_VALUE / 2 }
        var curr = FloatArray(m + 1)
        prev[0] = 0f
        for (i in 1..n) {
            curr[0] = Float.MAX_VALUE / 2
            val aOff = (i - 1) * FBANK_DIM
            for (j in 1..m) {
                val bOff = (j - 1) * FBANK_DIM
                var sum = 0f
                for (d in 0 until FBANK_DIM) {
                    val diff = a[aOff + d] - b[bOff + d]
                    sum += diff * diff
                }
                val dist = sqrt(sum)
                curr[j] = dist + minOf(prev[j], curr[j - 1], prev[j - 1])
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[m] / (n + m)
    }

    // ---- storage ----

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun saveTemplates(context: Context, templates: List<FloatArray>, threshold: Float) {
        val arr = JSONArray()
        templates.forEach { flat ->
            val buf = ByteBuffer.allocate(flat.size * 4)
            for (v in flat) buf.putFloat(v)
            arr.put(JSONObject().apply {
                put("frames", flat.size / FBANK_DIM)
                put("data", Base64.encodeToString(buf.array(), Base64.NO_WRAP))
            })
        }
        prefs(context).edit()
            .putString(KEY_TEMPLATES, arr.toString())
            .putFloat(KEY_THRESHOLD, threshold)
            .apply()
    }

    private fun loadTemplates(context: Context): List<FloatArray> {
        val raw = prefs(context).getString(KEY_TEMPLATES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val frames = o.getInt("frames")
                val bytes = Base64.decode(o.getString("data"), Base64.NO_WRAP)
                if (bytes.size != frames * FBANK_DIM * 4) return@mapNotNull null
                val buf = ByteBuffer.wrap(bytes)
                FloatArray(frames * FBANK_DIM) { buf.float }
            }
        }.getOrDefault(emptyList())
    }

    private fun loadThreshold(context: Context): Float? {
        val v = prefs(context).getFloat(KEY_THRESHOLD, -1f)
        return if (v < 0f) null else v
    }
}
