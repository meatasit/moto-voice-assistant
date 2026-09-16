package com.moto.voice

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.method.PasswordTransformationMethod
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.moto.voice.data.AppSettings
import com.moto.voice.data.SettingsBackup
import com.moto.voice.databinding.ActivitySettingsBinding
import com.moto.voice.network.WebhookClient
import com.moto.voice.nlu.ErrorSpeech
import com.moto.voice.tts.ThaiTTS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.4.0 — the Azure Neural TTS section is gone with the engine (see [com.moto.voice.tts.TtsRouter]).
 * New at the top: the brain selector, Cloud AI (default) vs Local LLM — see
 * [AppSettings.llmProvider] for why the default flipped.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: AppSettings
    private var tokenVisible = false
    private var testJob: Job? = null
    private var previewTts: ThaiTTS? = null

    /** SAF: pick a destination and write the current settings as JSON. */
    private val createBackupFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { writeBackup(it) } }

    /** SAF: pick an existing backup JSON and restore. */
    private val openBackupFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { readBackup(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
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
        binding.switchEarconOnSco.isChecked = settings.earconOnScoStream
        binding.switchYoutubeWebLink.isChecked = settings.youtubeWebLink
        binding.switchYoutubeMediaBrowser.isChecked = settings.youtubeMediaBrowser
        binding.sliderTtsRate.value = settings.ttsSpeechRate
        binding.tvTtsRateValue.text = formatRate(settings.ttsSpeechRate)
        binding.switchResumeAfterCall.isChecked = settings.resumeAfterCall
        binding.switchFollowup.isChecked = settings.followupEnabled
        binding.switchConfirmMediaStart.isChecked = settings.confirmMediaStart
        binding.sliderAssistantVolume.value = settings.assistantVolume
        binding.tvAssistantVolumeValue.text = formatRate(settings.assistantVolume)
        binding.sliderListenPace.value = settings.listenPaceSeconds
        binding.tvListenPaceValue.text = formatSeconds(settings.listenPaceSeconds)

        // Brain selector — check() before the listener is attached so loading doesn't write.
        binding.toggleLlmProvider.check(
            if (settings.llmProvider == AppSettings.LLM_LOCAL) R.id.btnLlmLocal else R.id.btnLlmApi
        )
        showLlmProviderHint()
    }

    private fun showLlmProviderHint() {
        binding.tvLlmProviderHint.text = if (settings.llmProvider == AppSettings.LLM_LOCAL) {
            "Ollama บนคอมที่บ้าน — ฟรีและเป็นส่วนตัว แต่ต้องเปิดเครื่องไว้ และคำสั่งแรกอาจช้าถ้าโมเดลยังไม่โหลด"
        } else {
            "แนะนำ — ตอบภายใน 2–3 วินาทีทุกครั้ง ไม่ต้องรอโหลดโมเดล กุญแจเก็บไว้ที่ n8n ไม่อยู่ในมือถือ"
        }
    }

    private fun formatRate(rate: Float): String = "%.1fx".format(rate)

    private fun formatSeconds(seconds: Float): String = "%.1fs".format(seconds)

    private fun setupListeners() {
        binding.btnShowToken.setOnClickListener {
            tokenVisible = !tokenVisible
            binding.etToken.transformationMethod =
                if (tokenVisible) null else PasswordTransformationMethod.getInstance()
            binding.etToken.setSelection(binding.etToken.text?.length ?: 0)
            binding.btnShowToken.text = if (tokenVisible) getString(R.string.token_hide) else getString(R.string.token_show)
        }

        binding.toggleLlmProvider.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            settings.llmProvider = if (checkedId == R.id.btnLlmLocal) AppSettings.LLM_LOCAL else AppSettings.LLM_API
            showLlmProviderHint()
        }

        binding.switchLlm.setOnCheckedChangeListener { _, v -> settings.llmMode = v }
        binding.switchConfirmCall.setOnCheckedChangeListener { _, v -> settings.confirmBeforeCall = v }
        binding.switchAskYoutube.setOnCheckedChangeListener { _, v -> settings.askBeforeYoutube = v }
        binding.switchGreetOnConnect.setOnCheckedChangeListener { _, v -> settings.greetOnConnect = v }
        binding.switchEarconOnSco.setOnCheckedChangeListener { _, v -> settings.earconOnScoStream = v }
        binding.switchYoutubeWebLink.setOnCheckedChangeListener { _, v -> settings.youtubeWebLink = v }
        binding.switchYoutubeMediaBrowser.setOnCheckedChangeListener { _, v -> settings.youtubeMediaBrowser = v }

        binding.sliderTtsRate.addOnChangeListener { _, value, _ ->
            binding.tvTtsRateValue.text = formatRate(value)
            settings.ttsSpeechRate = value
        }
        binding.sliderAssistantVolume.addOnChangeListener { _, value, _ ->
            binding.tvAssistantVolumeValue.text = formatRate(value)
            settings.assistantVolume = value
        }
        binding.sliderListenPace.addOnChangeListener { _, value, _ ->
            binding.tvListenPaceValue.text = formatSeconds(value)
            settings.listenPaceSeconds = value
        }
        binding.switchResumeAfterCall.setOnCheckedChangeListener { _, v -> settings.resumeAfterCall = v }
        binding.switchFollowup.setOnCheckedChangeListener { _, v -> settings.followupEnabled = v }
        binding.switchConfirmMediaStart.setOnCheckedChangeListener { _, v -> settings.confirmMediaStart = v }
        binding.btnPreviewTts.setOnClickListener { previewSpeech() }

        binding.btnTestConnection.setOnClickListener { testConnection() }

        binding.btnDefaultAssistant.setOnClickListener {
            // Unified with OnboardingActivity via [AssistantRoleHelper] (v1.3.6) —
            // used to be two copies that diverged (Settings worked, onboarding didn't).
            runCatching { startActivity(com.moto.voice.bt.AssistantRoleHelper.defaultAssistantPickerIntent(this)) }
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
        binding.btnSystemStatus.setOnClickListener {
            startActivity(Intent(this, SystemStatusActivity::class.java))
        }
        binding.btnExportSettings.setOnClickListener {
            createBackupFile.launch("moto_voice_backup.json")
        }
        binding.btnImportSettings.setOnClickListener {
            openBackupFile.launch(arrayOf("application/json"))
        }
    }

    private fun writeBackup(uri: android.net.Uri) {
        lifecycleScope.launch {
            val ok = runCatching {
                val json = SettingsBackup.toJson(SettingsBackup.snapshot(this@SettingsActivity))
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "w")?.use { it.write(json.toByteArray()) }
                        ?: throw IllegalStateException("cannot open output stream")
                }
                true
            }.getOrElse {
                Toast.makeText(this@SettingsActivity, "Export ล้มเหลว: ${it.message}", Toast.LENGTH_LONG).show()
                false
            }
            if (ok) Toast.makeText(this@SettingsActivity, "Export สำเร็จ", Toast.LENGTH_SHORT).show()
        }
    }

    private fun readBackup(uri: android.net.Uri) {
        lifecycleScope.launch {
            val backup = runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("cannot read file")
                }
                SettingsBackup.fromJson(bytes.toString(Charsets.UTF_8))
            }.getOrElse {
                Toast.makeText(this@SettingsActivity, "Import ล้มเหลว: ${it.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            SettingsBackup.restore(this@SettingsActivity, backup)
            loadSettings()  // refresh UI to reflect restored values
            Toast.makeText(this@SettingsActivity, "Import สำเร็จ — เข้ารหัส Auth Token ใหม่ในหน้านี้", Toast.LENGTH_LONG).show()
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
        val provider = settings.llmProvider
        testJob = lifecycleScope.launch {
            val result = WebhookClient(url, token, timeout, provider).call("ทดสอบระบบ")
            binding.btnTestConnection.isEnabled = true
            binding.tvTestResult.text = when (result) {
                is WebhookClient.Result.Success -> "✅ ${result.elapsedMs}ms · สมอง: $provider\n${result.rawJson.take(400)}"
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
            speak(ErrorSpeech.PREVIEW_SAMPLE)
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
