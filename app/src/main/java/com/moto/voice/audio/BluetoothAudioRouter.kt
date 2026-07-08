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
            if (!ok) {
                fireOnce(false)
                return
            }
            // setCommunicationDevice() returns true immediately even though the SCO link
            // may take a moment to actually carry audio. Give it a beat before we start
            // recording — otherwise the first ~200ms of speech gets recorded from the
            // phone mic during the switchover. Empirically ~300ms is enough on the S24.
            handler.postDelayed({ fireOnce(true) }, SCO_SETTLE_MS)
        } else {
            connectLegacy(am, timeoutMs)
        }
    }

    private companion object {
        const val SCO_SETTLE_MS = 300L
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

    /**
     * Release SCO and reset audio routing so A2DP can take over as the primary output.
     *
     * Field-test log 1783477052378 showed every interaction leaving `scoState=connected`
     * behind — the rider heard the TTS confirmation clearly but every subsequent media
     * (YouTube / FM) was silent. Root cause was two-fold:
     *
     *   1. On API 31+, [AudioManager.clearCommunicationDevice] cancels the request but
     *      does NOT reset the audio mode. If any layer (TTS, SpeechRecognizer) had
     *      nudged the platform into MODE_IN_COMMUNICATION, STREAM_MUSIC kept routing
     *      through the mono SCO channel and A2DP stayed suppressed. Explicitly setting
     *      MODE_NORMAL after the clear guarantees music routing.
     *   2. On <31, `stopBluetoothSco()` needs the mode flipped too; some vendor stacks
     *      leave STREAM_MUSIC pinned to SCO until mode changes.
     *
     * Idempotent — safe to call from cleanup even if we never connected.
     */
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
        // Belt-and-braces: force MODE_NORMAL so A2DP can carry STREAM_MUSIC (see kdoc).
        runCatching { am.mode = AudioManager.MODE_NORMAL }
    }

    /**
     * Current audio mode as read from [AudioManager]. Used by the pipeline to log
     * `audioMode` on media actions so we can prove in the field log that MODE_NORMAL
     * was reached before ExoPlayer / the YouTube intent kicked off.
     */
    fun currentAudioMode(): Int = audioManager?.mode ?: AudioManager.MODE_INVALID

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
