package com.moto.voice.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.moto.voice.MainActivity
import com.moto.voice.MotoVoiceApplication.Companion.CH_LISTENING
import com.moto.voice.audio.Earcon
import com.moto.voice.data.AppSettings
import com.moto.voice.pipeline.VoiceCommandPipeline
import kotlinx.coroutines.launch

/**
 * Front door for the voice pipeline. Every trigger (helmet BVRA, ACTION_ASSIST,
 * Quick-Settings tile, Riding-Mode mic button, notification tap) routes through
 * onStartCommand here.
 *
 * Handles two concerns the pipeline itself can't:
 *  - **Debounce** (§3.3): drops rapid double-taps within 500ms so a stiff glove
 *    doesn't accidentally trigger + immediately cancel.
 *  - **Barge-in cancel** (§3.1): if the pipeline is already active when a new
 *    trigger arrives, cancel the current interaction, play the descending cancel
 *    earcon, and stop. The rider must press again to start fresh — we do NOT
 *    auto-start a new interaction after a barge-in, because the rider's intent
 *    was "stop what you're doing", not "let me redo it".
 */
class VoiceCommandService : LifecycleService() {

    companion object {
        private const val TAG = "VoiceCommandService"
        private const val NOTIF_ID = 42
        /** Any trigger within this window of the previous one is treated as noise. */
        private const val DEBOUNCE_MS = 500L
    }

    private var pipeline: VoiceCommandPipeline? = null

    @Volatile private var lastTriggerAt: Long = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val now = SystemClock.elapsedRealtime()
        val sinceLast = now - lastTriggerAt

        // Debounce §3.3: drop a second trigger inside the window.
        if (lastTriggerAt > 0 && sinceLast < DEBOUNCE_MS) {
            Log.d(TAG, "trigger debounced (${sinceLast}ms since last)")
            // Return sticky-ish behavior: we're not starting new work, but we also
            // don't want the OS to think this startId completed uncleanly.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        lastTriggerAt = now

        startAsForeground()

        val active = pipeline?.isActive == true
        if (active) {
            // §3.1: barge-in cancel. Annotate the current DebugEntry, stop the pipeline,
            // then play the descending cancel earcon so the rider hears the abort.
            Log.d(TAG, "barge-in: cancelling active pipeline")
            pipeline?.markBargeIn()
            pipeline?.stop()
            pipeline = null
            lifecycleScope.launch {
                Earcon.cancel()
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        // Fresh interaction.
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
