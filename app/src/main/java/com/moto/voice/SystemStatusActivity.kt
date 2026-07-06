package com.moto.voice

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.moto.voice.databinding.ActivitySystemStatusBinding
import com.moto.voice.pipeline.StatusRow
import com.moto.voice.pipeline.SystemStatusChecker
import com.moto.voice.tts.ThaiTTS
import kotlinx.coroutines.launch

/**
 * Spec §7: "จุดเดียวรู้ทุกชั้น". Every subsystem the pipeline depends on is shown
 * with a colored indicator + explanation. Tapping a row either re-runs its check or
 * launches the OS settings screen that fixes it. "ทดสอบทั้งระบบ" runs everything
 * again and speaks a summary so the rider can verify readiness before mounting up.
 */
class SystemStatusActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySystemStatusBinding
    private lateinit var checker: SystemStatusChecker
    private var previewTts: ThaiTTS? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySystemStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.apply { title = "สถานะระบบ"; setDisplayHomeAsUpEnabled(true) }

        checker = SystemStatusChecker(this)
        binding.btnTestAll.setOnClickListener { runAllAndSpeakSummary() }
        renderAll()
    }

    override fun onResume() { super.onResume(); renderAll() }

    override fun onDestroy() {
        previewTts?.stop()
        previewTts = null
        super.onDestroy()
    }

    /** Fill the list synchronously, then kick off the async rows in the background. */
    private fun renderAll() {
        val rows = checker.checkSync().toMutableList()
        drawRows(rows)
        runAsyncRows(rows)
    }

    private fun runAsyncRows(rows: MutableList<StatusRow>) {
        lifecycleScope.launch {
            val ttsRow = checker.checkTts()
            replaceRow(rows, ttsRow)
            drawRows(rows)
            val webhookRow = checker.checkWebhook()
            replaceRow(rows, webhookRow)
            drawRows(rows)
        }
    }

    private fun replaceRow(rows: MutableList<StatusRow>, updated: StatusRow) {
        val idx = rows.indexOfFirst { it.id == updated.id }
        if (idx >= 0) rows[idx] = updated else rows.add(updated)
    }

    private fun drawRows(rows: List<StatusRow>) {
        binding.statusRowsContainer.removeAllViews()
        rows.forEach { row ->
            val v = layoutInflater.inflate(R.layout.item_status_row, binding.statusRowsContainer, false)
            v.findViewById<TextView>(R.id.tvStatusDot).text = dotFor(row.state)
            v.findViewById<TextView>(R.id.tvStatusLabel).text = row.label
            v.findViewById<TextView>(R.id.tvStatusDetail).text = row.detail
            v.setOnClickListener { handleRowTap(row) }
            binding.statusRowsContainer.addView(v)
        }
    }

    private fun dotFor(state: StatusRow.State) = when (state) {
        StatusRow.State.Green -> "🟢"
        StatusRow.State.Yellow -> "🟡"
        StatusRow.State.Red -> "🔴"
        StatusRow.State.Pending -> "⏳"
    }

    private fun handleRowTap(row: StatusRow) {
        // Prefer fixing over re-testing when the row is red and has a fix intent.
        if (row.fixIntent != null && row.state != StatusRow.State.Green) {
            runCatching { startActivity(row.fixIntent) }
            return
        }
        // For rows without a fix (Green rows, or Yellow with no clear resolver): re-test.
        renderAll()
    }

    private fun runAllAndSpeakSummary() {
        binding.btnTestAll.isEnabled = false
        binding.btnTestAll.text = "กำลังทดสอบ..."
        lifecycleScope.launch {
            val rows = checker.checkSync().toMutableList()
            replaceRow(rows, checker.checkTts())
            replaceRow(rows, checker.checkWebhook())
            drawRows(rows)

            val bad = rows.filter { it.state == StatusRow.State.Red }
            val warn = rows.filter { it.state == StatusRow.State.Yellow }
            val summary = when {
                bad.isEmpty() && warn.isEmpty() -> "พร้อมใช้งานทุกระบบค่ะ"
                bad.isNotEmpty() -> "มีปัญหา: " + bad.joinToString(", ") { it.label }
                else -> "ยังไม่พร้อมทั้งหมด: " + warn.joinToString(", ") { it.label }
            }
            previewTts?.stop()
            previewTts = ThaiTTS(this@SystemStatusActivity).apply { speak(summary) }
            binding.btnTestAll.isEnabled = true
            binding.btnTestAll.text = "ทดสอบทั้งระบบ"
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
