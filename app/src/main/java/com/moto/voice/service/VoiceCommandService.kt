package com.moto.voice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.moto.voice.MainActivity
import com.moto.voice.data.AppSettings
import com.moto.voice.pipeline.VoiceCommandPipeline

class VoiceCommandService : Service() {

    companion object {
        private const val CHANNEL_ID = "moto_voice_listening"
        private const val NOTIF_ID = 42
    }

    private var pipeline: VoiceCommandPipeline? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        pipeline?.stop()
        pipeline = VoiceCommandPipeline(this, AppSettings(this)) { stopSelf(startId) }
        pipeline?.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        pipeline?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Moto Voice — กำลังฟัง", NotificationManager.IMPORTANCE_LOW)
            ch.setSound(null, null); ch.enableLights(false); ch.enableVibration(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Moto Voice")
            .setContentText("กำลังฟัง...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
