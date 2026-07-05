package com.moto.voice.debug

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.moto.voice.databinding.ActivityDebugLogBinding

class DebugLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebugLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.apply { title = "Debug Log"; setDisplayHomeAsUpEnabled(true) }

        binding.btnClear.setOnClickListener {
            DebugLog.clear()
            renderEntries()
        }
        binding.btnExport.setOnClickListener { exportLog() }
        renderEntries()
    }

    override fun onResume() {
        super.onResume()
        renderEntries()
    }

    private fun renderEntries() {
        binding.logContainer.removeAllViews()
        val entries = DebugLog.entries()
        if (entries.isEmpty()) {
            binding.logContainer.addView(makeTv("ยังไม่มี log", "#AAFFFFFF"))
            return
        }
        entries.forEach { e ->
            val card = layoutInflater.inflate(
                com.moto.voice.R.layout.item_debug_entry,
                binding.logContainer, false
            )
            card.findViewById<TextView>(com.moto.voice.R.id.tvEntryHeader).text = e.summary()
            card.findViewById<TextView>(com.moto.voice.R.id.tvEntryDetail).text = buildString {
                if (e.sttPartial.isNotBlank()) appendLine("Partial: ${e.sttPartial}")
                if (e.sttFinal.isNotBlank()) appendLine("Final: ${e.sttFinal}")
                if (e.webhookRequest != null) appendLine("→ WH: ${e.webhookRequest}")
                if (e.webhookResponse != null) appendLine("← WH: ${e.webhookResponse?.take(300)}")
                if (e.error != null) appendLine("⚠️ ${e.error}")
            }.trim()
            binding.logContainer.addView(card)
        }
    }

    private fun makeTv(text: String, color: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(android.graphics.Color.parseColor(color))
        textSize = 14f
        setPadding(16, 16, 16, 16)
    }

    private fun exportLog() {
        val file = DebugLog.exportToFile(this)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Export Debug Log"
        ))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
