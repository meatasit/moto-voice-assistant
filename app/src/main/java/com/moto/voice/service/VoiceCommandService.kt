package com.moto.voice.service

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Notification
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.moto.voice.MainActivity
import com.moto.voice.MotoVoiceApplication.Companion.CH_LISTENING
import com.moto.voice.audio.Earcon
import com.moto.voice.data.AppSettings
import com.moto.voice.debug.DebugLog
import com.moto.voice.debug.FinishReason
import com.moto.voice.pipeline.PreflightCheck
import com.moto.voice.pipeline.PreflightNotification
import com.moto.voice.pipeline.VoiceCommandPipeline
import com.moto.voice.tts.ThaiTTS
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Front door for the voice pipeline. Every trigger (helmet BVRA, ACTION_ASSIST,
 * Quick-Settings tile, Riding-Mode mic button, notification tap) routes through
 * onStartCommand here.
 *
 * Lifecycle (updated after field test log 1783433003820):
 *  - The service NO LONGER self-stops after each interaction. Previously we called
 *    stopSelf(startId) from the pipeline's onFinished callback, which produced a
 *    fresh "service_restarted" entry between every command in the exported log —
 *    misleading noise, wasted battery on re-init, and cache misses because
 *    TtsRouter/AndroidTtsEngine had to warm up from scratch every ~30s.
 *  - Instead we stay alive between interactions. A watchdog coroutine tears the
 *    service down only when (a) no interaction is running AND (b) the helmet
 *    (or ANY Bluetooth HFP device) has been disconnected for [IDLE_STOP_MS].
 *    If the rider never had a helmet, the idle timer never arms.
 *
 * Other responsibilities:
 *  - **Debounce** (spec §3.3): drops rapid double-taps within 500ms.
 *  - **Barge-in cancel** (spec §3.1): double-BVRA cancels the in-flight interaction.
 *  - **Preflight** (spec §5.1): fast health check before every interaction.
 *  - **Post-mortem** (spec §1.1): reads ApplicationExitInfo on onCreate to
 *    classify what killed us last time (CRASH / ANR / USER_STOPPED / SIGNAL / etc).
 */
class VoiceCommandService : LifecycleService() {

    companion object {
        private const val TAG = "VoiceCommandService"
        private const val NOTIF_ID = 42
        /** Any trigger within this window of the previous one is treated as noise. */
        private const val DEBOUNCE_MS = 500L
        /** Time the service waits after helmet disconnect + idle before stopping. */
        private const val IDLE_STOP_MS = 5 * 60 * 1000L  // 5 minutes
        private const val IDLE_POLL_MS = 60_000L
        private const val WATCHDOG_PREFS = "moto_voice_watchdog"
        private const val KEY_LAST_CREATE = "last_create_ms"
    }

    private var pipeline: VoiceCommandPipeline? = null

    @Volatile private var lastTriggerAt: Long = 0L

    /**
     * -1L means "never had an interaction yet". Set to elapsed-realtime on every
     * pipeline finish. Watched by [idleWatchdogJob].
     */
    @Volatile private var lastFinishedAt: Long = -1L

    private var idleWatchdogJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        classifyPreviousExit()
        getSharedPreferences(WATCHDOG_PREFS, MODE_PRIVATE)
            .edit().putLong(KEY_LAST_CREATE, System.currentTimeMillis()).apply()
        armIdleWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val now = SystemClock.elapsedRealtime()
        val sinceLast = now - lastTriggerAt

        // Debounce §3.3: drop a second trigger inside the window.
        if (lastTriggerAt > 0 && sinceLast < DEBOUNCE_MS) {
            Log.d(TAG, "trigger debounced (${sinceLast}ms since last)")
            // NB: we deliberately do NOT stopSelf here — that's what caused the
            // per-command restart pattern. Debounced triggers just no-op.
            return START_STICKY
        }
        lastTriggerAt = now

        startAsForeground()

