package com.example.scifilauncher

/** JNI bridge to the vendored kaldi-native-fbank (app/src/main/cpp) - computes 80-dim
 * Kaldi-style fbank features (25ms/10ms frame length/shift, hamming window, no dither, no
 * energy, per-utterance mean-normalized) matching wespeaker's own inference reference exactly,
 * since the ECAPA-TDNN ONNX model expects features, not raw audio. */
object SpeakerFbank {
    init {
        System.loadLibrary("speaker_fbank")
    }

    /** Returns a flattened [numFrames * 80] row-major array - divide length by 80 for numFrames. */
    external fun computeFbank(audio: FloatArray, sampleRate: Int): FloatArray
}
