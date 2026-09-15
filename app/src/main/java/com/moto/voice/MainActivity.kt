package com.moto.voice

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.moto.voice.actions.HistoryReplay
import com.moto.voice.data.AppHistory
import com.moto.voice.data.AppSettings
import com.moto.voice.data.HistoryLabels
import com.moto.voice.data.NetworkState
import com.moto.voice.databinding.ActivityMainBinding
import com.moto.voice.debug.DebugLogActivity
import com.moto.voice.pipeline.HomeAlerts
import com.moto.voice.pipeline.StatusRow
import com.moto.voice.pipeline.SystemStatusChecker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home screen. v1.3.35 turned it from a setup checklist into a dashboard:
 *
 *  * **Only unhealthy status rows are shown** ([HomeAlerts]), and the whole section
 *    disappears once everything is granted. Rider report 7 Aug 2026: an APK update can
 *    silently drop the "เปิดสื่อตอนจอล็อค" (USE_FULL_SCREEN_INTENT) grant, and the only
 *    surface that said so was [SystemStatusActivity] behind two taps — so the failure was
 *    discovered mid-ride, as a locked media open that quietly did nothing.
 *  * **Recent history renders inline** instead of hiding behind a button; tapping a row
 *    replays it via [HistoryReplay], same as [HistoryActivity].
 *
 * v1.4.0 — product pass. The banner became a hero card with a coloured dot and three chips
 * (brain: Cloud/Local, helmet, network) so the day-to-day variables are visible without
 * opening anything. Everything is still recomputed in [onResume] so returning from an OS
 * settings screen immediately reflects the new grant.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var history: AppHistory
    private lateinit var settings: AppSettings
    private val timeFmt = SimpleDateFormat("HH:mm  d MMM", Locale.getDefault())

    private val allPermissions: Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    /**
     * Ask for every missing permission in one system dialog. If anything is still
     * missing afterwards the rider has hit "don't ask again", so the only way left is
     * the app-details page — open it rather than leaving a row that does nothing.
     */
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (missingPermissions().isNotEmpty()) openAppDetails()
        render()
    }

    private val requestRoleResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        history = AppHistory(this)
        settings = AppSettings(this)

        if (!settings.onboardingComplete) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        binding.tvVersion.text = versionLabel()
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.btnDebugLog.setOnClickListener { startActivity(Intent(this, DebugLogActivity::class.java)) }
        binding.btnFavorites.setOnClickListener { startActivity(Intent(this, FavoritesActivity::class.java)) }
        binding.btnHistoryAll.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        binding.btnSystemStatus.setOnClickListener { openSystemStatus() }
        binding.cardStatus.setOnClickListener { openSystemStatus() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        // Sync checks only — home must paint instantly and must not fire the webhook probe
        // just because the app was opened. The async Webhook/TTS rows stay on
        // [SystemStatusActivity].
        val rows = SystemStatusChecker(this).checkSync()
        renderHero(rows)
        renderAlerts(rows)
        renderHistory()
    }

    // ─── Hero ────────────────────────────────────────────────────────────────

    private fun renderHero(rows: List<StatusRow>) {
        val allGranted = missingPermissions().isEmpty()
        val isDefault = isDefaultAssistant()
        val online = NetworkState.isOnline(this)
        val helmetOn = rows.firstOrNull { it.id == StatusRow.Kind.Helmet }?.state == StatusRow.State.Green

        val (line, color) = when {
            !isDefault -> getString(R.string.status_not_default) to R.color.bad
            !allGranted -> getString(R.string.status_missing_perms) to R.color.bad
            !online -> getString(R.string.status_ready_offline) to R.color.warn
            else -> getString(R.string.status_ready) to R.color.ok
        }
        binding.tvStatus.text = line
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(getColor(color))

        binding.chipBrain.text = when {
            !settings.llmMode -> "🧩 กฎในเครื่อง"
            settings.llmProvider == AppSettings.LLM_LOCAL -> "🖥️ Local LLM"
            else -> "☁️ Cloud AI"
        }
        binding.chipHelmet.text = if (helmetOn) "🪖 หมวกเชื่อมแล้ว" else "🪖 ยังไม่ต่อหมวก"
        binding.chipNet.text = if (online) "📶 ออนไลน์" else "📵 ออฟไลน์"
        binding.tvHeroHint.text = when {
            !isDefault -> "แตะเพื่อตั้งเป็นผู้ช่วยหลักก่อน ปุ่มบนหมวกถึงจะเรียกได้"
            helmetOn -> "กดปุ่มบนหมวก แล้วพูดหลังเสียงบี๊บ"
            else -> "ต่อหมวกแล้วกดปุ่ม BVRA หรือกดปุ่ม Home ค้างบนเครื่อง"
        }
    }

    // ─── Alerts ──────────────────────────────────────────────────────────────

    private fun renderAlerts(rows: List<StatusRow>) {
        val alerts = HomeAlerts.alerts(rows)
        binding.alertsContainer.removeAllViews()
        binding.alertsSection.visibility = if (alerts.isEmpty()) View.GONE else View.VISIBLE
        alerts.forEach { row ->
            val v = layoutInflater.inflate(R.layout.item_status_row, binding.alertsContainer, false)
            v.findViewById<TextView>(R.id.tvStatusDot).apply {
                text = "●"
                setTextColor(getColor(if (row.state == StatusRow.State.Red) R.color.bad else R.color.warn))
            }
            v.findViewById<TextView>(R.id.tvStatusLabel).text = row.label
            v.findViewById<TextView>(R.id.tvStatusDetail).text = row.detail
            v.setOnClickListener { handleAlertTap(row) }
            binding.alertsContainer.addView(v)
        }
    }

    /**
     * Permissions and the assistant role keep their in-app flows (one dialog instead of
     * a trip through Settings); everything else uses the row's own fix intent, with the
     * status page as the last resort so a tap is never a no-op.
     */
    private fun handleAlertTap(row: StatusRow) {
        when (row.id) {
            StatusRow.Kind.Permissions -> requestPermissions.launch(missingPermissions().toTypedArray())
            StatusRow.Kind.DefaultAssistant -> openDefaultAssistantSettings()
            else -> {
                val fix = row.fixIntent
                if (fix != null) runCatching { startActivity(fix) }.onFailure { openSystemStatus() }
                else openSystemStatus()
            }
        }
    }

    // ─── History ─────────────────────────────────────────────────────────────

    private fun renderHistory() {
        binding.historyContainer.removeAllViews()
        val entries = history.entries().take(HOME_HISTORY_LIMIT)
        if (entries.isEmpty()) {
            binding.historyContainer.addView(TextView(this).apply {
                text = getString(R.string.home_history_empty)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 14f
                setPadding(16, 16, 16, 16)
            })
            return
        }
        entries.forEach { entry ->
            val row = layoutInflater.inflate(R.layout.item_history, binding.historyContainer, false)
            row.findViewById<TextView>(R.id.tvHistoryIcon).text = HistoryLabels.icon(entry.action)
            row.findViewById<TextView>(R.id.tvHistoryTitle).text = HistoryLabels.title(entry.action)
            row.findViewById<TextView>(R.id.tvHistoryTime).text = timeFmt.format(Date(entry.timestamp))
            row.findViewById<TextView>(R.id.tvHistoryHeard).text = HistoryLabels.subtitle(entry)
            if (HistoryLabels.isReplayable(entry.action)) {
                row.setOnClickListener { HistoryReplay.repeat(this, entry.action) }
            } else {
                row.isClickable = false
            }
            binding.historyContainer.addView(row)
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun versionLabel(): String {
        val name = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        return if (name.isNullOrBlank()) "จาวิส · ผู้ช่วยเสียงตอนขี่" else "จาวิส · ผู้ช่วยเสียงตอนขี่ · v$name"
    }

    private fun missingPermissions(): List<String> = allPermissions.filterNot {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun openSystemStatus() = startActivity(Intent(this, SystemStatusActivity::class.java))

    private fun isDefaultAssistant(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
        } else {
            Settings.Secure.getString(contentResolver, "assistant")?.contains(packageName) == true
        }
    }

    private fun openDefaultAssistantSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (!rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                requestRoleResult.launch(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                return
            }
        }
        try { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
        catch (_: Exception) { openAppDetails() }
    }

    private fun openAppDetails() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        })
    }

    private companion object {
        /**
         * Home shows a glanceable slice; [HistoryActivity] still lists all
         * [AppHistory.MAX] entries behind "ดูทั้งหมด".
         */
        const val HOME_HISTORY_LIMIT = 8
    }
}
