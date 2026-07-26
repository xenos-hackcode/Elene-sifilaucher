package com.example.scifilauncher

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import java.nio.LongBuffer

private const val MODEL_ASSET = "silero_vad.onnx"
private const val CHUNK_SAMPLES = 512 // fixed by the model for 16kHz input
private const val CONTEXT_SAMPLES = 64
private const val STATE_SIZE = 2 * 1 * 128

/**
 * Silero VAD (MIT license, snakers4/silero-vad) via ONNX Runtime - real neural voice-activity
 * detection instead of the earlier windowed-RMS-energy silence trim, which couldn't tell real
 * speech from a loud room tone/hum and had no real basis for its threshold. Confirmed against
 * the real downloaded model's input/output tensor names and shapes (input/state/sr ->
 * output/stateN), not assumed - the reference Python wrapper (silero-vad's own utils_vad.py)
 * showed this is a stateful model: each 512-sample chunk is prefixed with the last 64 samples
 * of the previous chunk ("context"), and an RNN state tensor threads across calls.
 */
class SileroVad(context: Context) {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(loadModelBytes(context), OrtSession.SessionOptions())

    private var state = FloatArray(STATE_SIZE)
    private var chunkContext = FloatArray(CONTEXT_SAMPLES)

    private fun loadModelBytes(context: Context): ByteArray =
        context.assets.open(MODEL_ASSET).use { it.readBytes() }

    fun reset() {
        state = FloatArray(STATE_SIZE)
        chunkContext = FloatArray(CONTEXT_SAMPLES)
    }

    /** [chunk] must be exactly CHUNK_SAMPLES (512) samples at 16kHz, normalized to [-1, 1].
     * Returns the model's speech probability for this chunk, in [0, 1]. */
    fun speechProbability(chunk: FloatArray): Float {
        require(chunk.size == CHUNK_SAMPLES) { "expected $CHUNK_SAMPLES samples, got ${chunk.size}" }

        val input = FloatArray(CONTEXT_SAMPLES + CHUNK_SAMPLES)
        chunkContext.copyInto(input)
        chunk.copyInto(input, CONTEXT_SAMPLES)

        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong())).use { inputTensor ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { stateTensor ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(16000)), longArrayOf()).use { srTensor ->
                    // Output order confirmed from the real model's own metadata: [output, stateN].
                    session.run(mapOf("input" to inputTensor, "state" to stateTensor, "sr" to srTensor)).use { results ->
                        @Suppress("UNCHECKED_CAST")
                        val prob = (results[0].value as Array<FloatArray>)[0][0]
                        @Suppress("UNCHECKED_CAST")
                        val newState = results[1].value as Array<Array<FloatArray>>
                        var idx = 0
                        for (a in newState) for (b in a) for (v in b) state[idx++] = v
                        chunkContext = chunk.copyOfRange(chunk.size - CONTEXT_SAMPLES, chunk.size)
                        return prob
                    }
                }
            }
        }
    }

    fun close() = session.close()
}
