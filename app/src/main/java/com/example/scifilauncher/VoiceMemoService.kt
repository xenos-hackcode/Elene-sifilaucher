package com.example.scifilauncher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Explicit "start recording"/"stop recording" voice memo, distinct from screen recording -
 * audio-only, so none of ScreenRecordService's MediaProjection/VirtualDisplay/process-isolation
 * machinery applies here (that existed specifically because MediaProjection is crash-prone;
 * plain mic-only MediaRecorder isn't). Only ever runs because the user explicitly asked for it
 * ("start recording") - never triggered passively. */
class VoiceMemoService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null

    companion object {
        var isRecording: Boolean = false
            private set
        private const val CHANNEL_ID = "voice_memo"
        private const val NOTIFICATION_ID = 4823
        private const val ACTION_STOP = "com.example.scifilauncher.action.STOP_VOICE_MEMO"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, VoiceMemoService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, VoiceMemoService::class.java).setAction(ACTION_STOP))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }
        if (isRecording) return START_STICKY
        startForeground(NOTIFICATION_ID, buildNotification())
        val started = runCatching { startRecording() }.isSuccess
        if (!started) {
            android.util.Log.e("VoiceMemo", "Failed to start voice memo recording")
            stopSelf()
        }
        return START_STICKY
    }

    private fun startRecording() {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "SciFiLauncher/VoiceMemos")
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "memo_$stamp.m4a")
        outputFile = file

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioEncodingBitRate(128_000)
        recorder.setAudioSamplingRate(44100)
        recorder.setOutputFile(file.absolutePath)
        recorder.prepare()
        recorder.start()
        mediaRecorder = recorder
        isRecording = true
    }

    private fun stopRecording() {
        runCatching { mediaRecorder?.stop() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        isRecording = false
        outputFile?.let { file ->
            runCatching {
                android.media.MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
            }
        }
        outputFile = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (isRecording) {
            runCatching { mediaRecorder?.stop() }
            runCatching { mediaRecorder?.release() }
            mediaRecorder = null
            isRecording = false
        }
        super.onDestroy()
    }

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Voice Memo", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording voice memo")
            .setContentText("Saving to Music/SciFiLauncher/VoiceMemos")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
    }
}
