package com.moto.voice

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.method.PasswordTransformationMethod
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.moto.voice.data.AppSettings
import com.moto.voice.databinding.ActivitySettingsBinding
import com.moto.voice.network.WebhookClient

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: AppSettings
    private val handler = Handler(Looper.getMainLooper())
    private var tokenVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.apply { title = "ตั้งค่า"; setDisplayHomeAsUpEnabled(true) }

        settings = AppSettings(this)
        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        binding.etWebhookUrl.setText(settings.webhookUrl)
        binding.etToken.setText(settings.authToken)
        binding.etToken.transformationMethod = PasswordTransformationMethod.getInstance()
        binding.etTimeout.setText(settings.timeoutSeconds.toString())
        binding.switchLlm.isChecked = settings.llmMode
        binding.switchConfirmCall.isChecked = settings.confirmBeforeCall
    }

    private fun setupListeners() {
        binding.btnShowToken.setOnClickListener {
            tokenVisible = !tokenVisible
            binding.etToken.transformationMethod =
                if (tokenVisible) null else PasswordTransformationMethod.getInstance()
            binding.etToken.setSelection(binding.etToken.text?.length ?: 0)
            binding.btnShowToken.text = if (tokenVisible) "ซ่อน" else "แสดง"
        }

        binding.switchLlm.setOnCheckedChangeListener { _, v -> settings.llmMode = v }
        binding.switchConfirmCall.setOnCheckedChangeListener { _, v -> settings.confirmBeforeCall = v }

        binding.btnTestConnection.setOnClickListener { testConnection() }

        binding.btnDefaultAssistant.setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            catch (_: Exception) { openAppSettings() }
        }
        binding.btnBatteryOpt.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun testConnection() {
        saveSettings()
        binding.btnTestConnection.isEnabled = false
        binding.tvTestResult.text = "กำลังทดสอบ..."
        val url = settings.webhookUrl
        val token = settings.authToken
        val timeout = settings.timeoutSeconds
        Thread {
            val result = WebhookClient(url, token, timeout).send("ทดสอบระบบ")
            handler.post {
                binding.btnTestConnection.isEnabled = true
                binding.tvTestResult.text = if (result.error == null) {
                    "✅ ${result.elapsedMs}ms\n${result.rawJson.take(400)}"
                } else {
                    "❌ ${result.error}\n(${result.elapsedMs}ms)"
                }
            }
        }.start()
    }

    private fun saveSettings() {
        settings.webhookUrl = binding.etWebhookUrl.text.toString().trim()
            .ifBlank { AppSettings.DEFAULT_WEBHOOK_URL }
        settings.authToken = binding.etToken.text.toString()
        settings.timeoutSeconds = binding.etTimeout.text.toString().toIntOrNull()
            ?: AppSettings.DEFAULT_TIMEOUT
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        })
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
