package com.moto.voice

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.os.Build
import android.util.Log
import com.moto.voice.bt.HelmetGreeter
import com.moto.voice.data.AppSettings
import com.moto.voice.nlu.Persona
import com.moto.voice.nlu.PersonaHolder
import com.moto.voice.tts.TtsRouter

class MotoVoiceApplication : Application() {

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

    /**
     * Spec v1.3.8 A4 — react to memory pressure so the OS is less likely to reach
     * the LOW_MEMORY exit path we observed in prior sessions
     * (ApplicationExitInfo REASON_LOW_MEMORY, importance 400).
     *
     * At MODERATE and above we:
     *   * clear the LRU tier of [TtsCache] (persistent pre-synth stays — that's what
     *     keeps the assistant responsive right when memory just tightened);
     *   * release the [HelmetGreeter] singleton — it's cheap to recreate on the next
     *     Bluetooth connect, but holds a coroutine scope that can be reclaimed now.
     *
     * Nothing here touches persistent state — favorites, settings, memory all live in
     * SharedPreferences and are untouched.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d(TAG, "onTrimMemory level=$level")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            runCatching {
                val deleted = TtsRouter.getOrCreate(this).clearTtsCacheLru()
                Log.d(TAG, "onTrimMemory MODERATE+ → cleared $deleted LRU TTS files")
            }
            greeter?.let {
                runCatching { it.stop() }
                greeter = null
                Log.d(TAG, "onTrimMemory MODERATE+ → released HelmetGreeter")
            }
        }
    }

    companion object {
        const val CH_LISTENING = "moto_voice_listening"
        const val CH_RADIO = "moto_voice_fm"
        /** v1.3.24 — high-importance channel for the over-lock-screen launch (full-screen intent). */
        const val CH_LAUNCH = "moto_voice_launch"
        private const val TAG = "MotoVoiceApplication"
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
        // Full-screen-intent needs a HIGH-importance channel or the OS demotes the launch.
        nm.createNotificationChannel(
            NotificationChannel(CH_LAUNCH, "Moto Voice — เปิดสื่อตอนจอล็อค", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null); enableVibration(false)
            }
        )
    }
}
