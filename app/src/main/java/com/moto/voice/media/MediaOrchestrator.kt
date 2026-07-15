package com.moto.voice.media

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.moto.voice.actions.MediaStopper
import com.moto.voice.debug.DebugEntry
import com.moto.voice.debug.FinishReason
import com.moto.voice.nlu.ErrorSpeech
import com.moto.voice.pipeline.NudgeDecider
import com.moto.voice.pipeline.StopSequenceTargets
import com.moto.voice.tts.ThaiTTS
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * v1.3.20 stabilization sprint — the SINGLE OWNER of every media control operation
 * (openYoutube / playContinue / pauseAll / seek). All previously-scattered logic
 * — prepauseIfMusicActive, stopYoutubeControllerIfActive, scheduleYoutubeNudge,
 * scheduleYoutubeNudgeFallback, executeStopSequence's controller loop, handleSeek —
 * has been consolidated here so a rider's field log never again shows our own code
 * dispatching a media key to the wrong app.
 *
 * Three iron rules (also recorded in [CLAUDE.md]):
 *
 *   **Rule #1 — Every op has an explicit targetPkg.**
 *   Media keys (KEYCODE_MEDIA_PLAY / PAUSE / FF / REW) are the "aimless gun" that
 *   hijacked Spotify in field log 1784028862496. They dispatch to whichever session
 *   is currently focused, which is not necessarily what we asked for. This class
 *   uses [MediaController.transportControls] with an explicit target package
 *   whenever notification-listener access is granted. Media keys are only used as
 *   a fallback when [MediaSessions.hasPermission] returns false — the rider hasn't
 *   granted us the ability to name a target.
 *
 *   **Rule #2 — Remember [MediaSessionMemory.lastOpenedApp] and [MediaSessionMemory.lastVideoId].**
 *   "เล่นต่อ" targets what WE opened, not whichever session is topmost. If the
 *   rider names an app in the sentence ("เล่น YouTube ต่อ"), that name wins.
 *   If the target has no session (e.g. YouTube got Background-Activity-Launch
 *   blocked while the screen was locked), we refire the deep link for
 *   [MediaSessionMemory.lastVideoId] rather than dispatching play() into thin air.
 *
 *   **Rule #3 — Every op writes [DebugEntry.mediaTargetPkg] + appends to
 *   [DebugEntry.mediaOperations].** Field logs must answer "who did we command,
 *   and what actually happened" without guessing.
 *
 * Operations are serialized by [mutex] so a nudge running via [Handler] can't
 * collide with a fresh openYoutube. Pending nudges are cancelled at the top of
 * every op — a new command supersedes an in-flight one.
 *
 * All suspend methods return a [Result] so the pipeline can log outcomes for
 * debugging and speak honest TTS ("launch blocked", "opened not playing", etc.).
 * The pipeline never touches [android.media.session.MediaController] directly
 * outside this class after the sprint.
 */
object MediaOrchestrator {

    private const val TAG = "MediaOrchestrator"

    /**
     * Wait for the target app to register a MediaSession after the deep link.
     * Deep-link cold-start + splash typically 800ms–3s; extended to 9s after
     * v1.3.19 so active play() attempts inside the window have room to work.
     */
    private const val POLL_INITIAL_DELAY_MS = 800L
    private const val POLL_INTERVAL_MS = 500L
    private const val POLL_WINDOW_MS = 9_000L
    private const val NUDGE_SETTLE_MS = 2_000L
    private const val NUDGE_MAX_ATTEMPTS = 3
    private const val NUDGE_RETRY_SPACING_MS = 1_500L
    /** After the poll window with no target session at all → launch was likely blocked. */
    private const val LAUNCH_BLOCKED_THRESHOLD_MS = POLL_WINDOW_MS + POLL_INITIAL_DELAY_MS

    private val mutex = Mutex()
    // `lazy` so pure-JVM tests (which never schedule a nudge) don't trip on
    // Handler(Looper.getMainLooper()) — that call throws in a non-instrumented
    // test because android.os.Looper isn't mocked.
    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var pendingNudge: Runnable? = null

