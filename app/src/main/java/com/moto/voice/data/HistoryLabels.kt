package com.moto.voice.data

/**
 * v1.3.35 — rider-facing wording for a [HistoryAction], extracted from
 * [com.moto.voice.HistoryActivity] so the home screen can render the same rows without
 * a second copy of the when-blocks drifting out of sync.
 *
 * Pure string mapping: no Context, no resources — JVM-testable, and the strings stay
 * next to the sealed class they describe.
 */
object HistoryLabels {

    fun icon(action: HistoryAction): String = when (action) {
        is HistoryAction.Call -> "📞"
        is HistoryAction.YoutubeOpen -> "▶"
        is HistoryAction.FmPlay -> "📻"
        HistoryAction.Stop -> "⏹"
        is HistoryAction.Speak -> "💬"
        is HistoryAction.Chat -> "🗨"
    }

    fun title(action: HistoryAction): String = when (action) {
        is HistoryAction.Call -> "โทรหา ${action.name}"
        is HistoryAction.YoutubeOpen ->
            if (action.title.isNotBlank()) "เปิด YouTube: ${action.title}" else "เปิด YouTube"
        is HistoryAction.FmPlay -> "เปิดวิทยุ ${action.stationName}"
        HistoryAction.Stop -> "หยุดเสียง"
        is HistoryAction.Speak -> "ผู้ช่วยพูด"
        is HistoryAction.Chat -> "คุยกับจาวิส"
    }

    fun subtitle(entry: HistoryEntry): String =
        "คุณพูด: " + entry.heard.ifBlank { "(ไม่ได้จับความ)" }

    /**
     * Whether tapping the row can re-run the action. Speak/Chat have nothing to replay —
     * home uses this to skip wiring a click listener that would do nothing.
     */
    fun isReplayable(action: HistoryAction): Boolean = when (action) {
        is HistoryAction.Call, is HistoryAction.YoutubeOpen, is HistoryAction.FmPlay,
        HistoryAction.Stop -> true
        is HistoryAction.Speak, is HistoryAction.Chat -> false
    }
}
