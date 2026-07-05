package com.moto.voice.media

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.moto.voice.MainActivity
import com.moto.voice.MotoVoiceApplication.Companion.CH_RADIO

class FmPlayerService : Service() {

    companion object {
        const val ACTION_PLAY = "com.moto.voice.FM_PLAY"
        const val ACTION_STOP = "com.moto.voice.FM_STOP"
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_LABEL = "label"
        private const val NOTIF_ID = 43
    }

    private var player: ExoPlayer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopPlayback(); return START_NOT_STICKY }
            else -> {
                val url = intent?.getStringExtra(EXTRA_STREAM_URL)
                if (url.isNullOrBlank()) { stopPlayback(); return START_NOT_STICKY }
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "วิทยุ"
                startAsForeground(label)
                startPlayback(url)
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground(label: String) {
        val notif = buildNotification(label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun startPlayback(url: String) {
        player?.release()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true   // handleAudioFocus — pauses on incoming call automatically
            )
            .build().apply {
                setMediaItem(MediaItem.fromUri(url))
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_IDLE) stopPlayback()
                    }
                })
                prepare()
                play()
            }
    }

    private fun stopPlayback() {
        player?.release()
        player = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(label: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, FmPlayerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val mainPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_RADIO)
            .setContentTitle("Moto Voice — $label")
            .setContentText("กำลังเล่นอยู่  แตะเพื่อหยุด")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainPi)
            .addAction(android.R.drawable.ic_media_pause, "หยุด", stopPi)
            .setOngoing(true)
            .build()
    }
}
