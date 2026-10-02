package com.example.scifilauncher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.view.KeyEvent

data class NowPlayingInfo(
    val packageName: String,
    val appName: String,
    val title: String,
    val artist: String,
    val albumArt: Bitmap?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val playbackSpeed: Float,
    val canSeek: Boolean,
    val canSkipNext: Boolean,
    val canSkipPrevious: Boolean,
    // ACTION_SET_PLAYBACK_SPEED is API 31+ on the platform MediaSession - unlike seek/skip,
    // there's no androidx fallback for it, so speed buttons simply don't show below API 31.
    val canSetSpeed: Boolean
)

/**
 * Real playback control (play/pause, seek to an exact duration, skip) via
 * MediaSessionManager.getActiveSessions() - not a guessed tap on a notification icon. This
 * requires the caller to have an enabled NotificationListenerService, which
 * XenosNotificationListener already is, so no extra permission is needed beyond the
 * notification access the rest of this feature already depends on.
 */
object MediaSessionBridge {

    private fun listenerComponent(context: Context) =
        ComponentName(context, XenosNotificationListener::class.java)

    private fun activeController(context: Context): MediaController? {
        if (!isNotificationListenerEnabled(context)) return null
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return null
        return runCatching {
            manager.getActiveSessions(listenerComponent(context))
                .firstOrNull { it.playbackState != null && it.metadata != null }
        }.getOrNull()
    }

    fun currentNowPlaying(context: Context): NowPlayingInfo? {
        val controller = activeController(context) ?: return null
        val metadata = controller.metadata ?: return null
        val state = controller.playbackState ?: return null

        val appName = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(controller.packageName, 0)).toString()
        }.getOrDefault(controller.packageName)

        // PlaybackState only gives the position as of its last update - while actually
        // playing, interpolate forward by elapsed real time so the scrubber moves smoothly
        // between polls instead of visibly jumping once a second. getLastPositionUpdateTime()
        // is documented as being on the SystemClock.elapsedRealtime() timebase (time since
        // boot), NOT wall-clock time - diffing it against System.currentTimeMillis() here
        // produced a huge bogus "elapsed" value, which is why every playing track's position
        // always read as sitting at its own duration (and, worse, made the loop feature think
        // every track was constantly at its end - see MainActivity's loop-detection comment).
        val position = if (state.state == PlaybackState.STATE_PLAYING) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            (state.position + (elapsed * state.playbackSpeed).toLong()).coerceAtLeast(0L)
        } else {
            state.position.coerceAtLeast(0L)
        }
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)

        return NowPlayingInfo(
            packageName = controller.packageName,
            appName = appName,
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
            albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART),
            isPlaying = state.state == PlaybackState.STATE_PLAYING,
            positionMs = if (duration > 0) position.coerceAtMost(duration) else position,
            durationMs = duration,
            playbackSpeed = state.playbackSpeed,
            canSeek = (state.actions and PlaybackState.ACTION_SEEK_TO) != 0L,
            canSkipNext = (state.actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L,
            canSkipPrevious = (state.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L,
            // Not gated on ACTION_SET_PLAYBACK_SPEED being declared in state.actions - confirmed
            // live (see playPause()'s comment on com.harmix.player.editmusic) that an app's own
            // declared actions bitmask isn't a reliable predictor of what it actually honors,
            // so this only guards the one thing that's a real crash risk: calling
            // setPlaybackSpeed() below API 31 throws NoSuchMethodError.
            canSetSpeed = Build.VERSION.SDK_INT >= 31
        )
    }

    /** Unconditionally starts playback (never toggles to pause) - for "play music" style voice
     * commands, where the intent is always "start", regardless of whatever happened to already
     * be playing. [playPause] stays toggle-based for the Now Playing card's own button. */
    fun play(context: Context) {
        val controller = activeController(context) ?: return
        controller.transportControls.play()
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    fun playPause(context: Context) {
        val controller = activeController(context) ?: return
        val state = controller.playbackState ?: return
        if (state.state == PlaybackState.STATE_PLAYING) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
            // Confirmed live: com.harmix.player.editmusic implements onPause()/onSeekTo() but
            // never onPlay() at all - transportControls.play() alone silently did nothing for
            // it (checked via dumpsys media_session, state didn't budge). A real hardware
            // media-button event reaches it anyway, because it's routed by the system to
            // whichever app currently holds the active session, independent of whether that
            // app bothered to implement the modern MediaSession.Callback for it. Harmless to
            // send alongside the proper call: a redundant "play" is a no-op for apps that
            // already handled transportControls.play() correctly.
            dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)
        }
    }

    fun seekTo(context: Context, positionMs: Long) {
        activeController(context)?.transportControls?.seekTo(positionMs)
    }

    fun skipNext(context: Context) {
        activeController(context)?.transportControls?.skipToNext()
    }

    fun skipPrevious(context: Context) {
        activeController(context)?.transportControls?.skipToPrevious()
    }

    fun setPlaybackSpeed(context: Context, speed: Float) {
        if (Build.VERSION.SDK_INT >= 31) {
            activeController(context)?.transportControls?.setPlaybackSpeed(speed)
        }
    }

    /** There's no real platform "repeat/loop" control (see NowPlayingCard's comment for why),
     * so this is our own honest approximation: seek back to the start and make sure playback
     * is actually resumed. Only ever called by MainActivity's own polling loop, and only while
     * the track is confirmed already playing. Confirmed live against
     * com.harmix.player.editmusic that seekTo() alone isn't enough here either - this app
     * pauses itself as a side effect of handling the seek and, since it never implements
     * onPlay() (see playPause()'s comment), needs the same media-button fallback to actually
     * come back out of that pause. */
    fun restartFromBeginning(context: Context) {
        val controller = activeController(context) ?: return
        controller.transportControls.seekTo(0L)
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    /** The same signal a physical play/pause button (or `adb shell input keyevent
     * KEYCODE_MEDIA_PLAY`) sends - the system routes it to whichever app currently holds the
     * active media session, regardless of whether that app implemented the modern
     * MediaSession.Callback.onPlay(). */
    private fun dispatchMediaKey(context: Context, keyCode: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val eventTime = System.currentTimeMillis()
        runCatching {
            audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
            audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
        }
    }
}
