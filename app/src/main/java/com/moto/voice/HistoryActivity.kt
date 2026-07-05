package com.moto.voice

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.moto.voice.actions.MediaStopper
import com.moto.voice.data.AppHistory
import com.moto.voice.data.HistoryAction
import com.moto.voice.data.HistoryEntry
import com.moto.voice.databinding.ActivityHistoryBinding
import com.moto.voice.media.FmPlayerService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var history: AppHistory
    private val timeFmt = SimpleDateFormat("HH:mm  d MMM", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.apply { title = "ประวัติการใช้งาน"; setDisplayHomeAsUpEnabled(true) }

        history = AppHistory(this)
        binding.btnClearHistory.setOnClickListener {
            history.clear(); render()
        }
        render()
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        binding.historyContainer.removeAllViews()
        val entries = history.entries()
        if (entries.isEmpty()) {
            binding.historyContainer.addView(TextView(this).apply {
                text = "ยังไม่มีประวัติ — พูดคำสั่งแรกได้เลย"
                setTextColor(android.graphics.Color.parseColor("#AAFFFFFF"))
                textSize = 14f
                setPadding(16, 24, 16, 16)
            })
            return
        }
        entries.forEach { entry ->
            val row = layoutInflater.inflate(R.layout.item_history, binding.historyContainer, false)
            row.findViewById<TextView>(R.id.tvHistoryIcon).text = iconFor(entry.action)
            row.findViewById<TextView>(R.id.tvHistoryTitle).text = titleFor(entry.action)
            row.findViewById<TextView>(R.id.tvHistoryTime).text = timeFmt.format(Date(entry.timestamp))
            row.findViewById<TextView>(R.id.tvHistoryHeard).text = subtitleFor(entry)
            row.setOnClickListener { repeat(entry.action) }
            binding.historyContainer.addView(row)
        }
    }

    private fun iconFor(action: HistoryAction) = when (action) {
        is HistoryAction.Call -> "📞"
        is HistoryAction.YoutubeOpen -> "▶"
        is HistoryAction.FmPlay -> "📻"
        HistoryAction.Stop -> "⏹"
        is HistoryAction.Speak -> "💬"
    }

    private fun titleFor(action: HistoryAction) = when (action) {
        is HistoryAction.Call -> "โทรหา ${action.name}"
        is HistoryAction.YoutubeOpen -> if (action.title.isNotBlank()) "เปิด YouTube: ${action.title}" else "เปิด YouTube"
        is HistoryAction.FmPlay -> "เปิดวิทยุ ${action.stationName}"
        HistoryAction.Stop -> "หยุดเสียง"
        is HistoryAction.Speak -> "ผู้ช่วยพูด"
    }

    private fun subtitleFor(entry: HistoryEntry): String {
        val heard = entry.heard.ifBlank { "(ไม่ได้จับความ)" }
        return "คุณพูด: $heard"
    }

    private fun repeat(action: HistoryAction) {
        when (action) {
            is HistoryAction.Call -> runCatching {
                startActivity(
                    Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(action.number)}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            is HistoryAction.YoutubeOpen -> runCatching {
                val app = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:${action.videoId}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${action.videoId}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val target = if (app.resolveActivity(packageManager) != null) app else web
                startActivity(target)
            }
            is HistoryAction.FmPlay -> {
                val intent = Intent(this, FmPlayerService::class.java)
                    .setAction(FmPlayerService.ACTION_PLAY)
                    .putExtra(FmPlayerService.EXTRA_STREAM_URL, action.streamUrl)
                    .putExtra(FmPlayerService.EXTRA_LABEL, action.stationName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
            }
            HistoryAction.Stop -> MediaStopper.stopAny(this)
            is HistoryAction.Speak -> Unit  // no-op: nothing to repeat for a speak-only entry
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
