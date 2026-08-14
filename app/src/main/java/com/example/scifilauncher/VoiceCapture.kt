package com.example.scifilauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Records [durationSamples] samples (16kHz mono) for speaker-embedding use. Returns null if
 * RECORD_AUDIO isn't granted or the recorder fails to init - callers should treat that as
 * "couldn't capture," not as a failed voice match. Does NOT stop early on silence - enrollment
 * needs the full requested duration even through natural pauses/breaths, so trimming only
 * happens afterward, on the complete recording.
 */
suspend fun recordVoiceSample(
    context: Context,
    durationSamples: Int = VOICE_SAMPLE_COUNT,
    requestFocus: Boolean = true
): FloatArray? =
    withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return@withContext null

        val minBufSize = AudioRecord.getMinBufferSize(
            VOICE_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufSize <= 0) return@withContext null

        val bufSize = maxOf(minBufSize, durationSamples * 2)
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                VOICE_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        }.getOrNull() ?: return@withContext null

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withContext null
        }

        // Real acoustic echo cancellation - only possible because this path owns its own
        // AudioRecord session directly. Elene's own TTS (or any other playback) picked up by
        // the mic gets subtracted here rather than just hoping nothing was playing.
        val aec = runCatching {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(recorder.audioSessionId) else null
        }.getOrNull()
        aec?.enabled = true

        // Skippable for callers that poll in the background (e.g. the "Hey Elene" audio wake-
        // word check, which runs every few seconds while dormant) - grabbing transient focus
        // that often would duck/pause the user's media for no real reason most of the time,
        // exactly the annoyance the STT-based wake check already avoids by skipping AudioFocus
        // entirely for passive checks.
        val focusHandle = if (requestFocus) requestAudioFocus(context, transient = true) else null

        val pcm = ShortArray(durationSamples)
        try {
            recorder.startRecording()
            var readTotal = 0
            while (readTotal < pcm.size) {
                val read = recorder.read(pcm, readTotal, pcm.size - readTotal)
                if (read <= 0) break
                readTotal += read
            }
            if (readTotal < pcm.size) return@withContext null
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            runCatching { aec?.release() }
            if (requestFocus) releaseAudioFocus(context, focusHandle)
        }

        val raw = FloatArray(pcm.size) { i -> pcm[i] / 32768f }
        val denoised = runCatching { NoiseSuppressor.denoise16k(raw) }.getOrDefault(raw)
        trimSilenceVad(context, denoised)
    }

/** Below this, a "sample" isn't real speech - a late start, a mic hiccup, or VAD finding
 * nothing. Far under even the shortest style (SHORT, a 2s recording of one word), so this
 * only rejects genuinely bad takes, not quiet-but-real ones. */
private const val MIN_SPEECH_SAMPLES = (300L * VOICE_SAMPLE_RATE / 1000).toInt()

/**
 * Trims leading/trailing silence using Silero VAD's real speech-probability output, rather than
 * a fixed energy threshold that can't tell real speech from a loud room tone. Returns the
 * shorter active-speech region as-is (no zero-padding) since the ECAPA-TDNN model accepts
 * variable-length input natively, and padding would only add meaningless silence frames into
 * the per-utterance mean normalization.
 *
 * Returns null - a real capture failure, same as a mic error - when VAD finds no speech at all
 * or the trimmed region is too short to be a genuine utterance. Previously this fell back to
 * returning the untrimmed (possibly all-silence) buffer, which meant a bad take - dead air,
 * VAD failing to catch the recording - got silently embedded and stored as a permanent
 * reference in the enrollment pool instead of being rejected and retried.
 */
private fun trimSilenceVad(context: Context, samples: FloatArray): FloatArray? {
    val chunkSize = 512 // fixed by Silero VAD for 16kHz input
    val chunkCount = samples.size / chunkSize
    if (chunkCount == 0) return null

    // VAD itself being unavailable (model load failure) is a different situation from VAD
    // running and finding nothing - don't fail capture over that, just skip trimming.
    val vad = runCatching { SileroVad(context) }.getOrNull() ?: return samples
    val speechProb = FloatArray(chunkCount)
    try {
        for (c in 0 until chunkCount) {
            val chunk = samples.copyOfRange(c * chunkSize, (c + 1) * chunkSize)
            speechProb[c] = runCatching { vad.speechProbability(chunk) }.getOrDefault(0f)
        }
    } finally {
        vad.close()
    }

    val threshold = 0.5f
    var firstActive = speechProb.indexOfFirst { it >= threshold }
    var lastActive = speechProb.indexOfLast { it >= threshold }
    if (firstActive < 0 || lastActive < 0) return null

    val padChunks = 3
    firstActive = maxOf(0, firstActive - padChunks)
    lastActive = minOf(chunkCount - 1, lastActive + padChunks)

    val speechStart = firstActive * chunkSize
    val speechEnd = minOf(samples.size, (lastActive + 1) * chunkSize)
    val trimmed = samples.copyOfRange(speechStart, speechEnd)
    return if (trimmed.size < MIN_SPEECH_SAMPLES) null else trimmed
}
