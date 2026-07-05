package com.moto.voice

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.moto.voice.data.NetworkState
import com.moto.voice.databinding.ActivityMainBinding
import com.moto.voice.debug.DebugLogActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val allPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    private val requestSinglePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updateUI() }

    private val requestRoleResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateUI() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnMic.setOnClickListener { requestSinglePermission.launch(Manifest.permission.RECORD_AUDIO) }
        binding.btnContacts.setOnClickListener { requestSinglePermission.launch(Manifest.permission.READ_CONTACTS) }
        binding.btnCall.setOnClickListener { requestSinglePermission.launch(Manifest.permission.CALL_PHONE) }
        binding.btnBluetooth.setOnClickListener { requestSinglePermission.launch(Manifest.permission.BLUETOOTH_CONNECT) }
        binding.btnSetDefault.setOnClickListener { openDefaultAssistantSettings() }
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.btnDebugLog.setOnClickListener { startActivity(Intent(this, DebugLogActivity::class.java)) }
        binding.btnRidingMode.setOnClickListener { startActivity(Intent(this, RidingModeActivity::class.java)) }
        binding.btnHistory.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        binding.btnFavorites.setOnClickListener { startActivity(Intent(this, FavoritesActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        updatePermButton(binding.btnMic, Manifest.permission.RECORD_AUDIO)
        updatePermButton(binding.btnContacts, Manifest.permission.READ_CONTACTS)
        updatePermButton(binding.btnCall, Manifest.permission.CALL_PHONE)
        updatePermButton(binding.btnBluetooth, Manifest.permission.BLUETOOTH_CONNECT)

        val allGranted = allPermissions.all { hasPermission(it) }
        val isDefault = isDefaultAssistant()
        val online = NetworkState.isOnline(this)
        val offlineSuffix = if (!online) "  •  ออฟไลน์" else ""
        when {
            isDefault && allGranted -> { binding.tvStatusIcon.text = if (online) "✅" else "📡"; binding.tvStatus.text = getString(R.string.status_ready) + offlineSuffix }
            !isDefault -> { binding.tvStatusIcon.text = "⚠️"; binding.tvStatus.text = getString(R.string.status_not_default) + offlineSuffix }
            else -> { binding.tvStatusIcon.text = "⚠️"; binding.tvStatus.text = getString(R.string.status_missing_perms) + offlineSuffix }
        }
    }

    private fun updatePermButton(btn: android.widget.Button, permission: String) {
        val granted = hasPermission(permission)
        btn.text = if (granted) getString(R.string.perm_granted) else getString(R.string.perm_grant)
        btn.isEnabled = !granted
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

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
        catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            })
        }
    }
}
