#include <jni.h>
#include <vector>

#include "rnnoise.h"
#include "speex_resampler.h"

namespace {
constexpr int RNNOISE_FRAME = 480; // 10ms @ 48kHz, fixed by the model's own design

std::vector<float> resample(const float *in, size_t inLen, int inRate, int outRate) {
    int err = 0;
    SpeexResamplerState *st = speex_resampler_init(1, inRate, outRate, 10, &err);
    if (st == nullptr) return {};
    auto inCount = static_cast<spx_uint32_t>(inLen);
    auto outCount = static_cast<spx_uint32_t>(inLen) * static_cast<spx_uint32_t>(outRate) / static_cast<spx_uint32_t>(inRate) + 64;
    std::vector<float> out(outCount);
    speex_resampler_process_float(st, 0, in, &inCount, out.data(), &outCount);
    speex_resampler_destroy(st);
    out.resize(outCount);
    return out;
}
} // namespace

// Real-noise-suppression pass before the fbank/ECAPA pipeline: classic RNNoise
// (BSD-3-Clause, xiph/rnnoise v0.1) operates on raw int16-range float samples
// at 48kHz in fixed 480-sample (10ms) frames - confirmed against the
// project's own reference demo, not assumed - so this resamples our native
// 16kHz audio up, denoises, and resamples back down.
extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_example_scifilauncher_NoiseSuppressor_denoise16k(
    JNIEnv *env, jobject /*thiz*/, jfloatArray audio) {
  jsize n = env->GetArrayLength(audio);
  std::vector<float> samples(static_cast<size_t>(n));
  env->GetFloatArrayRegion(audio, 0, n, samples.data());

  for (auto &s : samples) s *= 32768.0f;

  std::vector<float> at48k = resample(samples.data(), samples.size(), 16000, 48000);
  if (at48k.empty()) {
    return env->NewFloatArray(0);
  }

  // Pad the final partial frame with zeros rather than dropping trailing audio.
  size_t padded = ((at48k.size() + RNNOISE_FRAME - 1) / RNNOISE_FRAME) * RNNOISE_FRAME;
  at48k.resize(padded, 0.0f);

  DenoiseState *st = rnnoise_create();
  for (size_t i = 0; i < at48k.size(); i += RNNOISE_FRAME) {
    rnnoise_process_frame(st, at48k.data() + i, at48k.data() + i);
  }
  rnnoise_destroy(st);

  std::vector<float> back16k = resample(at48k.data(), at48k.size(), 48000, 16000);
  for (auto &s : back16k) s /= 32768.0f;

  jfloatArray result = env->NewFloatArray(static_cast<jsize>(back16k.size()));
  env->SetFloatArrayRegion(result, 0, static_cast<jsize>(back16k.size()), back16k.data());
  return result;
}
