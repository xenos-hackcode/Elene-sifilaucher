package com.example.scifilauncher

/** JNI bridge to classic RNNoise (app/src/main/cpp/rnnoise, v0.1, BSD-3-Clause) + the Speex
 * resampler (app/src/main/cpp/speex_resampler, BSD-style) - denoises 16kHz audio by resampling
 * it up to the 48kHz RNNoise actually operates at, running its DNN-based noise suppression,
 * and resampling back down. */
object NoiseSuppressor {
    init {
        System.loadLibrary("denoise")
    }

    /** [audio] is 16kHz mono PCM normalized to [-1, 1]. Returns denoised audio, same convention -
     * length may differ slightly from the input due to the resampling round trip. */
    external fun denoise16k(audio: FloatArray): FloatArray
}
