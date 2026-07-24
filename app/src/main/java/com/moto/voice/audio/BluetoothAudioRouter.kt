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
            connectModern(am)
        } else {
            connectLegacy(am, timeoutMs)
        }
    }

    /**
     * API 31+ SCO bring-up via [AudioManager.setCommunicationDevice].
     *
     * Two cold-start hardenings, both from field log 1784863894811 (rider: "กดปุ่ม
     * ครั้งแรกแล้วไม่มีเสียงสัญญาณ" — the [Earcon.ready] cue was inaudible on the very
     * first press, audible on every press after):
     *
     *   1. **Cold retry** — on the first connect since process start the SCO device may
     *      not yet be enumerated in [AudioManager.availableCommunicationDevices]. If the
     *      first `setCommunicationDevice` fails on a cold stack, retry once after a short
     *      beat instead of falling straight back to the phone mic (which sends the earcon
     *      to the phone speaker the helmeted rider can't hear).
     *   2. **Cold settle** — `setCommunicationDevice` returns true immediately, but the
     *      SCO link needs longer than the warm ~300ms to actually carry audio on a cold
     *      BT stack. The first connect gets [SCO_COLD_SETTLE_MS] so the ready cue (played
     *      right after we fire ready) lands on the live helmet link, not into the
     *      switchover gap. Subsequent connects keep the warm [SCO_SETTLE_MS].
     */
    private fun connectModern(am: AudioManager) {
        val cold = !everConnectedThisProcess
        lastConnectWasCold = cold

        fun trySetDevice(): Boolean {
            val btSco = am.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            return btSco != null && am.setCommunicationDevice(btSco)
        }

        fun settleThenFire() {
            val settle = if (cold) SCO_COLD_SETTLE_MS else SCO_SETTLE_MS
            handler.postDelayed({
                everConnectedThisProcess = true
                fireOnce(true)
            }, settle)
        }

        if (trySetDevice()) {
            settleThenFire()
            return
        }
        if (!cold) {
            fireOnce(false)
            return
        }
        // Cold stack: SCO device may not be enumerated yet on the very first press.
        handler.postDelayed({
            if (callbackFired) return@postDelayed
            if (trySetDevice()) settleThenFire() else fireOnce(false)
        }, COLD_RETRY_MS)
    }

    private companion object {
        const val SCO_SETTLE_MS = 300L
        /** First connect after cold start needs longer for the link to carry audio. */
        const val SCO_COLD_SETTLE_MS = 800L
        /** Delay before the one retry of setCommunicationDevice on a cold stack. */
        const val COLD_RETRY_MS = 250L

        /** Process-global so it survives per-interaction router instances. */
        @Volatile var everConnectedThisProcess = false
    }

    /** Set true while the most recent [connect] used the cold (first-since-boot) path. */
    @Volatile private var lastConnectWasCold = false

    /**
     * Whether the most recent [connect] took the cold-start path (first SCO bring-up
     * since process start). Logged as `scoColdConnect` so a field log can correlate the
     * "first press" symptom with the connect that used the longer cold settle.
     */
    fun lastConnectWasCold(): Boolean = lastConnectWasCold

    /**
     * True if the platform's active communication device is the BT SCO link right now.
     * Read the instant before [Earcon.ready] fires so the field log can PROVE whether the
     * ready cue routed to the helmet (`sco`) or leaked to the phone speaker (`phone`) —
     * the earcon path is otherwise uninstrumented (field log 1784863894811 could only
     * show the rider reporting the symptom, never where the tone went).
     */
    fun communicationRouteIsSco(): Boolean {
        val am = audioManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        } else {
            @Suppress("DEPRECATION") am.isBluetoothScoOn
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
