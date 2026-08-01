cmake_minimum_required(VERSION 3.22.1)
project(scifilauncher_native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# kaldi-native-fbank (Apache 2.0, csukuangfj/kaldi-native-fbank) and kissfft
# (BSD-3-Clause, mborgerding/kissfft) are vendored directly under this
# directory rather than fetched at build time, so a clean build doesn't
# depend on GitHub being reachable.
include_directories(${CMAKE_CURRENT_SOURCE_DIR})
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/kissfft)

add_library(speaker_fbank SHARED
    speaker_fbank_jni.cpp
    kaldi-native-fbank/csrc/feature-fbank.cc
    kaldi-native-fbank/csrc/feature-functions.cc
    kaldi-native-fbank/csrc/feature-mfcc.cc
    kaldi-native-fbank/csrc/feature-raw-audio-samples.cc
    kaldi-native-fbank/csrc/feature-window.cc
    kaldi-native-fbank/csrc/kaldi-math.cc
    kaldi-native-fbank/csrc/log.cc
    kaldi-native-fbank/csrc/mel-computations.cc
    kaldi-native-fbank/csrc/online-feature.cc
    kaldi-native-fbank/csrc/rfft.cc
    kaldi-native-fbank/csrc/whisper-feature.cc
    kaldi-native-fbank/csrc/istft.cc
    kaldi-native-fbank/csrc/stft.cc
    kissfft/kiss_fft.c
    kissfft/kiss_fftr.c
)

target_link_libraries(speaker_fbank log)

# Android 15+ 16KB page-size requirement - aligns this library's ELF load
# segments. Doesn't affect onnxruntime's own prebuilt .so files (that's a
# separate, currently-unresolved issue in Microsoft's AAR, not fixable here).
target_link_options(speaker_fbank PRIVATE "-Wl,-z,max-page-size=16384")

# Classic RNNoise (v0.1 tag, BSD-style, xiph/rnnoise) + the Speex resampler
# (BSD-style, xiph/speexdsp) for the 16kHz<->48kHz round trip RNNoise needs -
# a SEPARATE shared library from speaker_fbank because RNNoise vendors its
# own (older, differently-versioned) copy of kiss_fft, which would collide
# at link time with the kissfft already compiled into speaker_fbank above.
add_library(denoise SHARED
    denoise_jni.cpp
    rnnoise/src/denoise.c
    rnnoise/src/rnn.c
    rnnoise/src/rnn_data.c
    rnnoise/src/pitch.c
    rnnoise/src/celt_lpc.c
    rnnoise/src/kiss_fft.c
    speex_resampler/resample.c
)
target_include_directories(denoise PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise/include
    ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise/src
    ${CMAKE_CURRENT_SOURCE_DIR}/speex_resampler
)
target_link_libraries(denoise log)
target_link_options(denoise PRIVATE "-Wl,-z,max-page-size=16384")
# OUTSIDE_SPEEX: builds the resampler in its standalone mode (self-contained
# malloc/free wrappers, flat include paths) rather than as part of a full
# libspeexdsp build. RANDOM_PREFIX: speex_resampler.h refuses to compile
# without a project-specific symbol prefix to avoid clashing with a system
# libspeexdsp, if one were ever loaded into the same process.
target_compile_definitions(denoise PRIVATE OUTSIDE_SPEEX RANDOM_PREFIX=scifi FLOATING_POINT)

# Layered native Frida detection - deliberately native/JNI rather than Kotlin, since Frida
# hooks the Java/ART layer far more easily than it hooks native code reading raw /proc files
# and opening raw sockets directly.
add_library(fridadetect SHARED
    frida_detect_jni.cpp
)
target_link_libraries(fridadetect log)
target_link_options(fridadetect PRIVATE "-Wl,-z,max-page-size=16384")
