package com.nexauren.archiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File

/** Keeps audio playing after the preview dialog closes, until Stop is tapped. */
class AudioPlaybackService : Service() {
    private var player: MediaPlayer? = null
    private var playerPrepared: Boolean = false
    private var trackName: String = "Archiver"
    private var initialPositionMs: Int = 0

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Reprodução de áudio", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Controlos de áudio em segundo plano"
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopPlayback(); stopForeground(true); stopSelf(); return START_NOT_STICKY }
            ACTION_TOGGLE -> {
                val active = player
                if (active != null && playerPrepared) {
                    try { if (active.isPlaying) active.pause() else active.start() } catch (_: Exception) { }
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
                }
            }
            ACTION_PLAY -> {
                val path = intent.getStringExtra(EXTRA_PATH)
                if (path.isNullOrBlank() || !File(path).isFile) { stopSelf(); return START_NOT_STICKY }
                trackName = File(path).name
                initialPositionMs = intent.getIntExtra(EXTRA_POSITION, 0).coerceAtLeast(0)
                startForeground(NOTIFICATION_ID, notification())
                startTrack(File(path), startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startTrack(file: File, startId: Int) {
        stopPlayback()
        try {
            val next = MediaPlayer(); player = next; playerPrepared = false
            next.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            next.setOnPreparedListener {
                if (player === it) {
                    if (initialPositionMs > 0) runCatching { it.seekTo(initialPositionMs.coerceAtMost((it.duration - 1).coerceAtLeast(0))) }
                    initialPositionMs = 0
                    playerPrepared = true
                    it.start()
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
                }
            }
            next.setOnCompletionListener { if (player === it) { stopPlayback(); stopForeground(true); stopSelfResult(startId) } }
            next.setOnErrorListener { _, _, _ -> stopPlayback(); stopForeground(true); stopSelfResult(startId); true }
            next.setDataSource(file.absolutePath); next.prepareAsync()
        } catch (_: Exception) { stopPlayback(); stopForeground(true); stopSelfResult(startId) }
    }

    private fun stopPlayback() {
        val old = player
        player = null
        playerPrepared = false
        try { old?.stop() } catch (_: Exception) { }
        try { old?.release() } catch (_: Exception) { }
    }

    private fun notification(): Notification {
        val openApp = PendingIntent.getActivity(this, 41, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val toggleIntent = PendingIntent.getService(this, 42, Intent(this, AudioPlaybackService::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 43, Intent(this, AudioPlaybackService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val active = player?.let { playerPrepared && runCatching { it.isPlaying }.getOrDefault(false) } == true
        val toggleLabel = if (active) "Pausar" else "Retomar"
        val toggleIcon = if (active) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_audio)
            .setContentTitle("Archiver • Reprodução")
            .setContentText(trackName)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(toggleIcon, toggleLabel, toggleIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar", stopIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { stopPlayback(); super.onDestroy() }

    companion object {
        const val ACTION_PLAY = "com.nexauren.archiver.action.PLAY_BACKGROUND"
        const val ACTION_TOGGLE = "com.nexauren.archiver.action.TOGGLE_BACKGROUND"
        const val ACTION_STOP = "com.nexauren.archiver.action.STOP_BACKGROUND"
        const val EXTRA_PATH = "audio_path"
        const val EXTRA_POSITION = "audio_position_ms"
        private const val CHANNEL_ID = "archiver_background_audio"
        private const val NOTIFICATION_ID = 7411
    }
}