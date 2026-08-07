package com.moto.voice

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
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
 *  * The permission checklist, the Set-Default button and the how-to-use card are gone.
 *    Both remaining fixups are still reachable — as alert rows, when they actually apply.
 *
 * Everything is recomputed in [onResume] so returning from an OS settings screen
 * immediately reflects the new grant.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var history: AppHistory
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

        if (!AppSettings(this).onboardingComplete) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

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
        renderBanner()
        renderAlerts()
        renderHistory()
    }

    // ─── Banner ──────────────────────────────────────────────────────────────

    private fun renderBanner() {
        val allGranted = missingPermissions().isEmpty()
        val isDefault = isDefaultAssistant()
        val online = NetworkState.isOnline(this)
        val offlineSuffix = if (!online) "  •  ออฟไลน์" else ""
        when {
            isDefault && allGranted -> {
                binding.tvStatusIcon.text = if (online) "✅" else "📡"
                binding.tvStatus.text = getString(R.string.status_ready) + offlineSuffix
            }
            !isDefault -> {
                binding.tvStatusIcon.text = "⚠️"
                binding.tvStatus.text = getString(R.string.status_not_default) + offlineSuffix
            }
            else -> {
                binding.tvStatusIcon.text = "⚠️"
                binding.tvStatus.text = getString(R.string.status_missing_perms) + offlineSuffix
            }
        }
    }

    // ─── Alerts ──────────────────────────────────────────────────────────────

    /**
     * Sync checks only — home must paint instantly and must not fire the webhook probe
     * just because the app was opened. The async Webhook/TTS rows stay on
     * [SystemStatusActivity].
     */
    private fun renderAlerts() {
        val alerts = HomeAlerts.alerts(SystemStatusChecker(this).checkSync())
        binding.alertsContainer.removeAllViews()
        binding.alertsSection.visibility = if (alerts.isEmpty()) View.GONE else View.VISIBLE
        alerts.forEach { row ->
            val v = layoutInflater.inflate(R.layout.item_status_row, binding.alertsContainer, false)
            v.findViewById<TextView>(R.id.tvStatusDot).text =
                if (row.state == StatusRow.State.Red) "🔴" else "🟡"
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
                setTextColor(android.graphics.Color.parseColor("#AAFFFFFF"))
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
