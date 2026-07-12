package com.moto.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.moto.voice.bt.AssistantRoleHelper
import com.moto.voice.data.AppSettings
import com.moto.voice.databinding.ActivityOnboardingBinding
import com.moto.voice.network.WebhookClient
import kotlinx.coroutines.launch

/**
 * 4-step Thai first-run wizard (spec §7, refined v1.3.6). Each step shows an inline
 * ✓/⬜ status. The whole activity is safe to re-enter from Settings — nothing is
 * one-shot.
 *
 * v1.3.6 changes:
 *   - "5. ลองพูดคำสั่งแรก" step removed. Field report: it never worked reliably
 *     from onboarding context, and the rider can always test from the System Status
 *     page (linked from the finish CTA copy) where verification is already wired up.
 *   - Default-assistant action goes through the shared [AssistantRoleHelper] so the
 *     onboarding button and the Settings button have identical behaviour. The old
 *     onboarding-only path went through RoleManager first and was no-op on some
 *     OEMs; Settings never had that problem because it just opened the OS picker.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var settings: AppSettings

    private val requestSinglePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { render() }

    private val requestRole = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { render() }

    private val requestBattery = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.apply { title = "เริ่มต้นใช้งาน"; setDisplayHomeAsUpEnabled(true) }

        settings = AppSettings(this)
        binding.btnFinishOnboarding.setOnClickListener {
            settings.onboardingComplete = true
            // Send the rider to System Status where the "ทดสอบเสียง" verification is
            // wired up — used to be step 5 of onboarding but that path never worked
            // reliably (v1.3.5 field report).
            startActivity(Intent(this, SystemStatusActivity::class.java))
            finish()
        }
        render()
    }

    override fun onResume() { super.onResume(); render() }

    // ─── Step model ──────────────────────────────────────────────────────────

    private data class Step(
        val title: String,
        val reason: String,
        val done: Boolean,
        /** null = don't show an action button (step is already done, or nothing to do). */
        val actionLabel: String?,
        val action: (() -> Unit)?,
    )

    private fun steps(): List<Step> = listOf(
        stepPermissions(),
        stepDefaultAssistant(),
        stepBattery(),
        stepWebhook(),
        stepMediaCtrl(),
    )

    private fun stepPermissions(): Step {
        val needed = listOfNotNull(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.BLUETOOTH_CONNECT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null,
        )
        val missing = needed.firstOrNull { !hasPerm(it) }
        return Step(
            title = "1. สิทธิ์ที่จำเป็น",
            reason = "ไมโครโฟน + รายชื่อ + โทรออก + Bluetooth (+ การแจ้งเตือน) — ใช้สำหรับฟังคำสั่งและโทร",
            done = missing == null,
            actionLabel = if (missing != null) "ขอสิทธิ์ถัดไป" else null,
            action = if (missing != null) ({ requestSinglePermission.launch(missing) }) else null,
        )
    }

    private fun stepDefaultAssistant(): Step {
        val isDefault = AssistantRoleHelper.isDefaultAssistant(this)
        return Step(
            title = "2. ตั้งเป็น Default Assistant",
            reason = "เพื่อให้ปุ่มบนหมวก BVRA / ปุ่ม Home ค้าง เรียกแอปได้",
            done = isDefault,
            actionLabel = if (!isDefault) "ตั้งเลย" else null,
            action = if (!isDefault) ({ openDefaultAssistantSettings() }) else null,
        )
    }

    private fun stepBattery(): Step {
        val exempt = isBatteryOptimizationDisabled()
        return Step(
            title = "3. ยกเว้น Battery Optimization",
            reason = "เพื่อให้แอปตื่นได้เมื่อกดปุ่มบนหมวก แม้จอปิด",
            done = exempt,
            actionLabel = if (!exempt) "ตั้งเลย" else null,
            action = if (!exempt) ({ requestBatteryExemption() }) else null,
        )
    }

    /**
     * v1.3.11 §1 — optional step. Explains the notification-listener rationale in
     * Thai. Tapping "ตั้งเลย" opens the OS "Notification access" list where the
     * rider grants Moto Voice access. Done state is checked via [MediaSessions],
     * but the step is OPTIONAL: the finish CTA doesn't require it, matching the
     * spec that all media commands still work via media-key fallback when denied.
     */
    private fun stepMediaCtrl(): Step {
        val granted = com.moto.voice.media.MediaSessions.hasPermission(this)
        return Step(
            title = "5. อนุญาต ควบคุมสื่อ (ทางเลือก)",
            reason = "อ่านสถานะเพลง/วิดีโอและควบคุมได้แม่นยำขึ้น — ยืนยันการเล่น + เลื่อนเป็นวินาที",
            done = granted,
            actionLabel = if (!granted) "ตั้งเลย" else null,
            action = if (!granted) ({ openNotificationListenerSettings() }) else null,
        )
    }

    private fun openNotificationListenerSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun stepWebhook(): Step {
        val ready = settings.webhookUrl.isNotBlank()
        return Step(
            title = "4. ทดสอบเชื่อม webhook",
            reason = "ตรวจว่าสมองของระบบ (n8n) เชื่อมได้ปกติ",
            done = ready,
            actionLabel = "ทดสอบ",
            action = { testWebhook() },
        )
    }

    // ─── Render ──────────────────────────────────────────────────────────────

    private fun render() {
        val steps = steps()
        binding.stepsContainer.removeAllViews()
        steps.forEach { step ->
            val row = layoutInflater.inflate(R.layout.item_onboarding_step, binding.stepsContainer, false)
            row.findViewById<TextView>(R.id.tvStepStatus).text = if (step.done) "✅" else "⬜"
            row.findViewById<TextView>(R.id.tvStepTitle).text = step.title
            row.findViewById<TextView>(R.id.tvStepReason).text = step.reason
            val btn = row.findViewById<MaterialButton>(R.id.btnStepAction)
            if (step.actionLabel != null && step.action != null) {
                btn.text = step.actionLabel
                btn.visibility = View.VISIBLE
                btn.setOnClickListener { step.action.invoke() }
            } else {
                btn.visibility = View.GONE
            }
            binding.stepsContainer.addView(row)
        }
        val allCoreDone = steps.take(3).all { it.done }
        binding.btnFinishOnboarding.isEnabled = allCoreDone
        binding.btnFinishOnboarding.text =
            if (allCoreDone) "เสร็จสิ้น — ทดสอบที่หน้า สถานะระบบ" else "รอให้ครบ 3 ข้อแรกก่อน"
    }

    // ─── Actions ─────────────────────────────────────────────────────────────

    private fun testWebhook() {
        binding.btnFinishOnboarding.isEnabled = false
        lifecycleScope.launch {
            val result = WebhookClient(settings.webhookUrl, settings.authToken, settings.timeoutSeconds)
                .call("ทดสอบระบบ")
            val msg = when (result) {
                is WebhookClient.Result.Success -> "✅ เชื่อมได้ (${result.elapsedMs}ms)"
                is WebhookClient.Result.Failure -> "❌ ${result.error}"
            }
            binding.btnFinishOnboarding.isEnabled = true
            android.widget.Toast.makeText(this@OnboardingActivity, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun openDefaultAssistantSettings() {
        val intent = AssistantRoleHelper.defaultAssistantPickerIntent(this)
        // requestRole launcher re-renders on return so the ✓ appears if the picker
        // succeeded; fall back to plain startActivity if the launcher throws.
        runCatching { requestRole.launch(intent) }
            .onFailure {
                runCatching { startActivity(intent) }
                    .onFailure {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        })
                    }
            }
    }

    private fun requestBatteryExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        runCatching { requestBattery.launch(intent) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
    }

    // ─── Checks ──────────────────────────────────────────────────────────────

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun isBatteryOptimizationDisabled(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
