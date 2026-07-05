package com.moto.voice

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class MotoVoiceApplication : Application() {

    companion object {
        const val CH_LISTENING = "moto_voice_listening"
        const val CH_RADIO = "moto_voice_fm"
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_LISTENING, "Moto Voice — กำลังฟัง", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableLights(false); enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RADIO, "Moto Voice Radio", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableLights(false); enableVibration(false)
            }
        )
    }
}
