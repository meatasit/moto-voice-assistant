package com.moto.voice.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.moto.voice.MainActivity
import com.moto.voice.MotoVoiceApplication.Companion.CH_LISTENING
import com.moto.voice.data.AppSettings
import com.moto.voice.pipeline.VoiceCommandPipeline

class VoiceCommandService : LifecycleService() {

    companion object {
        private const val NOTIF_ID = 42
    }

    private var pipeline: VoiceCommandPipeline? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startAsForeground()
        pipeline?.stop()
        pipeline = VoiceCommandPipeline(this, AppSettings(this)) { stopSelf(startId) }
        pipeline?.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        pipeline?.stop()
        pipeline = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_LISTENING)
            .setContentTitle("Moto Voice")
            .setContentText("กำลังฟัง...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
