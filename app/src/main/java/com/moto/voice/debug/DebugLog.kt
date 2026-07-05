package com.moto.voice.debug

import android.content.Context
import com.google.gson.GsonBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class DebugEntry(
    val timestamp: Long = System.currentTimeMillis(),
    var sttPartial: String = "",
    var sttFinal: String = "",
    var webhookRequest: String? = null,
    var webhookResponse: String? = null,
    var scoTimeMs: Long = 0,
    var sttTimeMs: Long = 0,
    var webhookTimeMs: Long = 0,
    var actionTimeMs: Long = 0,
    var error: String? = null,
) {
    fun time(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))

    fun summary(): String = buildString {
        append("[${time()}]")
        if (sttFinal.isNotBlank()) append("  STT: \"$sttFinal\"")
        if (scoTimeMs > 0) append("  SCO:${scoTimeMs}ms")
        if (sttTimeMs > 0) append("  STT:${sttTimeMs}ms")
        if (webhookTimeMs > 0) append("  WH:${webhookTimeMs}ms")
        if (actionTimeMs > 0) append("  ACT:${actionTimeMs}ms")
        if (error != null) append("  ⚠️ $error")
    }
}

object DebugLog {
    private const val MAX = 30
    private val list = CopyOnWriteArrayList<DebugEntry>()
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun new(): DebugEntry {
        val e = DebugEntry()
        list.add(0, e)
        while (list.size > MAX) list.removeAt(list.size - 1)
        return e
    }

    fun entries(): List<DebugEntry> = list.toList()

    fun clear() = list.clear()

    fun exportToFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "moto_voice_debug_${System.currentTimeMillis()}.json")
        file.writeText(gson.toJson(list.toList()))
        return file
    }
}
