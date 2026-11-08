package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.audio.AudioRecorderManager
import com.example.audio.RecorderState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class RecordingService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var tickerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        recorderManager = AudioRecorderManager(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_START -> {
                val format = intent?.getStringExtra(EXTRA_FORMAT) ?: "M4A"
                startForegroundRecording(format)
            }
            ACTION_PAUSE -> {
                recorderManager?.pauseRecording()
                updateNotification(isPaused = true)
            }
            ACTION_RESUME -> {
                recorderManager?.resumeRecording()
                updateNotification(isPaused = false)
            }
            ACTION_STOP -> {
                val file = recorderManager?.stopRecording()
                _lastRecordedFile.value = file
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundRecording(format: String) {
        val notification = buildNotification(isPaused = false, elapsedSec = 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else 0
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            val file = recorderManager?.startRecording(format)
            _lastRecordedFile.value = null
            _activeRecordingFile.value = file
            startTicker()
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var seconds = 0
            while (isActive && recorderManager?.recorderState?.value != RecorderState.STOPPED) {
                if (recorderManager?.recorderState?.value == RecorderState.RECORDING) {
                    seconds++
                    updateNotification(isPaused = false, elapsedSec = seconds)
                }
                delay(1000)
            }
        }
    }

    private fun updateNotification(isPaused: Boolean, elapsedSec: Int = 0) {
        val notification = buildNotification(isPaused, elapsedSec)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(isPaused: Boolean, elapsedSec: Int = 0): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeAction = if (isPaused) {
            val resumeIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_RESUME }
            val resumePending = PendingIntent.getService(
                this, 2, resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_play, "Resume", resumePending
            ).build()
        } else {
            val pauseIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_PAUSE }
            val pausePending = PendingIntent.getService(
                this, 3, pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_pause, "Pause", pausePending
            ).build()
        }

        val minutes = elapsedSec / 60
        val remainingSecs = elapsedSec % 60
        val timeFormatted = String.format("%02d:%02d", minutes, remainingSecs)
        val contentText = if (isPaused) "Recording paused ($timeFormatted)" else "Recording in progress ($timeFormatted)"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VoxFlow High-Fidelity Recorder")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(pauseResumeAction)
            .addAction(android.R.drawable.ic_menu_save, "Save", stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live audio recording status and controls"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        recorderManager?.stopRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.action.START_RECORDING"
        const val ACTION_PAUSE = "com.example.action.PAUSE_RECORDING"
        const val ACTION_RESUME = "com.example.action.RESUME_RECORDING"
        const val ACTION_STOP = "com.example.action.STOP_RECORDING"
        const val EXTRA_FORMAT = "extra_format"

        const val CHANNEL_ID = "voxflow_recording_channel"
        const val NOTIFICATION_ID = 1001

        var recorderManager: AudioRecorderManager? = null
            private set

        private val _lastRecordedFile = MutableStateFlow<File?>(null)
        val lastRecordedFile = _lastRecordedFile.asStateFlow()

        private val _activeRecordingFile = MutableStateFlow<File?>(null)
        val activeRecordingFile = _activeRecordingFile.asStateFlow()
    }
}
