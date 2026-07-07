package com.moto.voice.pipeline

import android.Manifest
import android.app.role.RoleManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.moto.voice.data.AppSettings
import com.moto.voice.data.NetworkState
import com.moto.voice.network.WebhookClient
import com.moto.voice.tts.AzureTtsState

/**
 * Single-shot health check across every subsystem that could quietly break between
 * upgrades. Consumed by [com.moto.voice.SystemStatusActivity] for the tap-through
 * "System Status" page (spec §7).
 *
 * Each row is either static (computed synchronously) or async (webhook / TTS) —
 * async ones live behind [checkAsync] so the UI can render the static ones instantly
 * and then refresh individual rows as their tests complete.
 */
data class StatusRow(
    val id: Kind,
    val label: String,
    val state: State,
    /** Human-friendly detail: latency ms, connected device name, missing perm list, etc. */
    val detail: String = "",
    /** Intent to launch when the rider taps this row to fix it. null = row has no fixup. */
    val fixIntent: Intent? = null,
) {
    enum class Kind { DefaultAssistant, Permissions, Battery, Helmet, Webhook, Tts, Internet }
    enum class State { Green, Red, Yellow, Pending }
}

class SystemStatusChecker(private val context: Context) {

    /** Synchronous rows (everything except Webhook + TTS). Ordering matches the UI. */
    fun checkSync(): List<StatusRow> = listOf(
        checkDefaultAssistant(),
        checkPermissions(),
        checkBattery(),
        checkHelmet(),
        checkInternet(),
        // Placeholders for async rows so the UI can render them as Pending while the tests run.
        StatusRow(StatusRow.Kind.Webhook, "Webhook", StatusRow.State.Pending, "กำลังทดสอบ..."),
        StatusRow(StatusRow.Kind.Tts, "TTS", StatusRow.State.Pending, "กำลังทดสอบ..."),
    )

    /** @return the freshly-tested Webhook row. Latency reported on success. */
    suspend fun checkWebhook(): StatusRow {
        val settings = AppSettings(context)
        if (settings.webhookUrl.isBlank()) {
            return StatusRow(
                StatusRow.Kind.Webhook, "Webhook", StatusRow.State.Yellow,
                detail = "ยังไม่ตั้ง URL",
                fixIntent = null,
            )
        }
        val result = WebhookClient(settings.webhookUrl, settings.authToken, settings.timeoutSeconds)
            .call("ทดสอบระบบ")
        return when (result) {
            is WebhookClient.Result.Success -> StatusRow(
                StatusRow.Kind.Webhook, "Webhook", StatusRow.State.Green,
                detail = "${result.elapsedMs}ms",
            )
            is WebhookClient.Result.Failure -> StatusRow(
                StatusRow.Kind.Webhook, "Webhook", StatusRow.State.Red,
                detail = "${result.kind} — ${result.error}",
            )
        }
    }

