package com.moto.voice.audio

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresPermission

class BluetoothAudioRouter(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var scoReceiver: BroadcastReceiver? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(onReady: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            connectApi31(onReady)
        } else {
            connectLegacy(onReady)
        }
    }

    private fun connectApi31(onReady: () -> Unit) {
        val btSco = audioManager.availableCommunicationDevices
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (btSco != null) {
            audioManager.setCommunicationDevice(btSco)
        }
        onReady()
    }

    @Suppress("DEPRECATION")
    private fun connectLegacy(onReady: () -> Unit) {
        if (!audioManager.isBluetoothScoAvailableOffCall) {
            onReady()
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    unregisterSafe()
                    onReady()
                } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                    // BT not available — fall through to phone mic/speaker
                    unregisterSafe()
                    onReady()
                }
            }
        }
        scoReceiver = receiver
        context.registerReceiver(
            receiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED)
        )
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
    }

    @Suppress("DEPRECATION")
    fun disconnect() {
        unregisterSafe()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            try {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            } catch (_: Exception) {}
        }
    }

    private fun unregisterSafe() {
        scoReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
            scoReceiver = null
        }
    }
}
