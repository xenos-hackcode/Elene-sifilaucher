package com.example.scifilauncher

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

private const val PREFS = "voice_id_prefs"
private const val MAX_REFERENCES_PER_STYLE = 12
private fun embeddingKey(style: VoiceStyle) = "embeddings_${style.name}"

/**
 * Offline speaker verification on top of SpeakerEmbedder (ECAPA-TDNN via ONNX). Enrollment
 * grows a per-style POOL of reference embeddings rather than averaging them into one vector -
 * a single averaged reference collapses natural variation (accent shift, tired voice, sick
 * voice) into one blurry point that any one real session might fall outside of. Re-enrolling
 * adds another reference point to the pool instead of replacing it (multi-session enrollment);
 * verification matches against whichever stored reference is closest, not an average of all of
 * them. Capped per style so the pool doesn't grow without bound.
 */
object VoiceIdManager {
    fun isEnrolled(context: Context): Boolean =
        VoiceStyle.entries.all { isStyleEnrolled(context, it) }

    fun isStyleEnrolled(context: Context, style: VoiceStyle): Boolean =
        !prefs(context).getStringSet(embeddingKey(style), null).isNullOrEmpty()

    fun enrolledStyles(context: Context): Set<VoiceStyle> =
        VoiceStyle.entries.filter { isStyleEnrolled(context, it) }.toSet()

    suspend fun enrollStyle(context: Context, style: VoiceStyle, rawSamples: List<FloatArray>): Boolean =
        withContext(Dispatchers.Default) {
            if (rawSamples.isEmpty()) return@withContext false
            runCatching {
                val embedder = SpeakerEmbedder(context)
                try {
                    val existing = loadEmbeddings(context, style).toMutableList()
                    rawSamples.forEach { existing.add(embedder.embed(it)) }
                    val capped = if (existing.size > MAX_REFERENCES_PER_STYLE) {
                        existing.takeLast(MAX_REFERENCES_PER_STYLE)
                    } else {
                        existing
                    }
                    saveEmbeddings(context, style, capped)
                    true
                } finally {
                    embedder.close()
                }
            }.getOrElse {
                SystemEventLog.record(context, "VoiceID", "enrollStyle(${style.name}) failed: ${it.message}")
                false
            }
        }

    /** Returns the highest cosine similarity against any of this style's stored reference
     * points, or null if that style isn't enrolled - also null if the model itself fails to
     * load/run (falls back to fingerprint-only, same as "not enrolled"). Compare against
     * `style.threshold`. */
    suspend fun verify(context: Context, rawSample: FloatArray, style: VoiceStyle): Float? =
        withContext(Dispatchers.Default) {
            val stored = loadEmbeddings(context, style)
            if (stored.isEmpty()) return@withContext null
            runCatching {
                val embedder = SpeakerEmbedder(context)
                try {
                    val live = embedder.embed(rawSample)
                    stored.maxOf { cosineSimilarity(it, live) }
                } finally {
                    embedder.close()
                }
            }.getOrElse {
                SystemEventLog.record(context, "VoiceID", "verify(${style.name}) failed: ${it.message}")
                null
            }
        }

    /** For arbitrary speech where the utterance's "style" isn't known up front (general voice
     * commands/chat) - embeds once, compares against every enrolled style's best-matching
     * reference, and returns whichever scored highest relative to its own threshold. Null if
     * nothing enrolled, or if the model itself fails to load/run. */
    suspend fun verifyBest(context: Context, rawSample: FloatArray): Pair<VoiceStyle, Float>? =
        withContext(Dispatchers.Default) {
            val styles = enrolledStyles(context)
            if (styles.isEmpty()) return@withContext null
            runCatching {
                val embedder = SpeakerEmbedder(context)
                try {
                    val live = embedder.embed(rawSample)
                    styles.mapNotNull { style ->
                        val refs = loadEmbeddings(context, style)
                        if (refs.isEmpty()) null else style to refs.maxOf { cosineSimilarity(it, live) }
                    }.maxByOrNull { (style, score) -> score - style.threshold }
                } finally {
                    embedder.close()
                }
            }.getOrElse {
                SystemEventLog.record(context, "VoiceID", "verifyBest failed: ${it.message}")
                null
            }
        }

    fun reset(context: Context) {
        val editor = prefs(context).edit()
        VoiceStyle.entries.forEach { editor.remove(embeddingKey(it)) }
        editor.apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun saveEmbeddings(context: Context, style: VoiceStyle, embeddings: List<FloatArray>) {
        val encoded = embeddings.map { embedding ->
            val buf = ByteBuffer.allocate(embedding.size * 4)
            for (v in embedding) buf.putFloat(v)
            Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        }.toSet()
        prefs(context).edit().putStringSet(embeddingKey(style), encoded).apply()
    }

    private fun loadEmbeddings(context: Context, style: VoiceStyle): List<FloatArray> {
        val set = prefs(context).getStringSet(embeddingKey(style), null) ?: return emptyList()
        return set.mapNotNull { b64 ->
            val bytes = runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull() ?: return@mapNotNull null
            if (bytes.size != VOICE_EMBEDDING_DIM * 4) return@mapNotNull null
            val buf = ByteBuffer.wrap(bytes)
            FloatArray(VOICE_EMBEDDING_DIM) { buf.float }
        }
    }
}