    /**
     * Whether a successful nudge should speak [ErrorSpeech.MEDIA_PLAY_CONFIRMED].
     * Rider preference — [com.moto.voice.data.AppSettings.confirmMediaStart].
     * Pipeline sets this before each op; default true.
     */
    @Volatile var speakPlayConfirmed: Boolean = true

    /** What an orchestrated op accomplished (or didn't) — spoken/logged by caller. */
    sealed class Result {
        object Success : Result()
        /** Deep link succeeded but the target app's session never registered. */
        object LaunchBlocked : Result()
        /** Target session found + [MediaController.TransportControls] call fired. */
        object CommandDispatched : Result()
        /** No target could be identified (e.g. seek with no lastOpenedApp). */
        object NoTarget : Result()
        /** No permission to enumerate sessions + no explicit target → nothing safe to do. */
        object NoPermission : Result()
        /** Media-key fallback path used (only when [MediaSessions.hasPermission] is false). */
        data class MediaKeyFallback(val keycode: String) : Result()
    }

    /**
     * Open a YouTube deep link. Consolidates the v1.3.11 openYoutube + v1.3.18
     * pause-not-stop pre-clean + v1.3.19 active nudge into one orchestrated op.
     */
    suspend fun openYoutube(
        context: Context, videoId: String?, query: String?, entry: DebugEntry,
        expectedTitle: String? = null,
    ): Result = mutex.withLock {
        cancelPendingNudge()
        logOp(entry, "openYoutube:${videoId ?: query.orEmpty()}", MediaSessions.YOUTUBE_PKG)
        entry.mediaExpectedTitle = expectedTitle
        entry.screenLocked = isScreenLocked(context)

        // v1.3.21 — capture what YouTube is playing BEFORE we fire the intent. If the
        // session still shows this same title after the poll window, the switch never
        // happened (Background-Activity-Launch block while locked, per field log
        // 1784074856214) and we must NOT resume it — that masked the failure as success.
        val priorTitle = sessionTitle(MediaSessions.controllerFor(context, MediaSessions.YOUTUBE_PKG))

        // Rule #1: pre-clean via targeted controller.pause() (NOT media key which
        // would go to Spotify per field-log evidence). Only for YouTube — leaves
        // other apps alone.
        prepauseTarget(context, MediaSessions.YOUTUBE_PKG)

        val launched = fireYoutubeIntent(context, videoId, query, entry)
        if (!launched) return@withLock Result.NoTarget

        // Remember what we opened for rule #2 lookups.
        MediaSessionMemory.rememberOpenedApp(MediaSessions.YOUTUBE_PKG, videoId)

        scheduleTargetedNudge(context, MediaSessions.YOUTUBE_PKG, expectedTitle, priorTitle, entry)
        Result.Success
    }

    /**
     * "เล่นต่อ" — resume playback on target. If target has no session, refire
     * the deep link for [MediaSessionMemory.lastVideoId] rather than play() into
     * a random session.
     *
     * @param appHint app name the rider explicitly said ("youtube" / "spotify"),
     *   or null when the rider said "เล่นต่อ" alone.
     */
    suspend fun playContinue(
        context: Context, appHint: String?, entry: DebugEntry,
    ): Result = mutex.withLock {
        cancelPendingNudge()
        val target = resolveTarget(appHint) ?: MediaSessions.YOUTUBE_PKG
        logOp(entry, "playContinue", target)

        val ctrl = MediaSessions.controllerFor(context, target)
        if (ctrl != null) {
            entry.mediaCtrlUsed = true
            entry.playbackState = MediaSessions.stateName(ctrl.playbackState?.state)
            runCatching { ctrl.transportControls.play() }
                .onFailure { Log.w(TAG, "playContinue: transportControls.play() failed for $target", it) }
            return@withLock Result.CommandDispatched
        }

        // Target has no session. Rule #2: refire deep link, don't play() into thin air.
        return@withLock when (target) {
            MediaSessions.YOUTUBE_PKG -> {
                val lastVideo = MediaSessionMemory.lastVideoId()
                if (!lastVideo.isNullOrBlank()) {
                    logOp(entry, "playContinue→refireDeeplink", target)
                    val expectedTitle = MediaSessionMemory.currentTitle().ifBlank { null }
                    entry.mediaExpectedTitle = expectedTitle
                    entry.screenLocked = isScreenLocked(context)
                    fireYoutubeIntent(context, lastVideo, null, entry)
                    // priorTitle = null: we only reach here because YouTube had no active
                    // session, so there's no old video to guard against.
                    scheduleTargetedNudge(context, target, expectedTitle, priorTitle = null, entry = entry)
                    Result.Success
                } else {
                    Result.NoTarget
                }
            }
            else -> {
                // Non-YouTube target with no session and no known deep-link — best-effort
                // launch by package name (Play Store apps typically expose a main activity).
                logOp(entry, "playContinue→launchApp", target)
                val launched = launchAppByPackage(context, target)
                if (launched) Result.Success else Result.NoTarget
            }
        }
    }

