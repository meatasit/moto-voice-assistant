package com.moto.voice

import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.moto.voice.actions.HistoryReplay
import com.moto.voice.data.AppHistory
import com.moto.voice.data.HistoryLabels
import com.moto.voice.databinding.ActivityHistoryBinding
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
                text = getString(R.string.home_history_empty)
                setTextColor(android.graphics.Color.parseColor("#AAFFFFFF"))
                textSize = 14f
                setPadding(16, 24, 16, 16)
            })
            return
        }
        entries.forEach { entry ->
            val row = layoutInflater.inflate(R.layout.item_history, binding.historyContainer, false)
            row.findViewById<TextView>(R.id.tvHistoryIcon).text = HistoryLabels.icon(entry.action)
            row.findViewById<TextView>(R.id.tvHistoryTitle).text = HistoryLabels.title(entry.action)
            row.findViewById<TextView>(R.id.tvHistoryTime).text = timeFmt.format(Date(entry.timestamp))
            row.findViewById<TextView>(R.id.tvHistoryHeard).text = HistoryLabels.subtitle(entry)
            row.setOnClickListener { HistoryReplay.repeat(this, entry.action) }
            binding.historyContainer.addView(row)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
