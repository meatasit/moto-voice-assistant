package com.moto.voice

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.method.PasswordTransformationMethod
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.moto.voice.data.AppSettings
import com.moto.voice.databinding.ActivitySettingsBinding
import com.moto.voice.network.WebhookClient
import com.moto.voice.tts.ThaiTTS
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: AppSettings
    private var tokenVisible = false
    private var testJob: Job? = null
    private var previewTts: ThaiTTS? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.apply { title = getString(R.string.title_settings); setDisplayHomeAsUpEnabled(true) }

        settings = AppSettings(this)
        loadSettings()
        setupListeners()
        if (!settings.isTokenStoreSecure) {
            binding.tvTestResult.text = getString(R.string.token_store_insecure_warning)
        }
    }

    private fun loadSettings() {
        binding.etWebhookUrl.setText(settings.webhookUrl)
        binding.etToken.setText(settings.authToken)
        binding.etToken.transformationMethod = PasswordTransformationMethod.getInstance()
        binding.etTimeout.setText(settings.timeoutSeconds.toString())
        binding.switchLlm.isChecked = settings.llmMode
        binding.switchConfirmCall.isChecked = settings.confirmBeforeCall
        binding.switchAskYoutube.isChecked = settings.askBeforeYoutube
        binding.switchGreetOnConnect.isChecked = settings.greetOnConnect
        binding.sliderTtsRate.value = settings.ttsSpeechRate
        binding.tvTtsRateValue.text = formatRate(settings.ttsSpeechRate)
    }

    private fun formatRate(rate: Float): String = "%.1fx".format(rate)

    private fun setupListeners() {
        binding.btnShowToken.setOnClickListener {
            tokenVisible = !tokenVisible
            binding.etToken.transformationMethod =
                if (tokenVisible) null else PasswordTransformationMethod.getInstance()
            binding.etToken.setSelection(binding.etToken.text?.length ?: 0)
            binding.btnShowToken.text = if (tokenVisible) getString(R.string.token_hide) else getString(R.string.token_show)
        }

        binding.switchLlm.setOnCheckedChangeListener { _, v -> settings.llmMode = v }
        binding.switchConfirmCall.setOnCheckedChangeListener { _, v -> settings.confirmBeforeCall = v }
        binding.switchAskYoutube.setOnCheckedChangeListener { _, v -> settings.askBeforeYoutube = v }
        binding.switchGreetOnConnect.setOnCheckedChangeListener { _, v -> settings.greetOnConnect = v }

        binding.sliderTtsRate.addOnChangeListener { _, value, _ ->
            binding.tvTtsRateValue.text = formatRate(value)
            settings.ttsSpeechRate = value
        }
        binding.btnPreviewTts.setOnClickListener { previewSpeech() }

        binding.btnTestConnection.setOnClickListener { testConnection() }

        binding.btnDefaultAssistant.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                .onFailure { openAppSettings() }
        }
        binding.btnBatteryOpt.setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            runCatching { startActivity(intent) }
                .onFailure { runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
        }
        binding.btnAppPermissions.setOnClickListener { openAppSettings() }
        binding.btnReopenOnboarding.setOnClickListener {
            // Force re-entry even if the user has already completed onboarding.
            settings.onboardingComplete = false
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    private fun testConnection() {
        saveSettings()
        binding.btnTestConnection.isEnabled = false
        binding.tvTestResult.text = getString(R.string.testing)
        testJob?.cancel()
        val url = settings.webhookUrl
        val token = settings.authToken
        val timeout = settings.timeoutSeconds
        testJob = lifecycleScope.launch {
            val result = WebhookClient(url, token, timeout).call("ทดสอบระบบ")
            binding.btnTestConnection.isEnabled = true
            binding.tvTestResult.text = when (result) {
                is WebhookClient.Result.Success -> "✅ ${result.elapsedMs}ms\n${result.rawJson.take(400)}"
                is WebhookClient.Result.Failure -> "❌ ${result.error}\n(${result.elapsedMs}ms)"
            }
        }
    }

    private fun saveSettings() {
        settings.webhookUrl = binding.etWebhookUrl.text.toString().trim()
            .ifBlank { AppSettings.DEFAULT_WEBHOOK_URL }
        settings.authToken = binding.etToken.text.toString()
        settings.timeoutSeconds = binding.etTimeout.text.toString().toIntOrNull()
            ?: AppSettings.DEFAULT_TIMEOUT
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            })
        }
    }

    private fun previewSpeech() {
        // Save first so the new instance picks up the current slider value.
        settings.ttsSpeechRate = binding.sliderTtsRate.value
        previewTts?.stop()
        previewTts = ThaiTTS(this).apply {
            speak("สวัสดีครับ ทดสอบความเร็วเสียงพูด")
        }
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    override fun onDestroy() {
        previewTts?.stop()
        previewTts = null
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
