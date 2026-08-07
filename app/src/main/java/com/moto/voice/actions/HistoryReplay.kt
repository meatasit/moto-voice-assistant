package com.moto.voice.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.moto.voice.data.HistoryAction
import com.moto.voice.media.FmPlayerService

/**
 * v1.3.35 — "do that again" for a history row. Lifted out of
 * [com.moto.voice.HistoryActivity] so [com.moto.voice.MainActivity] can offer the same
 * tap-to-repeat on the home list without duplicating the launch logic.
 *
 * Note this is a rider-initiated foreground tap, not the voice pipeline: the screen is
 * on and the app is in front, so a plain `startActivity` is fine here — the lock-screen
 * FSI dance in [com.moto.voice.media.MediaOrchestrator] exists for the locked case and
 * is deliberately not duplicated.
 */
object HistoryReplay {

    /** @return true when something was launched, false for actions with nothing to replay. */
    fun repeat(context: Context, action: HistoryAction): Boolean = when (action) {
        is HistoryAction.Call -> runCatching {
            context.startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(action.number)}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess

        is HistoryAction.YoutubeOpen -> runCatching {
            val app = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:${action.videoId}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val web = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/watch?v=${action.videoId}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val target = if (app.resolveActivity(context.packageManager) != null) app else web
            context.startActivity(target)
        }.isSuccess

        is HistoryAction.FmPlay -> runCatching {
            val intent = Intent(context, FmPlayerService::class.java)
                .setAction(FmPlayerService.ACTION_PLAY)
                .putExtra(FmPlayerService.EXTRA_STREAM_URL, action.streamUrl)
                .putExtra(FmPlayerService.EXTRA_LABEL, action.stationName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }.isSuccess

        HistoryAction.Stop -> {
            MediaStopper.stopAnySimple(context)
            true
        }

        // Nothing to re-run: a spoken line / chat reply is not an action.
        is HistoryAction.Speak, is HistoryAction.Chat -> false
    }
}
