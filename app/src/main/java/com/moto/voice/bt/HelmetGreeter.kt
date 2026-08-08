package com.moto.voice.bt

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.moto.voice.MainActivity
import com.moto.voice.MotoVoiceApplication.Companion.CH_LISTENING
import com.moto.voice.VoiceAssistActivity
import com.moto.voice.data.AppSettings
import com.moto.voice.network.WebhookClient
import com.moto.voice.nlu.ErrorSpeech
import com.moto.voice.tts.ThaiTTS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

/**
 * Listens for Bluetooth headset (HFP) connection events and, on CONNECTED, both
 *
 *  - shows a low-priority notification "Moto Voice พร้อมใช้งาน" with a "เริ่มฟัง" action
 *  - (if settings.greetOnConnect) speaks a short Thai greeting through the newly-connected
 *    device so the rider hears confirmation without touching the phone
 *
 * Registered dynamically from [com.moto.voice.MotoVoiceApplication] because the
 * ACTION_ACL_* / HEADSET connection broadcasts are exempt from the API 26+ static
 * receiver restriction only when the manifest also declares the device connection
 * broadcast; the dynamic route is simpler and works while the process is alive.
 */
class HelmetGreeter(private val app: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var receiver: BroadcastReceiver? = null
    private var ttsJob: Job? = null

    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                        if (state == BluetoothProfile.STATE_CONNECTED) onConnected(intent)
                        else if (state == BluetoothProfile.STATE_DISCONNECTED) onDisconnected()
                    }
                }
            }
        }
        receiver = r
        val filter = IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(r, filter)
        }
    }

    fun stop() {
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
        ttsJob?.cancel()
        ttsJob = null
    }

    private fun onConnected(intent: Intent) {
        if (!hasBtPermission()) return
        val deviceName = readDeviceName(intent) ?: "หมวก"
        Log.d(TAG, "helmet connected: $deviceName")
        showReadyNotification(deviceName)

        val settings = AppSettings(app)

        // Spec v1.3.8 B4 — pick a time-appropriate greeting so the assistant feels
        // aware of context instead of repeating the same "พร้อมใช้งาน" every time.
        val greeting = if (settings.greetOnConnect) pickTimeBasedGreeting() else null

        // v1.3.37 — warm the LLM here, on the connect, so the first command of the ride
        // isn't the one that pays for a cold model load (26s in field log 1786178611552).
        // The ping is started BEFORE the greeting is spoken so the model loads while the
        // rider is hearing "อรุณสวัสดิ์ค่ะ" — the wait is absorbed by audio he wanted anyway.
        val warmNeeded = settings.webhookUrl.isNotBlank() &&
            LlmWarmup.shouldWarm(System.currentTimeMillis(), lastWarmOkAt)

        if (greeting == null && !warmNeeded) return

        ttsJob?.cancel()
        ttsJob = scope.launch {
            val ping = if (warmNeeded) async { pingLlm(settings) } else null

            // TTS in a short-lived scope. We give up after 4s if TTS can't init in time —
            // the notification is the primary signal; the greeting is nice-to-have.
            if (greeting != null) {
                withTimeoutOrNull(GREETING_TIMEOUT_MS) {
                    val tts = ThaiTTS(app)
                    tts.speakAwait(greeting)
                    tts.stop()
                }
            }

            val outcome = ping?.await() ?: return@launch
            if (outcome == LlmWarmup.Outcome.Warm) lastWarmOkAt = System.currentTimeMillis()
            // Rider's rule: silent when it works, speak only when something is wrong.
            val line = LlmWarmup.lineFor(outcome) ?: return@launch
            Log.w(TAG, "LLM warm-up: $outcome — telling the rider")
            withTimeoutOrNull(GREETING_TIMEOUT_MS) {
                val tts = ThaiTTS(app)
                tts.speakAwait(line)
                tts.stop()
            }
        }
    }

    /**
     * Fire the warm-up command and classify what came back. Never throws — a helmet
     * connect must not be able to crash the app because the LLM box is off.
     */
    private suspend fun pingLlm(settings: AppSettings): LlmWarmup.Outcome {
        val result = runCatching {
            WebhookClient(settings.webhookUrl, settings.authToken, LlmWarmup.PING_TIMEOUT_SEC)
                .call(LlmWarmup.PING_TEXT)
        }.getOrNull()
        return when (result) {
            is WebhookClient.Result.Success ->
                LlmWarmup.outcomeFor(configured = true, success = true, kind = null)
            is WebhookClient.Result.Failure ->
                LlmWarmup.outcomeFor(configured = true, success = false, kind = result.kind)
            // The call itself blew up (bad URL, etc.) — same rider-visible meaning as
            // "couldn't reach it".
            null -> LlmWarmup.outcomeFor(
                configured = true, success = false, kind = WebhookClient.Kind.Network,
            )
        }
    }

    private fun pickTimeBasedGreeting(hour: Int = currentHour()): String =
        pickTimeBasedGreetingFor(hour)

    private fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    private fun onDisconnected() {
        Log.d(TAG, "helmet disconnected")
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(NOTIF_ID)
        ttsJob?.cancel()
    }

    private fun showReadyNotification(deviceName: String) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        val listenPi = PendingIntent.getActivity(
            app, 0,
            Intent(app, VoiceAssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val mainPi = PendingIntent.getActivity(
            app, 1,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(app, CH_LISTENING)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Moto Voice พร้อมใช้งาน")
            .setContentText("เชื่อมกับ $deviceName แล้ว — แตะเพื่อเริ่มฟัง")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(listenPi)
            .addAction(android.R.drawable.ic_media_play, "เริ่มฟัง", listenPi)
            .addAction(android.R.drawable.ic_menu_manage, "ตั้งค่า", mainPi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun readDeviceName(intent: Intent): String? {
        if (!hasBtPermission()) return null
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        return runCatching { device?.name }.getOrNull()
    }

    companion object {
        private const val TAG = "HelmetGreeter"
        private const val NOTIF_ID = 44

        /** Give up on any single spoken line after this — the notification is the real signal. */
        private const val GREETING_TIMEOUT_MS = 4_000L

        /**
         * When the LLM last answered a warm-up. Process-global (the receiver is re-created
         * with the app) and only set on success, so a failed ping is retried on the next
         * connect — the rider may have just walked over and switched the box on.
         */
        @Volatile private var lastWarmOkAt: Long? = null

        /**
         * Spec v1.3.8 B4 — three greetings tuned to the rider's likely intent by hour:
         *   * 05:00–10:59  → wake-up energy ("อรุณสวัสดิ์ค่ะ")
         *   * 11:00–18:59  → midday-travel readiness ("พร้อมเดินทางแล้วค่ะ")
         *   * 19:00–04:59  → evening safety wish ("ขี่ปลอดภัยนะคะ")
         *
         * All three variants are persona-aware and pre-synthesized in
         * [ErrorSpeech.allSystemLines] so the greeting is a cache hit (no Azure round-trip
         * on the connection critical path).
         *
         * Kept as a top-level companion function so JVM tests can exercise it directly
         * without needing to construct a HelmetGreeter (which would need a Context).
         */
        fun pickTimeBasedGreetingFor(hour: Int): String = when (hour) {
            in 5..10 -> ErrorSpeech.GREET_MORNING
            in 11..18 -> ErrorSpeech.GREET_MIDDAY
            else -> ErrorSpeech.GREET_EVENING  // 19..23 and 0..4
        }
    }
}