    /**
     * "หยุด" — pause every active controller.
     * Rule #1: media key is only allowed on builds without notification-listener
     * permission. With permission, we pause each session by explicit target.
     */
    suspend fun pauseAll(context: Context, entry: DebugEntry): Result = mutex.withLock {
        cancelPendingNudge()
        val hasPerm = MediaSessions.hasPermission(context)
        val controllers = if (hasPerm) MediaSessions.activeControllers(context) else emptyList()
        val targets = controllers.filter { StopSequenceTargets.shouldPauseAtState(it.playbackState?.state) }

        if (targets.isNotEmpty()) {
            entry.mediaCtrlUsed = true
            entry.playbackState = targets.joinToString(",") {
                "${it.packageName.substringAfterLast('.')}=${MediaSessions.stateName(it.playbackState?.state)}"
            }
            targets.forEach { ctrl ->
                logOp(entry, "pauseTarget", ctrl.packageName)
                runCatching { ctrl.transportControls.pause() }
                    .onFailure { Log.w(TAG, "pauseAll: pause() failed for ${ctrl.packageName}", it) }
            }
            return@withLock Result.CommandDispatched
        }

        // Rule #1: no controllers OR no permission → media key ONLY when no permission.
        // With permission, no controllers means the system genuinely has nothing to
        // pause — dispatching MEDIA_PAUSE would wake a random app (the bug we fixed).
        if (!hasPerm) {
            logOp(entry, "pauseAll→mediaKey", "unknown")
            MediaStopper.dispatchExternalPauseOnly(context)
            return@withLock Result.MediaKeyFallback("MEDIA_PAUSE")
        }
        logOp(entry, "pauseAll→noop", null)
        Result.NoTarget
    }

