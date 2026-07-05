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
import android.util.Log
import androidx.annotation.RequiresPermission

private const val TAG = "BluetoothAudioRouter"

class BluetoothAudioRouter(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var scoReceiver: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutTask: Runnable? = null

    @Volatile private var callbackFired = false
    @Volatile private var readyCallback: ((Boolean) -> Unit)? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(timeoutMs: Long = 3_000L, onReady: (scoConnected: Boolean) -> Unit) {
        callbackFired = false
        readyCallback = onReady

        val am = audioManager
        if (am == null) {
            Log.w(TAG, "AudioManager unavailable")
            fireOnce(false)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val btSco = am.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            val ok = btSco != null && am.setCommunicationDevice(btSco)
            fireOnce(ok)
        } else {
            connectLegacy(am, timeoutMs)
        }
    }

    @Suppress("DEPRECATION")
    private fun connectLegacy(am: AudioManager, timeoutMs: Long) {
        if (!am.isBluetoothScoAvailableOffCall) {
            fireOnce(false)
            return
        }

        val task = Runnable {
            unregisterSafe()
            runCatching { am.stopBluetoothSco() }
            fireOnce(false)
        }
        timeoutTask = task

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (callbackFired) return
                when (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        handler.removeCallbacks(task)
                        unregisterSafe()
                        fireOnce(true)
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        handler.removeCallbacks(task)
                        unregisterSafe()
                        fireOnce(false)
                    }
                }
            }
        }
        scoReceiver = receiver

        // Register BEFORE starting SCO so we don't race the CONNECTED broadcast.
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        handler.postDelayed(task, timeoutMs)

        runCatching {
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
        }.onFailure {
            Log.w(TAG, "startBluetoothSco failed", it)
            handler.removeCallbacks(task)
            unregisterSafe()
            fireOnce(false)
        }
    }

    @Suppress("DEPRECATION")
    fun disconnect() {
        timeoutTask?.let { handler.removeCallbacks(it) }
        timeoutTask = null
        unregisterSafe()
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
            }
        }
    }

    private fun fireOnce(connected: Boolean) {
        if (callbackFired) return
        callbackFired = true
        val cb = readyCallback
        readyCallback = null
        cb?.invoke(connected)
    }

    private fun unregisterSafe() {
        scoReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
            scoReceiver = null
        }
    }
}
