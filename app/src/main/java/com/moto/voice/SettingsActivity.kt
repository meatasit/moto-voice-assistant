package com.moto.voice

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.method.PasswordTransformationMethod
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.moto.voice.data.AppSettings
import com.moto.voice.data.SettingsBackup
import com.moto.voice.databinding.ActivitySettingsBinding
import com.moto.voice.network.WebhookClient
import com.moto.voice.nlu.ErrorSpeech
import com.moto.voice.nlu.PersonaHolder
import com.moto.voice.tts.AzureTtsState
import com.moto.voice.tts.ThaiTTS
import com.moto.voice.tts.TtsRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: AppSettings
    private var tokenVisible = false
    private var azureKeyVisible = false
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
        binding.switchResumeAfterCall.isChecked = settings.resumeAfterCall
        binding.switchFollowup.isChecked = settings.followupEnabled
        binding.switchConfirmMediaStart.isChecked = settings.confirmMediaStart
        binding.sliderAssistantVolume.value = settings.assistantVolume
        binding.tvAssistantVolumeValue.text = formatRate(settings.assistantVolume)
        binding.sliderListenPace.value = settings.listenPaceSeconds
        binding.tvListenPaceValue.text = formatSeconds(settings.listenPaceSeconds)

        // Azure section
        binding.etAzureRegion.setText(settings.azureRegion)
        binding.etAzureKey.setText(settings.azureKey)
        binding.etAzureKey.transformationMethod = PasswordTransformationMethod.getInstance()
        loadVoiceDropdown()
    }

    private fun loadVoiceDropdown() {
        val voices = AppSettings.AZURE_VOICES
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, voices)
        binding.ddAzureVoice.setAdapter(adapter)
        binding.ddAzureVoice.setText(settings.azureVoice, false)
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

        binding.switchLlm.setOnCheckedChangeListener { _, v -> settings.llmMode = v }
        binding.switchConfirmCall.setOnCheckedChangeListener { _, v -> settings.confirmBeforeCall = v }
        binding.switchAskYoutube.setOnCheckedChangeListener { _, v -> settings.askBeforeYoutube = v }
        binding.switchGreetOnConnect.setOnCheckedChangeListener { _, v -> settings.greetOnConnect = v }

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

        // ─── Azure Neural TTS ────────────────────────────────────────────────
        binding.btnShowAzureKey.setOnClickListener {
            azureKeyVisible = !azureKeyVisible
            binding.etAzureKey.transformationMethod =
                if (azureKeyVisible) null else PasswordTransformationMethod.getInstance()
            binding.etAzureKey.setSelection(binding.etAzureKey.text?.length ?: 0)
            binding.btnShowAzureKey.text = if (azureKeyVisible) getString(R.string.token_hide) else getString(R.string.token_show)
        }
        binding.ddAzureVoice.setOnItemClickListener { _, _, position, _ ->
            val voice = AppSettings.AZURE_VOICES[position]
            settings.azureVoice = voice
            // Auto-update persona per §5.2 so ค่ะ/ครับ flips whenever voice does.
            val newPersona = com.moto.voice.nlu.PersonaHolder.personaForVoice(voice)
            PersonaHolder.set(newPersona)
            settings.persona = if (newPersona == com.moto.voice.nlu.Persona.Feminine)
                AppSettings.PERSONA_FEMININE else AppSettings.PERSONA_MASCULINE
            // Rebuild the Azure engine so it uses the new voice; warm cache in bg.
            val router = TtsRouter.getOrCreate(this@SettingsActivity)
            router.reloadAzureConfig()
            router.warmCache()
        }
        binding.btnAzurePreview.setOnClickListener { previewAzure() }
    }

    /** Save Azure region/key + fire a preview through TtsRouter to validate the key. */
    private fun previewAzure() {
        // Persist inputs before we spin up the engine.
        settings.azureRegion = binding.etAzureRegion.text.toString().trim()
            .ifBlank { AppSettings.DEFAULT_AZURE_REGION }
        settings.azureKey = binding.etAzureKey.text.toString().trim()
        settings.azureVoice = binding.ddAzureVoice.text.toString()
            .ifBlank { AppSettings.DEFAULT_AZURE_VOICE }

        if (settings.azureKey.isBlank()) {
            binding.tvAzureResult.text = "❌ ยังไม่ได้กรอก key"
            return
        }
        binding.tvAzureResult.text = "กำลังสังเคราะห์..."
        binding.btnAzurePreview.isEnabled = false

        // Force router to pick up the new config immediately.
        val router = TtsRouter.getOrCreate(this)
        router.reloadAzureConfig()

        val start = System.currentTimeMillis()
        previewTts?.stop()
        previewTts = ThaiTTS(this).apply {
            speak(ErrorSpeech.PREVIEW_SAMPLE) {
                runOnUiThread {
                    binding.btnAzurePreview.isEnabled = true
                    binding.tvAzureResult.text = when (AzureTtsState.result()) {
                        AzureTtsState.LastResult.Ok ->
                            "✅ synth ${AzureTtsState.synthMs()}ms · play ${AzureTtsState.playMs()}ms" +
                                if (AzureTtsState.cacheHit()) " · cache" else ""
                        AzureTtsState.LastResult.Failed ->
                            "❌ ${AzureTtsState.error() ?: "unknown"} — ใช้ Android แทน (${System.currentTimeMillis() - start}ms)"
                        AzureTtsState.LastResult.Never ->
                            "⚠️ ไม่ได้เรียก Azure — key ว่างหรือออฟไลน์ (${System.currentTimeMillis() - start}ms)"
                    }
                }
            }
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
