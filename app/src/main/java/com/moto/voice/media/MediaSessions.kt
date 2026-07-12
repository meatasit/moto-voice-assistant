package com.moto.voice.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.util.Log
import com.moto.voice.bt.MotoMediaNotificationListener

/**
 * v1.3.11 — thin wrapper around [MediaSessionManager.getActiveSessions] so the
 * pipeline can:
 *
 *   * verify a media action actually played (read [PlaybackState.getState] instead
 *     of guessing from [android.media.AudioManager.isMusicActive]);
 *   * seek forward/backward via [MediaController.getTransportControls];
 *   * pause the currently-playing session directly (skip the media-key round trip
 *     which some OEMs eat).
 *
 * Every method is **safe when the notification-listener permission is denied**:
 *   * [hasPermission] returns false → callers know to skip the controller path.
 *   * [activeControllers] returns empty list.
 *   * [controllerFor] returns null.
 *
 * No throws, no crashes — the fallback in each call site is media-key dispatch
 * which does not require this permission.
 */
object MediaSessions {

    private const val TAG = "MediaSessions"
    /** The YouTube app package we deep-link into and want to control. */
    const val YOUTUBE_PKG = "com.google.android.youtube"

    /**
     * Whether the user has granted notification-listener access to Moto Voice.
     * Cheaper than getActiveSessions() (no manager service call) so callers can
     * quick-branch.
     */
    fun hasPermission(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        val cn = ComponentName(context, MotoMediaNotificationListener::class.java).flattenToString()
        val cnShort = "${context.packageName}/${MotoMediaNotificationListener::class.java.name}"
        // The setting can serialize the component name in either the fully-qualified
        // or the short form depending on OEM; check both.
        return enabled.split(":").any { it == cn || it == cnShort }
    }

    /**
     * All active MediaControllers this app can see, sorted so the one most likely
     * currently playing comes first: PLAYING > BUFFERING > PAUSED > everything else.
     * Empty list on missing permission or platform error — never null, never throws.
     */
    fun activeControllers(context: Context): List<MediaController> {
        if (!hasPermission(context)) return emptyList()
        val msm = runCatching { context.getSystemService(MediaSessionManager::class.java) }
            .getOrNull() ?: return emptyList()
        val cn = ComponentName(context, MotoMediaNotificationListener::class.java)
        return runCatching { msm.getActiveSessions(cn) }
            .onFailure { Log.w(TAG, "getActiveSessions failed", it) }
            .getOrNull()
            .orEmpty()
            .sortedBy { playbackPriority(it.playbackState?.state) }
    }

    /**
     * The MediaController for [packageName] if one is currently active, else null.
     * Used by the YouTube nudge upgrade (§3) to check playbackState of the video
     * the pipeline just deep-linked into.
     */
    fun controllerFor(context: Context, packageName: String): MediaController? {
        return activeControllers(context).firstOrNull { it.packageName == packageName }
    }

    /**
     * For the "package we asked about wasn't running any session" log path.
     * Returns the packages of currently-active sessions so the debug entry can
     * show what WAS available — spec §1 tail: "log media_ctrl_pkg_miss with the
     * actual package name found, so future variants show up in debug logs".
     */
    fun activePackagesForDebug(context: Context): List<String> =
        activeControllers(context).map { it.packageName }

    /** Priority key: lower = more likely "this is what's actually playing right now". */
    private fun playbackPriority(state: Int?): Int = when (state) {
        PlaybackState.STATE_PLAYING -> 0
        PlaybackState.STATE_BUFFERING -> 1
        PlaybackState.STATE_PAUSED -> 2
        PlaybackState.STATE_CONNECTING -> 3
        PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> 4
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> 5
        PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE, null -> 8
        else -> 9
    }

    /** Human-readable label of a PlaybackState.state int — used for debug logging. */
    fun stateName(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_NONE -> "none"
        PlaybackState.STATE_CONNECTING -> "connecting"
        PlaybackState.STATE_FAST_FORWARDING -> "ff"
        PlaybackState.STATE_REWINDING -> "rew"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "skip_next"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "skip_prev"
        PlaybackState.STATE_ERROR -> "error"
        null -> "no_state"
        else -> "unknown_$state"
    }
}
