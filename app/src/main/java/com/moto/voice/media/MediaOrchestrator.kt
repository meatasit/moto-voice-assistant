package com.moto.voice.media

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.moto.voice.actions.MediaStopper
import com.moto.voice.data.NetworkState
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
    /**
     * v1.3.36 — a COLD target (nothing playing when we fired, so no prior session) needs
     * longer than a warm switch. Field logs 1786072158662 (entry 1786066108846) and
     * 1786104958601 (entries 1786100870242 / 1786100948949) share one shape:
     * `fsiTrampolineLaunchOk=true`, `mediaCtrlPkgMiss=none` — the launch fired, nothing
     * else held focus, YouTube simply had not registered a session inside 9.8s, and the
     * rider was told the open failed when it was only slow. Warm switches keep the tighter
     * window so a genuine failure is still reported promptly.
     */
    private const val POLL_WINDOW_COLD_MS = 15_000L
    /**
     * v1.3.26 (fix 1) — if the session is still on the PRIOR video this long after the poll
     * started, re-fire the deep link ONCE. A locked YouTube→YouTube switch often ignores the
     * first delivery (YouTube already the running task); the rider's manual retry lands it,
     * so we automate one retry.
     *
     * v1.3.36 — field log 1786104958601 disproved the "send the same link again" half of
     * that fix: ELEVEN consecutive locked switches ended `launchBlocked(stillPrior)`, every
     * one of them AFTER a `refireSwitch` — 22 deliveries of a `vnd.youtube:` VIEW intent,
     * none of which navigated. `mediaActualTitle` sat on the same "crazy chill song
     * playlist" for 12 minutes while the rider kept asking for other things. Re-sending an
     * identical intent to a task that is already running IS the no-op, so the re-fire now
     * escalates instead of repeating — see [fireYoutubeIntent]'s `forceRestart`.
     */
    private const val REFIRE_STILL_PRIOR_MS = 2_500L
    private const val NUDGE_SETTLE_MS = 2_000L
    private const val NUDGE_MAX_ATTEMPTS = 3
    private const val NUDGE_RETRY_SPACING_MS = 1_500L

    /**
     * How long to wait for the target's session before declaring the launch blocked.
     * Pure so a JVM test can lock the cold/warm split without a Handler.
     *
     * @param priorTitle what the target was playing when we fired — null means nothing was
     *   playing, i.e. the app is cold and has a whole startup ahead of it.
     */
    internal fun pollWindowMsFor(priorTitle: String?): Long =
        if (priorTitle == null) POLL_WINDOW_COLD_MS else POLL_WINDOW_MS

    private val mutex = Mutex()
    // `lazy` so pure-JVM tests (which never schedule a nudge) don't trip on
    // Handler(Looper.getMainLooper()) — that call throws in a non-instrumented
    // test because android.os.Looper isn't mocked.
    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var pendingNudge: Runnable? = null
    /** v1.4.4 — the entry the pending nudge writes into, so a cancel can be logged there. */
    @Volatile private var pendingEntry: DebugEntry? = null

    /**
     * v1.3.25 diagnostic breadcrumb for the locked-screen FSI path. [LockLaunchActivity]
     * (the trampoline) runs asynchronously after [openYoutube] returns and has no handle
     * on the pipeline's [DebugEntry], so it can't log directly. It stamps this instead;
     * the nudge reads it via [stampTrampoline] when the poll resolves. Ops are serialized
     * by [mutex] and stale nudges cancelled, so only one launch is ever in flight.
     */
    private class TrampolineCrumb {
        @Volatile var ran: Boolean = false
        @Volatile var launchOk: Boolean = false
    }
    @Volatile private var trampolineCrumb: TrampolineCrumb? = null

    /**
     * Called by [LockLaunchActivity] the moment it runs, reporting whether it managed to
     * startActivity the real YouTube deep link. Lets the next field log tell "FSI demoted,
     * trampoline never ran" (ran stays false) apart from "trampoline ran but the deep link
     * was BAL-dropped" (ran=true, launchOk=false).
     */
    fun onTrampolineResult(launchOk: Boolean) {
        trampolineCrumb?.let {
            it.ran = true
            it.launchOk = launchOk
        }
    }

    /** Copy the FSI trampoline breadcrumb onto [entry] as the nudge resolves. No-op off the FSI path. */
    private fun stampTrampoline(entry: DebugEntry) {
        val crumb = trampolineCrumb ?: return
        entry.fsiTrampolineRan = crumb.ran
        entry.fsiTrampolineLaunchOk = if (crumb.ran) crumb.launchOk else false
    }

    /**
     * v1.3.42 — whether to open videos with the https App Link rather than `vnd.youtube:`.
     * Set from [com.moto.voice.data.AppSettings.youtubeWebLink] by the pipeline before each
     * op, same way [speakPlayConfirmed] works. Default true.
     */
    @Volatile var webLinkPreferred: Boolean = true

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
        /**
         * v1.4.4 — the deep link could not even be fired (no resolvable intent,
         * startActivity threw, or the lock-screen notification could not be posted).
         * Nothing is polling, so the CALLER must speak — the review found both openYoutube
         * call sites discarding the Result, leaving the rider with "กำลังเปิด…" and silence.
         */
        object LaunchFailed : Result()
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
        // v1.4.1 — diagnostics for the cold-launch failures (see DebugEntry.mediaPriorTitle).
        entry.mediaPriorTitle = priorTitle
        entry.netTransport = runCatching { NetworkState.transportName(context) }.getOrNull()
        // v1.4.2 — see DebugEntry.keyguardSecure / mediaBrowserAvail.
        entry.keyguardSecure = runCatching {
            context.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure
        }.getOrNull()
        entry.screenInteractive = runCatching {
            context.getSystemService(android.os.PowerManager::class.java)?.isInteractive
        }.getOrNull()
        entry.mediaBrowserAvail = runCatching { probeMediaBrowsers(context) }.getOrNull()

        // Rule #1: pre-clean via targeted controller.pause() (NOT media key which
        // would go to Spotify per field-log evidence). Only for YouTube — leaves
        // other apps alone.
        //
        // v1.3.26 (fix 2) — but NOT when the screen is locked. Field log 1784203179701
        // showed locked YouTube→YouTube switches frequently fail to navigate (stillPrior);
        // having paused the current video A first left the rider in DEAD SILENCE. The
        // title-based verify (not isMusicActive) no longer needs a paused baseline, and
        // classify() checks the prior title first so a still-playing A can never
        // false-confirm. Leaving A playing means a failed switch degrades to "old video
        // keeps playing", not silence.
        if (entry.screenLocked != true) prepauseTarget(context, MediaSessions.YOUTUBE_PKG)

        // v1.3.25 — free audio focus before launching YouTube. Field log 1784173407858
        // (morning session, after a BT reconnect) failed EVERY youtube_play with
        // mediaCtrlPkgMiss=com.spotify.music: the helmet auto-resumed Spotify, and with
        // Spotify holding focus YouTube never registered a session (noSession). Pause any
        // foreign player explicitly (Rule #1: targeted controller, never a media key).
        prepauseForeignPlayers(context, entry)

        val launched = fireYoutubeIntent(context, videoId, query, entry)
        if (!launched) {
            // v1.4.4 — never silent (Rule #3). Stamp the entry like a blocked launch so the
            // field log categorizes it, and hand the caller a Result it cannot mistake for
            // "the nudge will speak for me".
            entry.launchBlocked = true
            entry.finishReason = FinishReason.LAUNCH_BLOCKED
            logOp(entry, "openYoutube→launchFailed", MediaSessions.YOUTUBE_PKG)
            return@withLock Result.LaunchFailed
        }

        // Remember what we opened for rule #2 lookups.
        MediaSessionMemory.rememberOpenedApp(MediaSessions.YOUTUBE_PKG, videoId)

        scheduleTargetedNudge(
            context, MediaSessions.YOUTUBE_PKG, expectedTitle, priorTitle, entry,
            videoId = videoId, query = query,
        )
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
                    // v1.4.4 — verify against the FULL title. currentTitle() is the 60-char
                    // speech title and failed the 70% rule on long titles (review finding).
                    val expectedTitle = MediaSessionMemory.currentVerifyTitle().ifBlank { null }
                    entry.mediaExpectedTitle = expectedTitle
                    entry.screenLocked = isScreenLocked(context)
                    // v1.3.25 — same focus-steal guard as openYoutube: we only reach here
                    // because YouTube had no session, so a foreign player (Spotify auto-resumed
                    // on BT reconnect) may be holding audio focus. Pause it before refiring.
                    prepauseForeignPlayers(context, entry)
                    if (!fireYoutubeIntent(context, lastVideo, null, entry)) {
                        entry.launchBlocked = true
                        entry.finishReason = FinishReason.LAUNCH_BLOCKED
                        logOp(entry, "playContinue→launchFailed", target)
                        return@withLock Result.LaunchFailed
                    }
                    // priorTitle = null: we only reach here because YouTube had no active
                    // session, so there's no old video to guard against.
                    scheduleTargetedNudge(
                        context, target, expectedTitle, priorTitle = null, entry = entry,
                        videoId = lastVideo, query = null,
                    )
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
        // v1.4.4 — seek was the one op that skipped this; an in-flight warm-switch nudge
        // survived the seek and its CLEAR_TASK re-fire then restarted YouTube on top of the
        // position the rider had just been told was set (review finding).
        cancelPendingNudge()
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

    /**
     * v1.3.25 — pause every OTHER app that is actively PLAYING/BUFFERING before we
     * launch YouTube, so YouTube can take audio focus. The rider's helmet auto-resumes
     * Spotify on every BT reconnect (field log 1784173407858), and a foreign player
     * holding focus is the leading suspect for YouTube's session never appearing.
     * Rule #1: each pause targets an explicit controller — never a media key. Records
     * what we paused into [DebugEntry.mediaForeignPaused] to confirm the hypothesis in
     * the next field log.
     */
    private suspend fun prepauseForeignPlayers(context: Context, entry: DebugEntry) {
        val foreign = foreignActivePlayers(context)
        if (foreign.isEmpty()) return
        entry.mediaForeignPaused = foreign.joinToString(",") {
            "${it.packageName}=${MediaSessions.stateName(it.playbackState?.state)}"
        }
        foreign.forEach { ctrl ->
            logOp(entry, "prepauseForeign", ctrl.packageName)
            Log.d(TAG, "prepauseForeignPlayers: pausing ${ctrl.packageName} to free focus for YouTube")
            runCatching { ctrl.transportControls.pause() }
                .onFailure { Log.w(TAG, "prepauseForeignPlayers: pause() failed for ${ctrl.packageName}", it) }
        }
        delay(300L)
    }

    /**
     * Non-YouTube sessions actively PLAYING/BUFFERING right now — the foreign players
     * that can hold audio focus away from YouTube. Shared by the launch-instant
     * [prepauseForeignPlayers] and the per-tick re-pause inside [scheduleTargetedNudge].
     */
    private fun foreignActivePlayers(
        context: Context,
    ): List<android.media.session.MediaController> =
        MediaSessions.activeControllers(context).filter {
            isForeignPackage(it.packageName, context.packageName) &&
                (it.playbackState?.state == PlaybackState.STATE_PLAYING ||
                    it.playbackState?.state == PlaybackState.STATE_BUFFERING)
        }

    /**
     * v1.4.4 — a "foreign" player is neither the target (YouTube) nor US. Our own
     * FmPlayerService registers a media3 session under the app's package; without this
     * check the per-tick re-pause killed the radio every 500 ms while a YouTube nudge was
     * still polling (review finding).
     */
    internal fun isForeignPackage(pkg: String?, selfPkg: String?): Boolean =
        pkg != null && pkg != MediaSessions.YOUTUBE_PKG && pkg != selfPkg

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

    /**
     * Build the best YouTube Intent for [videoId] or [query] (app deep link → web
     * fallback; search intent → web results). Returns null when there's nothing to open.
     * Split out of [fireYoutubeIntent] so both the direct-launch and over-lock-screen
     * paths choose the exact same target.
     */
    /**
     * v1.4.2 — does [pkg] declare a MediaBrowserService? Needs the manifest `<queries>`.
     * "absent" when the package is not installed at all.
     */
    private fun probeMediaBrowsers(context: Context): String {
        val pm = context.packageManager
        fun probe(pkg: String): String {
            val installed = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
            if (!installed) return "absent"
            val svc = Intent("android.media.browse.MediaBrowserService").setPackage(pkg)
            return runCatching { pm.queryIntentServices(svc, 0).isNotEmpty() }.getOrDefault(false).toString()
        }
        return "yt=${probe(MediaSessions.YOUTUBE_PKG)},ytm=${probe(YOUTUBE_MUSIC_PKG)}"
    }

    private const val YOUTUBE_MUSIC_PKG = "com.google.android.apps.youtube.music"

    private fun buildYoutubeIntent(
        context: Context, videoId: String?, query: String?, forceRestart: Boolean = false,
        entry: DebugEntry? = null,
    ): Intent? {
        // v1.3.36 — CLEAR_TASK (only legal alongside NEW_TASK) tears down YouTube's existing
        // task and starts it fresh at the requested video. That is the difference between the
        // re-fire and the first attempt: a plain NEW_TASK delivered to a task that is already
        // running just brings that task forward — Android does not deliver the new intent, so
        // the deep link is dropped on the floor while startActivity still returns normally.
        // That is exactly the 11-in-a-row stillPrior signature in field log 1786104958601.
        val restartFlag = if (forceRestart) Intent.FLAG_ACTIVITY_CLEAR_TASK else 0
        fun view(uri: Uri) =
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or restartFlag)
        val pm = context.packageManager
        if (videoId != null) {
            // v1.3.42 — prefer the https App Link over the vnd.youtube: custom scheme; see
            // AppSettings.youtubeWebLink for the evidence. Package-targeted so no chooser can
            // appear, and we only use it if YouTube actually claims it — otherwise this falls
            // through to exactly what shipped before.
            // v1.4.2 — log WHICH form fires. Until the manifest <queries> block landed in this
            // same version, every resolveActivity() here most likely answered null on the
            // rider's Android 14 phone, i.e. the untargeted https fallback was what ran.
            if (webLinkPreferred) {
                val web = view(Uri.parse("https://www.youtube.com/watch?v=$videoId"))
                    .setPackage(MediaSessions.YOUTUBE_PKG)
                if (web.resolveActivity(pm) != null) {
                    entry?.let { logOp(it, "link→webTargeted", MediaSessions.YOUTUBE_PKG) }
                    return web
                }
            }
            val app = view(Uri.parse("vnd.youtube:$videoId"))
            val web = view(Uri.parse("https://www.youtube.com/watch?v=$videoId"))
            return if (app.resolveActivity(pm) != null) {
                entry?.let { logOp(it, "link→vnd", MediaSessions.YOUTUBE_PKG) }
                app
            } else {
                entry?.let { logOp(it, "link→webUntargeted", MediaSessions.YOUTUBE_PKG) }
                web
            }
        }
        val q = query?.takeIf { it.isNotBlank() } ?: return null
        val search = Intent(Intent.ACTION_SEARCH).apply {
            setPackage(MediaSessions.YOUTUBE_PKG)
            putExtra("query", q)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or restartFlag)
        }
        return if (search.resolveActivity(pm) != null) {
            entry?.let { logOp(it, "link→search", MediaSessions.YOUTUBE_PKG) }
            search
        } else {
            entry?.let { logOp(it, "link→searchWeb", MediaSessions.YOUTUBE_PKG) }
            view(Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(q)}"))
        }
    }

    /**
     * Fire the YouTube launch. When the screen is LOCKED and we hold USE_FULL_SCREEN_INTENT,
     * route through [LockScreenLauncher] so the deep link isn't silently BAL-dropped (field
     * logs 1784078976959 / 1784082476746). Otherwise start it directly.
     */
    private fun fireYoutubeIntent(
        context: Context, videoId: String?, query: String?, entry: DebugEntry,
        forceRestart: Boolean = false,
    ): Boolean {
        val target = buildYoutubeIntent(context, videoId, query, forceRestart, entry) ?: run {
            entry.error = ((entry.error ?: "") + " youtube_launch_failed").trim()
            return false
        }
        val locked = isScreenLocked(context) == true
        if (locked && LockScreenLauncher.canUseFullScreenIntent(context)) {
            logOp(entry, "launch→fullScreenIntent", MediaSessions.YOUTUBE_PKG)
            // v1.3.25 diagnostic — arm a fresh breadcrumb the trampoline will stamp when
            // (if) the OS honors the FSI. Reset BEFORE posting so a demoted FSI leaves
            // ran=false, which the nudge reads as "trampoline never launched".
            trampolineCrumb = TrampolineCrumb()
            val posted = LockScreenLauncher.launchOverLockScreen(context, target)
            if (!posted) entry.error = ((entry.error ?: "") + " fsi_notify_failed").trim()
            return posted
        }
        if (locked) {
            // Locked but no full-screen-intent permission → the direct start will likely be
            // BAL-dropped; mark it so the log + rider know to grant the permission.
            logOp(entry, "launch→startActivity(lockedNoFsiPerm)", MediaSessions.YOUTUBE_PKG)
            entry.error = ((entry.error ?: "") + " no_fsi_permission").trim()
        }
        return runCatching { context.startActivity(target) }.isSuccess.also {
            if (!it) entry.error = ((entry.error ?: "") + " youtube_launch_failed").trim()
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
        videoId: String?, query: String?,
    ) {
        val appCtx = context.applicationContext
        // v1.4.4 — uptimeMillis, the clock Handler.postDelayed and NudgeDecider run on. The
        // wall clock kept ticking through a deep sleep between two 500 ms ticks and expired
        // the window with almost no CPU time spent (review finding).
        val pollStartAt = SystemClock.uptimeMillis() + POLL_INITIAL_DELAY_MS
        // var: a CLEAR_TASK re-fire restarts YouTube from scratch and gets the window
        // extended below — v1.3.36 shipped the escalation but kept judging it on the warm
        // clock, so it was declared failed while it was still working.
        var pollWindowEndAt = pollStartAt + pollWindowMsFor(priorTitle)
        val refireAt = pollStartAt + REFIRE_STILL_PRIOR_MS
        var playAttempts = 0
        var lastPlayAttemptAt = 0L
        var refiredSwitch = false
        // v1.4.2 — did the target EVER show a session during this window? Field log
        // 1789518388540 (entry 1789518322022): the poll saw YouTube PLAYING the right video
        // mid-window (mediaActualTitle / playbackState were stamped), then the session was
        // gone by window end and the rider heard "เปิดไม่สำเร็จ" — for a video that HAD
        // started. "It started and then stopped" is a different fact from "it never
        // appeared", and it points at the keyguard (YouTube pauses when its player is not
        // visible), so it gets its own reason and its own line.
        var sawSession = false
        // v1.4.4 — sawSession fires on ANY controller, which on a warm switch is the PRIOR
        // video's session, so "sessionLost" was reachable for switches that never landed and
        // the rider was told "it opened and then stopped" about a video that never opened
        // (review finding). sessionLost now needs a session that was demonstrably not the
        // old one — see [countsAsNewSession]. sawSession stays for the sessionSeen op log.
        var sawNewSession = false
        // v1.3.31 — packages we've already logged a mid-window re-pause for (log once each).
        val foreignRepaused = mutableSetOf<String>()
        lateinit var poll: Runnable
        poll = Runnable {
            // v1.3.31 — the launch-instant prepause is a single snapshot. Field log
            // 1784768501667 proved Spotify auto-resumes AFTER that snapshot on a BT
            // reconnect (the morning noSession cluster) so it was never caught —
            // prepauseForeign never fired, yet Spotify held a session at block time and
            // YouTube never registered one. Re-check every tick and pause any foreign
            // player that (re)started so YouTube can take focus. Rule #1: targeted
            // controller.pause(), never a media key. Log once per pkg; keep pausing while
            // it keeps resuming. The 9s window bounds the pause-war; on window end the
            // ctrl==null branch still declares noSession honestly.
            for (fc in foreignActivePlayers(appCtx)) {
                val pkg = fc.packageName
                if (foreignRepaused.add(pkg)) {
                    logOp(entry, "repauseForeign", pkg)
                    entry.mediaForeignPaused =
                        (entry.mediaForeignPaused?.let { "$it," } ?: "") +
                            "$pkg=${MediaSessions.stateName(fc.playbackState?.state)}"
                    Log.w(TAG, "nudge: foreign $pkg resumed mid-window — re-pausing to free focus for YouTube")
                }
                runCatching { fc.transportControls.pause() }
            }
            val ctrl = MediaSessions.controllerFor(appCtx, targetPkg)
            if (ctrl == null) {
                // Rule #1: NO media-key fallback here. If YouTube's session isn't there,
                // dispatching MEDIA_PLAY would wake Spotify (per field log 1784028862496).
                // If the poll window has elapsed with no controller, mark launch_blocked.
                if (SystemClock.uptimeMillis() >= pollWindowEndAt) {
                    declareLaunchBlocked(
                        appCtx, targetPkg, entry,
                        reason = if (sawNewSession) "sessionLost" else "noSession",
                    )
                    return@Runnable
                }
                handler.postDelayed(poll, POLL_INTERVAL_MS)
                return@Runnable
            }
            val state = ctrl.playbackState?.state
            entry.playbackState = MediaSessions.stateName(state)
            entry.mediaCtrlUsed = true
            if (!sawSession) {
                sawSession = true
                logOp(entry, "nudge→sessionSeen(${MediaSessions.stateName(state)})", targetPkg)
            }

            // v1.3.21 — verify by TITLE, not mediaId (YouTube leaves mediaId blank). The
            // decisive signal is whether the title moved away from what was playing before
            // we fired the intent. STILL_PRIOR after the window = the switch was blocked.
            val currentTitle = sessionTitle(ctrl)
            entry.mediaActualTitle = currentTitle
            val verdict = YoutubeVerify.classify(currentTitle, priorTitle, expectedTitle)
            if (!sawNewSession && countsAsNewSession(verdict, priorTitle)) sawNewSession = true
            val windowExhausted = SystemClock.uptimeMillis() >= pollWindowEndAt

            if (verdict == YoutubeVerify.Verdict.STILL_PRIOR) {
                // The session is still showing the OLD video — the requested switch did not
                // land. Do NOT play() it (that would resume the wrong video and fake success).
                //
                // v1.3.26 (fix 1) — a locked YouTube→YouTube switch often doesn't navigate on
                // the FIRST deep-link delivery: YouTube is already the running task and a bare
                // vnd.youtube:VID (NEW_TASK) gets handed to it without a fresh navigation.
                // Field log 1784203179701 proved the rider's manual "say it again" lands the
                // switch — so re-fire the SAME deep link ONCE, mid-window, automating that.
                //
                // v1.3.36 — and the re-fire now ESCALATES. Repeating the same intent was
                // proven useless (11/11 stillPrior in field log 1786104958601, each after a
                // refireSwitch); this one adds CLEAR_TASK so YouTube's running task is torn
                // down and restarted at the requested video instead of merely being brought
                // forward. Logged under a distinct op name so the next field log says which
                // kind of re-fire ran.
                if (!refiredSwitch && SystemClock.uptimeMillis() >= refireAt &&
                    (videoId != null || query != null)
                ) {
                    refiredSwitch = true
                    logOp(entry, "nudge→refireSwitch(clearTask)", targetPkg)
                    Log.w(TAG, "nudge: $targetPkg still on prior video — re-firing deep link with CLEAR_TASK")
                    fireYoutubeIntent(appCtx, videoId, query, entry, forceRestart = true)
                    // v1.3.37 — CLEAR_TASK works, we were just judging it on the wrong clock.
                    // Field log 1786178611552: entry 1786164662718 declared stillPrior, and the
                    // NEXT interaction 57s later found YouTube playing -CXDKsZY80I — exactly the
                    // video that "failed". Tearing the task down and starting it again is a full
                    // cold start, so give it a cold start's worth of time from this moment.
                    pollWindowEndAt = SystemClock.uptimeMillis() + POLL_WINDOW_COLD_MS
                }
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
                val decision = playingDecision(
                    verdict = verdict,
                    expectedKnown = !expectedTitle.isNullOrBlank(),
                    windowExhausted = windowExhausted,
                )
                when (decision) {
                    PlayingDecision.Confirm -> {
                        stampTrampoline(entry)
                        logOp(entry, "nudge→confirmed", targetPkg)
                        Log.d(TAG, "nudge: $targetPkg playing, verdict=$verdict title=\"$currentTitle\" — confirmed")
                        if (speakPlayConfirmed && audioIsSettled(appCtx)) {
                            speakOutOfPipeline(appCtx, ErrorSpeech.MEDIA_PLAY_CONFIRMED)
                        }
                        pendingNudge = null
                        return@Runnable
                    }
                    PlayingDecision.WrongVideo -> {
                        // Playing, but not what was asked for — see [playingDecision].
                        Log.w(TAG, "nudge: $targetPkg playing the WRONG title: want=\"$expectedTitle\" got=\"$currentTitle\"")
                        declareLaunchBlocked(appCtx, targetPkg, entry, reason = "wrongVideo")
                        return@Runnable
                    }
                    PlayingDecision.KeepPolling -> {
                        handler.postDelayed(poll, POLL_INTERVAL_MS)
                        return@Runnable
                    }
                }
            }
            when (NudgeDecider.decide(
                state = state,
                nowMs = SystemClock.uptimeMillis(),
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
                    lastPlayAttemptAt = SystemClock.uptimeMillis()
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
        pendingEntry = entry
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
        stampTrampoline(entry)
        val available = MediaSessions.activePackagesForDebug(appCtx)
        entry.mediaCtrlPkgMiss = if (available.isEmpty()) "none" else available.joinToString(",")
        entry.launchBlocked = true
        // Overwrite whatever finishReason the pipeline set — LAUNCH_BLOCKED is more
        // informative than "ok". Field-log exports key off finishReason to categorize.
        entry.finishReason = FinishReason.LAUNCH_BLOCKED
        logOp(entry, "nudge→launchBlocked($reason)", targetPkg)
        Log.w(TAG, "nudge: $targetPkg launch blocked ($reason). available=$available")
        // v1.3.30 — split the honest line by WHY it blocked (field log 1784551582120):
        //   stillPrior → the app IS open and playing the old clip; the switch just didn't
        //                land in-window. Say the clip hasn't switched yet and to try again.
        //   noSession  → the app never registered a session. This is a lock-screen BAL block
        //                ONLY when the screen is actually locked. v1.3.34 (field log
        //                1786018272868, entry 1786016733912): screenLocked=false yet the rider
        //                heard "can't open while locked, unlock first" — a wrong instruction he
        //                could see was false (screen was unlocked; YouTube just never loaded the
        //                clip, likely weak signal). Re-check the LIVE lock state here — it's what
        //                is true when the rider hears the line — and never tell an unlocked rider
        //                to unlock.
        //
        // v1.3.36 — "ปลดล็อคก่อน" is only true advice when the keyguard actually stopped us.
        // Field log 1786104958601 (entries 1786100870242 / 1786100948949) has locked opens
        // where fsiTrampolineRan=true AND fsiTrampolineLaunchOk=true — the full-screen intent
        // was honored and YouTube was launched over the lock screen — yet the rider still
        // heard "เปิดไม่ได้ตอนจอล็อค ลองปลดล็อคก่อน", which he logged as a bug. Unlocking would
        // not have helped: the launch fired, the session was just slow to appear. Keep the
        // unlock advice for the case it describes (no FSI path taken) and otherwise use the
        // honest "didn't start, say it again" line.
        val fsiHonored = entry.fsiTrampolineRan == true && entry.fsiTrampolineLaunchOk == true
        val line = when (blockedLineFor(reason, isScreenLocked(appCtx) == true, fsiHonored)) {
            BlockedLine.SwitchNotLanded -> ErrorSpeech.SWITCH_NOT_LANDED
            BlockedLine.LockedNoFsi -> ErrorSpeech.LAUNCH_BLOCKED_LOCKED
            BlockedLine.NoSession -> ErrorSpeech.LAUNCH_FAILED_NO_SESSION
            BlockedLine.SessionLost -> ErrorSpeech.MEDIA_STOPPED_AFTER_OPEN
        }
        speakOutOfPipeline(appCtx, line)
        pendingNudge = null
    }

    /** What to do when the target is PLAYING but we still have to judge WHAT it's playing. */
    internal enum class PlayingDecision { Confirm, KeepPolling, WrongVideo }

    /**
     * v1.3.41 — "playing" is not the same as "playing what the rider asked for".
     *
     * Field log 1787294052224 caught the difference costing the rider three commands in a
     * row. He asked for เรื่องเล่าเช้านี้ (landed), then for instrumental music twice — both
     * declared `stillPrior` — then for กรรมกรข่าว, and got:
     *
     * ```
     * want: Live "กรรมกรข่าว คุยนอกจอ" 21 สิงหาคม 2569
     * got:  ดนตรีเพราะๆ บรรเลง เสียงธรรมชาติ ฟังเพลินๆ 1 ชม.      ← the music from two commands ago
     * ops:  …;nudge→confirmed
     * ```
     *
     * The CLEAR_TASK restart from the music command landed AFTER its window closed, so
     * YouTube switched to the music while we were verifying the news request. The old rule
     * confirmed on any verdict except UNKNOWN, and [YoutubeVerify.Verdict.SWITCHED] only
     * means "the title moved away from the prior one" — so a late arrival from a previous
     * command reads as success for the current one.
     *
     * SWITCHED is only trustworthy when there was nothing to check against ("อันต่อไป",
     * where [expectedTitle] is blank). With a known target, a title that matches neither the
     * target nor the prior one means something else is playing: keep polling in case ours is
     * still loading, and if the window runs out, say so instead of claiming success.
     */
    internal fun playingDecision(
        verdict: YoutubeVerify.Verdict,
        expectedKnown: Boolean,
        windowExhausted: Boolean,
    ): PlayingDecision = when {
        verdict == YoutubeVerify.Verdict.CONFIRMED_TARGET -> PlayingDecision.Confirm
        // No target to verify against — playing anything new is the best we can know.
        verdict == YoutubeVerify.Verdict.SWITCHED && !expectedKnown -> PlayingDecision.Confirm
        verdict == YoutubeVerify.Verdict.SWITCHED && windowExhausted -> PlayingDecision.WrongVideo
        verdict == YoutubeVerify.Verdict.SWITCHED -> PlayingDecision.KeepPolling
        // No title at all, but audio IS playing: accept at the edge rather than false-block.
        verdict == YoutubeVerify.Verdict.UNKNOWN && windowExhausted -> PlayingDecision.Confirm
        else -> PlayingDecision.KeepPolling
    }

    /**
     * v1.4.4 — does this poll tick prove the TARGET's own session exists, as opposed to the
     * session of whatever was playing before we fired? Cold launch (no prior): any session
     * is new. Warm switch: only a title that moved away from the prior one counts; a blank
     * title could still be the old session mid-transition.
     */
    internal fun countsAsNewSession(verdict: YoutubeVerify.Verdict, priorTitle: String?): Boolean =
        priorTitle == null ||
            verdict == YoutubeVerify.Verdict.CONFIRMED_TARGET ||
            verdict == YoutubeVerify.Verdict.SWITCHED

    /** Which honest line a blocked launch should speak. Named so a JVM test can lock it. */
    internal enum class BlockedLine { SwitchNotLanded, LockedNoFsi, NoSession, SessionLost }

    /**
     * Pure decision behind [declareLaunchBlocked]'s TTS. The rule that matters:
     * **never tell the rider to unlock when the full-screen-intent path was honored** —
     * unlocking would not have changed anything, and he can hear that it is wrong.
     */
    internal fun blockedLineFor(reason: String, locked: Boolean, fsiHonored: Boolean): BlockedLine =
        when {
            // v1.3.41 — "wrongVideo" is the same rider-facing situation as "stillPrior":
            // something IS audible, it just isn't what was asked for. "Can't open" would
            // contradict what he can hear.
            reason == "stillPrior" || reason == "wrongVideo" -> BlockedLine.SwitchNotLanded
            // v1.4.2 — it opened and then stopped: say that, not "couldn't open".
            reason == "sessionLost" -> BlockedLine.SessionLost
            locked && !fsiHonored -> BlockedLine.LockedNoFsi
            else -> BlockedLine.NoSession
        }

    /**
     * v1.4.4 — a new interaction supersedes an in-flight nudge. The poll lived up to ~16 s
     * after its own interaction and no non-media command cancelled it, so it could re-pause
     * our own radio, CLEAR_TASK-restart YouTube over a seek, or QUEUE_FLUSH a call
     * confirmation with "เปิดไม่สำเร็จ" (review findings). The pipeline calls this at the top
     * of every interaction; the cancel is logged into the superseded launch's own entry.
     */
    fun supersedePendingNudge(reason: String) {
        if (pendingNudge != null) Log.d(TAG, "pending nudge cancelled: $reason")
        cancelPendingNudge(reason)
    }

    private fun cancelPendingNudge(reason: String = "newOp") {
        pendingNudge?.let {
            handler.removeCallbacks(it)
            // Rule #3 — the superseded launch's entry records that nobody watched it to the
            // end, so a missing nudge→confirmed / launchBlocked is explained, not a mystery.
            pendingEntry?.let { e -> logOp(e, "nudge→cancelled($reason)", MediaSessions.YOUTUBE_PKG) }
        }
        pendingNudge = null
        pendingEntry = null
        // Drop any stale FSI breadcrumb so a later non-FSI (unlocked) open can't be
        // stamped with a previous locked launch's trampoline result. Every op calls
        // this at its top; the FSI branch re-arms a fresh crumb when it fires.
        trampolineCrumb = null
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
        // v1.3.36 — speakUnlogged: this line belongs to the nudge, not to the interaction
        // whose DebugEntry is still at the head of the log. Stamping it there overwrote the
        // interaction's own ttsEngine/cacheHit/azureError (field log 1786104958601).
        tts.speakUnlogged(text) { runCatching { tts.stop() } }
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
