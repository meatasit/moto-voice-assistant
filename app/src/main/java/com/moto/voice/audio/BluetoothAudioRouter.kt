package com.moto.voice.audio

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission

class BluetoothAudioRouter(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var scoReceiver: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(timeoutMs: Long = 3_000L, onReady: (scoConnected: Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val btSco = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (btSco != null) audioManager.setCommunicationDevice(btSco)
            onReady(btSco != null)
        } else {
            connectLegacy(timeoutMs, onReady)
        }
    }

    @Suppress("DEPRECATION")
    private fun connectLegacy(timeoutMs: Long, onReady: (Boolean) -> Unit) {
        if (!audioManager.isBluetoothScoAvailableOffCall) {
            onReady(false)
            return
        }

        val timeoutTask = Runnable {
            unregisterSafe()
            try { audioManager.stopBluetoothSco() } catch (_: Exception) {}
            onReady(false)
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        handler.removeCallbacks(timeoutTask)
                        unregisterSafe()
                        onReady(true)
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        handler.removeCallbacks(timeoutTask)
                        unregisterSafe()
                        onReady(false)
                    }
                }
            }
        }
        scoReceiver = receiver
        context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED))
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
        handler.postDelayed(timeoutTask, timeoutMs)
    }

    @Suppress("DEPRECATION")
    fun disconnect() {
        unregisterSafe()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
        }
    }

    private fun unregisterSafe() {
        scoReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
            scoReceiver = null
        }
    }
}