    /**
     * Seek by [deltaSeconds] on target's controller. Combines v1.3.11 seek-controller
     * path + v1.3.12 auto-resume + v1.3.17 metadata verification into one op.
     */
    suspend fun seek(
        context: Context, deltaSeconds: Int, appHint: String?, entry: DebugEntry,
    ): Result = mutex.withLock {
        val target = resolveTarget(appHint) ?: MediaSessionMemory.lastOpenedApp()
        logOp(entry, "seek:${deltaSeconds}s", target ?: "unknown")

        val ctrl = target?.let { MediaSessions.controllerFor(context, it) }
        if (ctrl != null) {
            val pos = ctrl.playbackState?.position ?: 0L
            val newPos = (pos + deltaSeconds * 1000L).coerceAtLeast(0L)
            entry.mediaCtrlUsed = true
            entry.playbackState = MediaSessions.stateName(ctrl.playbackState?.state)
            runCatching { ctrl.transportControls.seekTo(newPos) }
            // v1.3.12 auto-resume — seek doesn't imply play, so kick play() after.
            runCatching { ctrl.transportControls.play() }
            return@withLock Result.CommandDispatched
        }

        // No target session. Rule #1: media key ONLY when no permission.
        if (!MediaSessions.hasPermission(context)) {
            logOp(entry, "seek→mediaKey", "unknown")
            MediaStopper.dispatchMediaSeek(context, forward = deltaSeconds >= 0)
            delay(200L)
            MediaStopper.dispatchMediaPlay(context)
            return@withLock Result.MediaKeyFallback(
                if (deltaSeconds >= 0) "MEDIA_FAST_FORWARD" else "MEDIA_REWIND"
            )
        }
        Result.NoTarget
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Rule #2 target resolution:
     *   * explicit rider hint wins,
     *   * otherwise [MediaSessionMemory.lastOpenedApp],
     *   * otherwise null → caller decides fallback.
     *
     * `internal` so JVM tests can exercise the mapping without needing a live
     * [android.content.Context] or [MediaSession]. This is pure logic — the only
     * side effect is a read of [MediaSessionMemory.lastOpenedApp].
     */
    internal fun resolveTarget(appHint: String?): String? {
        appHint?.let { hint ->
            val normalized = hint.lowercase().trim()
            return when {
                normalized.contains("youtube") || normalized.contains("ยูทูป") || normalized.contains("ยูทูบ") ->
                    MediaSessions.YOUTUBE_PKG
                normalized.contains("spotify") || normalized.contains("สปอติฟาย") ->
                    SPOTIFY_PKG
                else -> null
            }
        }
        return MediaSessionMemory.lastOpenedApp()
    }

    /** Pause the [targetPkg] session directly. No-op if the controller doesn't exist. */
    private suspend fun prepauseTarget(context: Context, targetPkg: String) {
        val ctrl = MediaSessions.controllerFor(context, targetPkg) ?: return
        val state = ctrl.playbackState?.state ?: return
        val shouldPause = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
        if (!shouldPause) return
        Log.d(TAG, "prepauseTarget: pausing $targetPkg (state=${MediaSessions.stateName(state)})")
        runCatching { ctrl.transportControls.pause() }
        delay(300L)
    }

    private fun fireYoutubeIntent(
        context: Context, videoId: String?, query: String?, entry: DebugEntry,
    ): Boolean {
        fun view(uri: Uri) = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pm = context.packageManager
        if (videoId != null) {
            val app = view(Uri.parse("vnd.youtube:$videoId"))
            val web = view(Uri.parse("https://www.youtube.com/watch?v=$videoId"))
            val target = if (app.resolveActivity(pm) != null) app else web
            return runCatching { context.startActivity(target) }.isSuccess.also {
                if (!it) entry.error = ((entry.error ?: "") + " youtube_launch_failed").trim()
            }
        }
        val q = query?.takeIf { it.isNotBlank() } ?: return false
        val search = Intent(Intent.ACTION_SEARCH).apply {
            setPackage(MediaSessions.YOUTUBE_PKG)
            putExtra("query", q)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val target = if (search.resolveActivity(pm) != null) search
        else view(Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(q)}"))
        return runCatching { context.startActivity(target) }.isSuccess.also {
            if (!it) entry.error = ((entry.error ?: "") + " youtube_search_failed").trim()
        }
    }

    private fun launchAppByPackage(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /**
     * Schedule an active nudge that polls [targetPkg]'s controller and calls play()
     * on the target directly — never on other apps' sessions. If no controller
     * ever appears, mark [DebugEntry.launchBlocked] and speak the honest TTS.
     */
    private fun scheduleTargetedNudge(
        context: Context, targetPkg: String,
        expectedTitle: String?, priorTitle: String?, entry: DebugEntry,
    ) {
        val appCtx = context.applicationContext
        val pollStartAt = System.currentTimeMillis() + POLL_INITIAL_DELAY_MS
        val pollWindowEndAt = pollStartAt + POLL_WINDOW_MS
        var playAttempts = 0
        var lastPlayAttemptAt = 0L
        lateinit var poll: Runnable
        poll = Runnable {
            val ctrl = MediaSessions.controllerFor(appCtx, targetPkg)
            if (ctrl == null) {
                // Rule #1: NO media-key fallback here. If YouTube's session isn't there,
                // dispatching MEDIA_PLAY would wake Spotify (per field log 1784028862496).
                // If the poll window has elapsed with no controller, mark launch_blocked.
                if (System.currentTimeMillis() >= pollWindowEndAt) {
                    declareLaunchBlocked(appCtx, targetPkg, entry, reason = "noSession")
                    return@Runnable
                }
                handler.postDelayed(poll, POLL_INTERVAL_MS)
                return@Runnable
            }
            val state = ctrl.playbackState?.state
            entry.playbackState = MediaSessions.stateName(state)
            entry.mediaCtrlUsed = true

            // v1.3.21 — verify by TITLE, not mediaId (YouTube leaves mediaId blank). The
            // decisive signal is whether the title moved away from what was playing before
            // we fired the intent. STILL_PRIOR after the window = the switch was blocked.
            val currentTitle = sessionTitle(ctrl)
            entry.mediaActualTitle = currentTitle
            val verdict = YoutubeVerify.classify(currentTitle, priorTitle, expectedTitle)
            val windowExhausted = System.currentTimeMillis() >= pollWindowEndAt

            if (verdict == YoutubeVerify.Verdict.STILL_PRIOR) {
                // The session is still showing the OLD video — the requested switch did not
                // land. Do NOT play() it (that would resume the wrong video and fake success).
                // Give it until the window ends in case the new video is still loading; then
                // speak the honest "can't open while locked" instead.
                if (windowExhausted) {
                    declareLaunchBlocked(appCtx, targetPkg, entry, reason = "stillPrior")
                    return@Runnable
                }
                handler.postDelayed(poll, POLL_INTERVAL_MS)
                return@Runnable
            }

            if (state == PlaybackState.STATE_PLAYING) {
                // CONFIRMED_TARGET or SWITCHED (title changed to a new video) → genuine success.
                // UNKNOWN (no title yet) but playing: keep waiting for the title, then accept
                // at the window edge rather than false-block something that IS playing.
                if (verdict != YoutubeVerify.Verdict.UNKNOWN || windowExhausted) {
                    logOp(entry, "nudge→confirmed", targetPkg)
                    Log.d(TAG, "nudge: $targetPkg playing, verdict=$verdict title=\"$currentTitle\" — confirmed")
                    if (speakPlayConfirmed && audioIsSettled(appCtx)) {
                        speakOutOfPipeline(appCtx, ErrorSpeech.MEDIA_PLAY_CONFIRMED)
                    }
                    pendingNudge = null
                    return@Runnable
                }
                handler.postDelayed(poll, POLL_INTERVAL_MS)
                return@Runnable
            }
            when (NudgeDecider.decide(
                state = state,
                nowMs = System.currentTimeMillis(),
                pollStartAt = pollStartAt,
                pollWindowEndAt = pollWindowEndAt,
                playAttempts = playAttempts,
                lastPlayAttemptAt = lastPlayAttemptAt,
                maxAttempts = NUDGE_MAX_ATTEMPTS,
                settleMs = NUDGE_SETTLE_MS,
                retrySpacingMs = NUDGE_RETRY_SPACING_MS,
            )) {
                NudgeDecider.Action.PlayNow -> {
                    playAttempts++
                    lastPlayAttemptAt = System.currentTimeMillis()
                    entry.youtubeNudged = true
                    logOp(entry, "nudge→play#$playAttempts", targetPkg)
                    Log.w(TAG, "nudge: state=${MediaSessions.stateName(state)} — controller.play() attempt $playAttempts/$NUDGE_MAX_ATTEMPTS on $targetPkg")
                    runCatching { ctrl.transportControls.play() }
                    handler.postDelayed(poll, POLL_INTERVAL_MS)
                }
                NudgeDecider.Action.GiveUp -> {
                    logOp(entry, "nudge→giveUp", targetPkg)
                    Log.w(TAG, "nudge: still not playing after ${POLL_WINDOW_MS}ms and $playAttempts play attempts on $targetPkg")
                    speakOutOfPipeline(appCtx, ErrorSpeech.MEDIA_OPENED_NOT_PLAYING)
                    pendingNudge = null
                }
                NudgeDecider.Action.KeepPolling -> {
                    handler.postDelayed(poll, POLL_INTERVAL_MS)
                }
            }
        }
        pendingNudge = poll
        handler.postDelayed(poll, POLL_INITIAL_DELAY_MS)
    }

    /**
     * v1.3.21 — the title the target session reports, our only reliable verification
     * signal (YouTube leaves METADATA_KEY_MEDIA_ID blank). Null when there is no
     * controller / no metadata / blank title.
     */
    private fun sessionTitle(controller: android.media.session.MediaController?): String? {
        val md = controller?.metadata ?: return null
        return md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
    }

    /** KeyguardManager.isKeyguardLocked, or null when the service is unavailable. */
    private fun isScreenLocked(context: Context): Boolean? {
        val km = context.getSystemService(android.app.KeyguardManager::class.java) ?: return null
        return runCatching { km.isKeyguardLocked }.getOrNull()
    }

    /**
     * Mark the interaction launch-blocked, speak the honest error, stop polling.
     * Two ways in: the target session never appeared at all (`noSession`), or it
     * appeared but kept playing the previous video (`stillPrior`, the locked-switch case
     * from field log 1784074856214). Either way: never silent, never a wrong-app nudge.
     */
    private fun declareLaunchBlocked(
        appCtx: Context, targetPkg: String, entry: DebugEntry, reason: String,
    ) {
        val available = MediaSessions.activePackagesForDebug(appCtx)
        entry.mediaCtrlPkgMiss = if (available.isEmpty()) "none" else available.joinToString(",")
        entry.launchBlocked = true
        // Overwrite whatever finishReason the pipeline set — LAUNCH_BLOCKED is more
        // informative than "ok". Field-log exports key off finishReason to categorize.
        entry.finishReason = FinishReason.LAUNCH_BLOCKED
        logOp(entry, "nudge→launchBlocked($reason)", targetPkg)
        Log.w(TAG, "nudge: $targetPkg launch blocked ($reason). available=$available")
        speakOutOfPipeline(appCtx, ErrorSpeech.LAUNCH_BLOCKED_LOCKED)
        pendingNudge = null
    }

    private fun cancelPendingNudge() {
        pendingNudge?.let { handler.removeCallbacks(it) }
        pendingNudge = null
    }

    /**
     * Rule #3 — append this op + target to [DebugEntry.mediaOperations] so a field
     * log can reconstruct the sequence of media calls without ambiguity.
     */
    private fun logOp(entry: DebugEntry, op: String, target: String?) {
        entry.mediaTargetPkg = target
        val existing = entry.mediaOperations.orEmpty()
        val fragment = if (target != null) "$op[$target]" else op
        entry.mediaOperations = if (existing.isEmpty()) fragment else "$existing;$fragment"
    }

    /**
     * Spawn a short-lived [ThaiTTS] to speak a line AFTER the pipeline's own scope
     * is dead. Used by the nudge polling task which runs via [Handler] post-finish.
     */
    private fun speakOutOfPipeline(appCtx: Context, text: String) {
        val tts = ThaiTTS(appCtx)
        tts.speak(text) { runCatching { tts.stop() } }
    }

    /**
     * v1.3.11 approval note — never speak MEDIA_PLAY_CONFIRMED while the audio route
     * is mid-switch (SCO tearing down, A2DP taking over). MODE_NORMAL is the
     * "settled" signal — if we're still in a call/comm mode, TTS would collide with
     * the routing transition and either duck the media start or come out on the
     * wrong device.
     */
    private fun audioIsSettled(appCtx: Context): Boolean {
        val am = appCtx.getSystemService(AudioManager::class.java) ?: return true
        return am.mode == AudioManager.MODE_NORMAL
    }

    // ─── Constants used across the app ────────────────────────────────────────

    /** Spotify Android package. Referenced by webhook `action=spotify_play` (v1.3.20+). */
    const val SPOTIFY_PKG = "com.spotify.music"

    /** Test hook — clears the pending-nudge handler between tests. */
    internal fun resetForTest() {
        cancelPendingNudge()
    }
}
