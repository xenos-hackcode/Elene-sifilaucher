package com.example.scifilauncher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/** Requests real Android audio focus the way an actual voice assistant does, so other audio
 * that plays along automatically pauses/ducks instead of just being talked over or blocking
 * the mic entirely. [transient] picks between a brief interruption (a one-off voice-sample
 * recording - AUDIOFOCUS_GAIN_TRANSIENT, apps typically duck or briefly pause) and a real
 * hold for the duration of a conversation turn (continuous listening - plain AUDIOFOCUS_GAIN,
 * which most media apps treat as "stop, not just duck", since it doesn't promise to be brief).
 * Returns an opaque handle for [releaseAudioFocus]; null if the request failed (the caller
 * should proceed regardless - this is a courtesy to other audio, not a requirement to record). */
fun requestAudioFocus(context: Context, transient: Boolean): Any? {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
    val focusGain = if (transient) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val request = AudioFocusRequest.Builder(focusGain)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        val granted = runCatching { am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }.getOrDefault(false)
        if (granted) request else null
    } else {
        @Suppress("DEPRECATION")
        val granted = runCatching {
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, focusGain) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.getOrDefault(false)
        if (granted) am else null
    }
}

fun releaseAudioFocus(context: Context, handle: Any?) {
    if (handle == null) return
    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    runCatching {
        if (handle is AudioFocusRequest) {
            am.abandonAudioFocusRequest(handle)
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }
}
