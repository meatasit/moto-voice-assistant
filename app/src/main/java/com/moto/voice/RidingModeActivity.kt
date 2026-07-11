package com.moto.voice

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.moto.voice.data.AppHistory
import com.moto.voice.data.HistoryAction
import com.moto.voice.databinding.ActivityRidingModeBinding
import com.moto.voice.pipeline.PipelineState
import com.moto.voice.service.VoiceCommandService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Big-button voice UI meant to be tapped with gloved fingers.
 *  - Central mic button starts the pipeline immediately (no ACTION_ASSIST hop).
 *  - Screen stays on while this activity is foreground so the rider can see status
 *    at a glance without unlocking the phone again.
 *  - Helmet-connection state is polled on resume via BluetoothProfile.
 *  - Last action / last-heard STT come from AppHistory (no separate broadcast needed).
 */
class RidingModeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRidingModeBinding
    private lateinit var history: AppHistory
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRidingModeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        history = AppHistory(this)

        binding.btnRideMic.setOnClickListener { startListening() }
        binding.btnRideExit.setOnClickListener { finish() }

        // Spec v1.3.9 §4 — mirror the pipeline state onto the big status circle.
        // repeatOnLifecycle(STARTED) pauses collection in onStop, which also stops
        // the ValueAnimator inside the view (via setState being idempotent), so
        // battery isn't burned while the activity is in the background.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PipelineState.state.collect { binding.statusIndicator.setState(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderHelmetStatus()
        renderLastAction()
    }

    private fun startListening() {
        val svc = Intent(this, VoiceCommandService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
    }

    private fun renderHelmetStatus() {
        val connected = isHeadsetConnected()
        binding.tvRideHelmetIcon.text = if (connected) "🪖" else "📱"
        binding.tvRideHelmetStatus.text = if (connected) "หมวกพร้อม" else "ไม่พบหมวก — ใช้ไมค์โทรศัพท์"
    }

    private fun renderLastAction() {
        val last = history.entries().firstOrNull()
        if (last == null) {
            binding.tvRideLastAction.text = "ยังไม่มีคำสั่ง"
            binding.tvRideLastHeard.text = ""
            return
        }
        binding.tvRideLastAction.text = titleFor(last.action)
        val time = timeFmt.format(Date(last.timestamp))
        val heard = last.heard.ifBlank { "(ไม่ได้จับความ)" }
        binding.tvRideLastHeard.text = "[$time] $heard"
    }

    private fun titleFor(a: HistoryAction) = when (a) {
        is HistoryAction.Call -> "📞 โทรหา ${a.name}"
        is HistoryAction.YoutubeOpen -> "▶ ${a.title.ifBlank { "YouTube" }}"
        is HistoryAction.FmPlay -> "📻 ${a.stationName}"
        HistoryAction.Stop -> "⏹ หยุดเสียง"
        is HistoryAction.Speak -> "💬 ${a.text}"
        is HistoryAction.Chat -> "🗨 ${a.text}"
    }

    private fun isHeadsetConnected(): Boolean {
        if (Build.SDK_S_OR_LATER &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) return false
        val bm = getSystemService(BluetoothManager::class.java) ?: return false
        val adapter: BluetoothAdapter = bm.adapter ?: return false
        return runCatching {
            adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
        }.getOrDefault(false)
    }

    private object Build {
        val SDK_S_OR_LATER =
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    }
}