        val active = pipeline?.isActive == true
        if (active) {
            // §3.1: barge-in cancel.
            Log.d(TAG, "barge-in: cancelling active pipeline")
            pipeline?.markBargeIn()
            pipeline?.stop()
            pipeline = null
            lifecycleScope.launch {
                Earcon.cancel()
                // Do NOT stopSelf — service stays alive, next trigger starts fresh.
            }
            lastFinishedAt = SystemClock.elapsedRealtime()
            return START_STICKY
        }

        // §5.1: fast health check.
        PreflightCheck(this).check()?.let { issue ->
            Log.w(TAG, "preflight failed: ${issue.kind}")
            handlePreflightMiss(issue)
            return START_STICKY
        }
        PreflightNotification.cancel(this)

        // Fresh interaction — service stays alive after it finishes.
        pipeline = VoiceCommandPipeline(this, AppSettings(this)) {
            lastFinishedAt = SystemClock.elapsedRealtime()
            pipeline = null
            Log.d(TAG, "pipeline finished — service staying alive")
        }
        pipeline?.start()
        return START_STICKY
    }

    private fun handlePreflightMiss(issue: PreflightCheck.Issue) {
        PreflightNotification.show(this, issue)
        DebugLog.new().apply {
            error = "preflight_missing:${issue.kind}"
            finishReason = FinishReason.PHONE_UNAVAILABLE
        }
        lifecycleScope.launch {
            val tts = ThaiTTS(this@VoiceCommandService)
            tts.speakAwait(issue.speak)
            delay(300)
            tts.stop()
            lastFinishedAt = SystemClock.elapsedRealtime()
            // Not stopSelf() — the watchdog can decide whether to shut down.
        }
    }

    override fun onDestroy() {
        pipeline?.stop()
        pipeline = null
        idleWatchdogJob?.cancel()
        idleWatchdogJob = null
        super.onDestroy()
    }

    // ─── Foreground + notification ──────────────────────────────────────────

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
            .setContentText("พร้อมรับคำสั่ง")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    // ─── Idle-shutdown watchdog (spec §1.2) ─────────────────────────────────

    private fun armIdleWatchdog() {
        idleWatchdogJob?.cancel()
        idleWatchdogJob = lifecycleScope.launch {
            while (true) {
                delay(IDLE_POLL_MS)
                val active = pipeline?.isActive == true
                if (active) continue

                val idleFor = lastFinishedAt.let {
                    if (it < 0) return@let 0L
                    SystemClock.elapsedRealtime() - it
                }
                if (idleFor < IDLE_STOP_MS) continue

                // Idle long enough. Only shut down if BT headset is also disconnected.
                if (isHeadsetConnected()) {
                    Log.d(TAG, "idle ${idleFor}ms but headset still connected — staying alive")
                    continue
                }

                Log.i(TAG, "idle ${idleFor / 1000}s AND no headset — self-stopping")
                stopSelf()
                return@launch
            }
        }
    }

    private fun isHeadsetConnected(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) return false
        val bm = getSystemService(BluetoothManager::class.java) ?: return false
        val adapter: BluetoothAdapter = bm.adapter ?: return false
        return runCatching {
            adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
        }.getOrDefault(false)
    }

    // ─── Post-mortem via ApplicationExitInfo (spec §1.1) ────────────────────

    private fun classifyPreviousExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = getSystemService(ActivityManager::class.java) ?: return
        val infos = runCatching {
            am.getHistoricalProcessExitReasons(packageName, /*pid=*/0, /*maxNum=*/1)
        }.getOrNull() ?: return
        val last = infos.firstOrNull() ?: return

        val reasonName = when (last.reason) {
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_EXIT_SELF -> "SELF"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED(${last.status})"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            else -> "OTHER(${last.reason})"
        }
        val ageMs = System.currentTimeMillis() - last.timestamp
        Log.i(TAG, "previous exit: $reasonName ${ageMs}ms ago — importance=${last.importance}")
        DebugLog.new().apply {
            error = "prev_exit:${reasonName} age=${ageMs}ms importance=${last.importance}"
            finishReason = "prev_exit_${reasonName.lowercase()}"
        }
    }
}