    /**
     * TTS row: reports the live Azure result if the user has configured Azure, else
     * falls back to a plain "Android TTS engine present" check.
     */
    fun checkTts(): StatusRow {
        val settings = AppSettings(context)
        val hasAzureConfig = settings.azureKey.isNotBlank() && settings.azureRegion.isNotBlank()

        // Base: is there an Android TTS engine at all? — the silent fallback path needs it.
        val hasAndroidEngine = context.packageManager
            .queryIntentServices(Intent("android.intent.action.TTS_SERVICE"), 0)
            .isNotEmpty()

        // When Azure is configured, prefer to show the live Azure result.
        if (hasAzureConfig) {
            return when (AzureTtsState.result()) {
                AzureTtsState.LastResult.Ok -> StatusRow(
                    StatusRow.Kind.Tts, "Azure TTS", StatusRow.State.Green,
                    detail = "ล่าสุด: synth ${AzureTtsState.synthMs()}ms · play ${AzureTtsState.playMs()}ms" +
                        if (AzureTtsState.cacheHit()) " · cache" else "",
                )
                AzureTtsState.LastResult.Failed -> StatusRow(
                    StatusRow.Kind.Tts, "Azure TTS", StatusRow.State.Yellow,
                    detail = "ล่าสุด: ${AzureTtsState.error() ?: "unknown"} — ใช้ Android แทน",
                )
                AzureTtsState.LastResult.Never -> StatusRow(
                    StatusRow.Kind.Tts, "Azure TTS", StatusRow.State.Yellow,
                    detail = "ตั้งค่าแล้ว ยังไม่ทดสอบ — กดฟังตัวอย่างในหน้าตั้งค่า",
                )
            }
        }

        return if (hasAndroidEngine) StatusRow(
            StatusRow.Kind.Tts, "TTS (Android)", StatusRow.State.Green,
            detail = "พร้อมใช้งาน — ยังไม่ได้ตั้ง Azure",
        ) else StatusRow(
            StatusRow.Kind.Tts, "TTS", StatusRow.State.Red,
            detail = "ไม่พบ TTS engine",
            fixIntent = runCatching { Intent("com.android.settings.TTS_SETTINGS") }
                .getOrNull()?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    // ─── Individual checks ───────────────────────────────────────────────────

    private fun checkDefaultAssistant(): StatusRow {
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        } else {
            Settings.Secure.getString(context.contentResolver, "assistant")
                ?.contains(context.packageName) == true
        }
        val fix = runCatching { Intent(Settings.ACTION_VOICE_INPUT_SETTINGS) }.getOrNull()
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return StatusRow(
            StatusRow.Kind.DefaultAssistant, "Default Assistant",
            if (ok) StatusRow.State.Green else StatusRow.State.Red,
            detail = if (ok) "ตั้งไว้แล้ว" else "ยังไม่ได้ตั้ง",
            fixIntent = if (ok) null else fix,
        )
    }

    private fun checkPermissions(): StatusRow {
        val required = listOfNotNull(
            Manifest.permission.RECORD_AUDIO to "ไมโครโฟน",
            Manifest.permission.READ_CONTACTS to "รายชื่อ",
            Manifest.permission.CALL_PHONE to "โทรออก",
            Manifest.permission.BLUETOOTH_CONNECT to "Bluetooth",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.POST_NOTIFICATIONS to "การแจ้งเตือน" else null,
        )
        val missing = required.filterNot {
            ContextCompat.checkSelfPermission(context, it.first) == PackageManager.PERMISSION_GRANTED
        }
        val fix = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return StatusRow(
            StatusRow.Kind.Permissions, "สิทธิ์",
            if (missing.isEmpty()) StatusRow.State.Green else StatusRow.State.Red,
            detail = if (missing.isEmpty()) "ครบทุกรายการ"
                     else "ขาด: " + missing.joinToString(", ") { it.second },
            fixIntent = if (missing.isEmpty()) null else fix,
        )
    }

    private fun checkBattery(): StatusRow {
        val pm = context.getSystemService(PowerManager::class.java)
        val ok = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        val fix = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return StatusRow(
            StatusRow.Kind.Battery, "Battery unrestricted",
            if (ok) StatusRow.State.Green else StatusRow.State.Yellow,
            detail = if (ok) "ยกเว้นแล้ว" else "อาจโดนฆ่าเมื่อจอปิด",
            fixIntent = if (ok) null else fix,
        )
    }

    private fun checkHelmet(): StatusRow {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            return StatusRow(
                StatusRow.Kind.Helmet, "หมวก / Bluetooth",
                StatusRow.State.Yellow, detail = "ต้องขอสิทธิ์ก่อนถึงจะเช็คได้",
            )
        }
        val bm = context.getSystemService(BluetoothManager::class.java)
        val adapter: BluetoothAdapter? = bm?.adapter
        val connected = runCatching {
            adapter?.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
        }.getOrDefault(false)
        return StatusRow(
            StatusRow.Kind.Helmet, "หมวก / Bluetooth",
            if (connected) StatusRow.State.Green else StatusRow.State.Yellow,
            detail = if (connected) "เชื่อมต่อแล้ว (HFP)" else "ยังไม่เชื่อม",
        )
    }

    private fun checkInternet(): StatusRow {
        val online = NetworkState.isOnline(context)
        return StatusRow(
            StatusRow.Kind.Internet, "อินเทอร์เน็ต",
            if (online) StatusRow.State.Green else StatusRow.State.Red,
            detail = if (online) "ออนไลน์" else "ออฟไลน์",
        )
    }
}
