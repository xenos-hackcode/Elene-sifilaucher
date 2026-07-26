#include <jni.h>
#include <vector>

#include "kaldi-native-fbank/csrc/online-feature.h"

// Feature extraction here must exactly match wespeaker's own inference
// reference (wespeaker/bin/infer_onnx.py, Apache 2.0, wenet-e2e/wespeaker):
// waveform scaled by 1<<15, 80-dim fbank, 25ms/10ms frame length/shift,
// hamming window, no energy, no dither, then per-utterance cepstral MEAN
// normalization (mean-only, no variance norm) across the time axis. A
// mismatch here wouldn't throw - it would just quietly produce worse
// embeddings than the model was trained/exported with.
extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_example_scifilauncher_SpeakerFbank_computeFbank(
    JNIEnv *env, jobject /*thiz*/, jfloatArray audio, jint sampleRate) {
  jsize n = env->GetArrayLength(audio);
  std::vector<float> samples(static_cast<size_t>(n));
  env->GetFloatArrayRegion(audio, 0, n, samples.data());

  for (auto &s : samples) s *= 32768.0f;

  knf::FbankOptions opts;
  opts.frame_opts.samp_freq = static_cast<float>(sampleRate);
  opts.frame_opts.dither = 0.0f;
  opts.frame_opts.window_type = "hamming";
  opts.mel_opts.num_bins = 80;
  opts.use_energy = false;

  knf::OnlineFbank fbank(opts);
  fbank.AcceptWaveform(static_cast<float>(sampleRate), samples.data(),
                       static_cast<int32_t>(samples.size()));
  fbank.InputFinished();

  int32_t numFrames = fbank.NumFramesReady();
  int32_t dim = fbank.Dim();

  if (numFrames <= 0) {
    return env->NewFloatArray(0);
  }

  std::vector<double> means(static_cast<size_t>(dim), 0.0);
  for (int32_t f = 0; f < numFrames; ++f) {
    const float *frame = fbank.GetFrame(f);
    for (int32_t d = 0; d < dim; ++d) means[static_cast<size_t>(d)] += frame[d];
  }
  for (int32_t d = 0; d < dim; ++d) means[static_cast<size_t>(d)] /= numFrames;

  std::vector<float> out(static_cast<size_t>(numFrames) * static_cast<size_t>(dim));
  for (int32_t f = 0; f < numFrames; ++f) {
    const float *frame = fbank.GetFrame(f);
    for (int32_t d = 0; d < dim; ++d) {
      out[static_cast<size_t>(f) * dim + d] =
          frame[d] - static_cast<float>(means[static_cast<size_t>(d)]);
    }
  }

  jfloatArray result = env->NewFloatArray(static_cast<jsize>(out.size()));
  env->SetFloatArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
  return result;
}
