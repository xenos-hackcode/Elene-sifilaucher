package com.example.scifilauncher

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

private const val MODEL_ASSET = "ecapa_tdnn_speaker.onnx"
const val VOICE_SAMPLE_RATE = 16000
const val VOICE_SAMPLE_COUNT = 64000 // default 4s at 16kHz - the model itself accepts any length
const val VOICE_EMBEDDING_DIM = 192

/**
 * Wraps WeSpeaker's ECAPA-TDNN speaker-verification model (CC-BY-4.0, wenet-e2e/wespeaker),
 * run via ONNX Runtime - replaced an earlier attempt with Google's FRILL, which is a
 * general-purpose non-semantic embedding, not built for speaker verification, and showed real
 * same-speaker inconsistency in on-device testing (same voice scoring anywhere from 0.60-0.75).
 * ECAPA-TDNN is purpose-built for this and expects 80-dim Kaldi fbank features (via
 * SpeakerFbank/kaldi-native-fbank), not raw audio - input/output shapes and the exact feature
 * pipeline (hamming window, no dither, per-utterance mean normalization) were confirmed against
 * wespeaker's own inference reference and the downloaded model's real ONNX metadata, not assumed.
 */
class SpeakerEmbedder(context: Context) {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(loadModelBytes(context), OrtSession.SessionOptions())

    private fun loadModelBytes(context: Context): ByteArray =
        context.assets.open(MODEL_ASSET).use { it.readBytes() }

    /** [audio] is 16kHz mono PCM normalized to [-1, 1], any length (T is a dynamic axis). */
    fun embed(audio: FloatArray): FloatArray {
        val feats = SpeakerFbank.computeFbank(audio, VOICE_SAMPLE_RATE)
        val numFrames = feats.size / 80
        require(numFrames > 0) { "no fbank frames produced from audio of size ${audio.size}" }
        val shape = longArrayOf(1, numFrames.toLong(), 80)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(feats), shape).use { inputTensor ->
            session.run(mapOf("feats" to inputTensor)).use { results ->
                @Suppress("UNCHECKED_CAST")
                val output = results[0].value as Array<FloatArray>
                return output[0]
            }
        }
    }

    fun close() = session.close()
}

fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size)
    var dot = 0f
    var normA = 0f
    var normB = 0f
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    if (normA == 0f || normB == 0f) return 0f
    return dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
}
