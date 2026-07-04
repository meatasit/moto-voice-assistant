package com.moto.voice

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.moto.voice.service.VoiceCommandService

/**
 * Transparent no-display activity that handles ACTION_ASSIST / ACTION_VOICE_COMMAND.
 * This is what makes the app appear in Settings > Default apps > Digital assistant.
 * It immediately starts VoiceCommandService and exits.
 */
class VoiceAssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val svc = Intent(this, VoiceCommandService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
        finish()
    }
}
