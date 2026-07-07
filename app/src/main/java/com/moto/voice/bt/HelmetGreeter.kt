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
import com.moto.voice.nlu.ErrorSpeech
import com.moto.voice.tts.ThaiTTS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
        if (!settings.greetOnConnect) return

        // TTS in a short-lived scope. We give up after 4s if TTS can't init in time —
        // the notification is the primary signal; the greeting is nice-to-have.
        ttsJob?.cancel()
        ttsJob = scope.launch {
            withTimeoutOrNull(4_000L) {
                val tts = ThaiTTS(app)
                tts.speakAwait(ErrorSpeech.HELMET_READY)
                tts.stop()
            }
        }
    }

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

    private companion object {
        const val TAG = "HelmetGreeter"
        const val NOTIF_ID = 44
    }
}
