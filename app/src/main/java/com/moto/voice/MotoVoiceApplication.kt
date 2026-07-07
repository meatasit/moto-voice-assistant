package com.moto.voice

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.moto.voice.bt.HelmetGreeter
import com.moto.voice.data.AppSettings
import com.moto.voice.nlu.Persona
import com.moto.voice.nlu.PersonaHolder

class MotoVoiceApplication : Application() {

    companion object {
        const val CH_LISTENING = "moto_voice_listening"
        const val CH_RADIO = "moto_voice_fm"
    }

    private var greeter: HelmetGreeter? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        applyPersonaFromSettings()
        greeter = HelmetGreeter(this).also { it.start() }
    }

    /** Bootstrap [PersonaHolder] from the persisted setting so every ErrorSpeech line reads correctly from the first frame. */
    private fun applyPersonaFromSettings() {
        val stored = runCatching { AppSettings(this).persona }.getOrDefault(AppSettings.PERSONA_FEMININE)
        PersonaHolder.set(
            if (stored == AppSettings.PERSONA_MASCULINE) Persona.Masculine else Persona.Feminine
        )
    }

    override fun onTerminate() {
        greeter?.stop()
        greeter = null
        super.onTerminate()
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
