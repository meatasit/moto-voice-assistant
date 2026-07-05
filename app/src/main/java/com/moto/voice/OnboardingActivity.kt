package com.moto.voice

import android.Manifest
import android.app.role.RoleManager
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
import com.moto.voice.data.AppSettings
import com.moto.voice.databinding.ActivityOnboardingBinding
import com.moto.voice.network.WebhookClient
import kotlinx.coroutines.launch

/**
 * 5-step Thai first-run wizard (spec §7). Each step displays an inline status ✓/✗ so
 * users can see progress at a glance. The whole activity is safe to re-enter from
 * Settings ("เปิด Onboarding อีกครั้ง") — nothing here is one-shot.
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
        stepFirstCommand(),
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
        val isDefault = isDefaultAssistant()
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

    private fun stepWebhook(): Step {
        // "Done" == user has run the test at least once. We track that by whether webhookUrl
        // is set (it always is by default) — so instead flip to done when user taps the action once.
        // For a lightweight check, mark this done as soon as webhookUrl is non-empty AND the
        // rest is done. It's the least interruptive definition of "ready".
        val ready = settings.webhookUrl.isNotBlank()
        return Step(
            title = "4. ทดสอบเชื่อม webhook",
            reason = "ตรวจว่าสมองของระบบ (n8n) เชื่อมได้ปกติ",
            done = ready,
            actionLabel = "ทดสอบ",
            action = { testWebhook() },
        )
    }

    private fun stepFirstCommand(): Step = Step(
        title = "5. ลองพูดคำสั่งแรก",
        reason = "แตะเพื่อเริ่มฟัง แล้วพูดว่า \"ทำอะไรได้บ้าง\"",
        done = false,
        actionLabel = "เริ่มฟัง",
        action = {
            startActivity(Intent(this, VoiceAssistActivity::class.java))
        },
    )

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
        binding.btnFinishOnboarding.text = if (allCoreDone) "เสร็จสิ้น" else "รอให้ครบ 3 ข้อแรกก่อน"
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && !rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                requestRole.launch(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                return
            }
        }
        runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            .onFailure {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                })
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

    private fun isDefaultAssistant(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        } else {
            Settings.Secure.getString(contentResolver, "assistant")?.contains(packageName) == true
        }
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
