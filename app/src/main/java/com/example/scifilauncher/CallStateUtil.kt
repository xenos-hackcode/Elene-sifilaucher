package com.example.scifilauncher

import android.content.Context
import android.media.AudioManager

/** True while a call has taken over the device's audio - cellular OR VoIP (WhatsApp, Zoom,
 * Meet, anything), checked via AudioManager.mode rather than TelephonyManager so it catches
 * every kind of call, not just traditional cellular ones, and doesn't need READ_PHONE_STATE. */
fun isPhoneBusyWithCall(context: Context): Boolean {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
    return am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
}

/** True while ANY app is actively playing audio through the normal media stream - a video,
 * music, a voice recording/memo playing back, anything. Different mechanism than a call (this
 * isn't about mic contention, it's about the mic acoustically picking up whatever's playing out
 * loud and misreading dialogue/speech in it as a command), same conclusion either way. */
fun isMediaPlaying(context: Context): Boolean {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
    return runCatching { am.isMusicActive }.getOrDefault(false)
}

/** The single check Elene's continuous listening must pass before it's ever allowed to open the
 * mic - external audio only (the room, your actual voice), never a call, never whatever's
 * already playing out loud. */
fun shouldPauseListening(context: Context): Boolean =
    isPhoneBusyWithCall(context) || isMediaPlaying(context)
