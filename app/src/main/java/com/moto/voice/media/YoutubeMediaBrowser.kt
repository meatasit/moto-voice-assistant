package com.moto.voice.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.util.Log
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v1.4.5 — start YouTube playback **without putting an Activity on screen**, which is the
 * one thing the deep-link path cannot do while the phone is locked.
 *
 * ## The failure this exists to fix
 *
 * A COLD YouTube (nothing playing, so no session yet) opened behind a SECURE keyguard never
 * starts. [LockLaunchActivity] does get the deep link launched — field logs stamp
 * `fsiTrampolineRan=true` + `fsiTrampolineLaunchOk=true` — but the YouTube Activity it starts
 * is created behind the keyguard and never becomes visible, and YouTube only begins playback
 * when its player is visible. No playback means no `MediaSession`, which is what the nudge
 * polls for, so the interaction ends `launchBlocked(noSession)`.
 *
 * Field log `1789561893967` holds the cleanest proof yet — the SAME video id, the same lock
 * state, 40 seconds apart:
 *
 * ```
 * 19:46:28  เปิดเพลงสากลชิวๆ         openYoutube:eoXtKw_bW_s;…;nudge→launchBlocked(noSession)
 * 19:47:07  เปิด YouTube เพลงสากลชิวๆ  openYoutube:eoXtKw_bW_s;…;nudge→confirmed   playbackState=playing
 * ```
 *
 * The first attempt failed because YouTube was cold. It left YouTube's process warm, so the
 * second attempt landed. The rider's other complaint — unlocking the phone later and finding
 * YouTube sitting there open — is the same mechanism seen from the other side: the Activity
 * was created and simply waited for a visible window.
 *
 * ## Why a MediaBrowser fixes it
 *
 * `mediaBrowserAvail=yt=true` in every recent field entry: the YouTube app publishes a
 * [MediaBrowser] service. That is the interface Android Auto and the system assistant use to
 * start playback headlessly — it binds to a Service, so there is no Activity, no window, and
 * therefore nothing for the keyguard to block. Playback begins with the screen still locked
 * and the phone still in the rider's pocket.
 *
 * The trade for that is precision: [MediaController.TransportControls.playFromSearch] takes a
 * **search string**, not a video id, so YouTube picks what to play. We hand it the most
 * specific string we have (the full video title — see
 * [MediaOrchestrator.browserSearchTerm]) and let the existing title verification in the nudge
 * judge the result, exactly as it judges a deep link. Nothing here is allowed to report
 * success on its own.
 *
 * ## Never worse than today
 *
 * Every failure mode returns [Outcome.Failed] with a reason that lands in
 * `DebugEntry.mediaOperations`, and [MediaOrchestrator] then fires the ordinary deep link.
 * YouTube may refuse the connection outright (`onGetRoot` rejecting an unknown caller is
 * normal for media apps), it may not export the service on some builds, or `playFromSearch`
 * may do nothing at all — in each case the rider gets the v1.4.4 behaviour, minus the
 * [CONNECT_TIMEOUT_MS] ceiling spent finding out.
 *
 * Requires the `<queries>` block in the manifest (landed in v1.4.2) to see the service at all
 * on Android 11+.
 */
object YoutubeMediaBrowser {

    private const val TAG = "YoutubeMediaBrowser"

    /**
     * Ceiling on the bind + `onGetRoot` round trip. Deliberately short: this whole path is an
     * attempt to be FASTER than the 15 s cold poll window, so a slow or hung connection must
     * hand back to the deep link quickly rather than eat the rider's time twice.
     */
    private const val CONNECT_TIMEOUT_MS = 3_000L

    /** The action a media app's browsable service declares. Also probed by [MediaOrchestrator]. */
    const val BROWSER_SERVICE_ACTION = "android.media.browse.MediaBrowserService"

    /**
     * Held across the launch so the binding outlives [playFromSearch].
     *
     * We deliberately do NOT disconnect when the interaction ends. Disconnecting the last
     * client of a MediaBrowserService can let the service stop, and stopping YouTube's
     * playback service one interaction after we asked it to play would be a far worse bug
     * than the one this class fixes. One idle binding is cheap, it is replaced (not stacked)
     * on the next launch, and keeping YouTube's process warm is useful here rather than
     * wasteful — a warm YouTube is the state in which everything already works.
     */
    @Volatile private var browser: MediaBrowser? = null

    /**
     * The controller for the session we started, kept so the nudge can verify playback even
     * when notification-listener access is denied. [MediaSessions.controllerFor] needs that
     * permission; a token handed to us by the browser does not.
     */
    @Volatile private var controller: MediaController? = null

    /** What the attempt did. [Failed.reason] is written verbatim into the op log. */
    sealed class Outcome {
        /** `playFromSearch` was dispatched. Says nothing about whether YouTube obeyed it. */
        object Dispatched : Outcome()
        /** Nothing was dispatched — caller must fall back to the deep link. */
        data class Failed(val reason: String) : Outcome()
    }

    /**
     * The YouTube app's MediaBrowserService, or null when it doesn't publish one (or the
     * manifest `<queries>` block is missing, which hides it on Android 11+).
     */
    fun serviceComponent(context: Context): ComponentName? {
        val intent = Intent(BROWSER_SERVICE_ACTION).setPackage(MediaSessions.YOUTUBE_PKG)
        val info = runCatching { context.packageManager.queryIntentServices(intent, 0) }
            .onFailure { Log.w(TAG, "queryIntentServices failed", it) }
            .getOrNull()
            ?.firstOrNull()
            ?.serviceInfo
            ?: return null
        return ComponentName(info.packageName, info.name)
    }

    /**
     * Connect to YouTube's browser service and ask it to play [query], with no Activity and
     * no window involved.
     *
     * Runs on the main thread throughout: [MediaBrowser] binds its callbacks to the Looper of
     * the thread that constructs it, so building it anywhere else would silently never call
     * back. The pipeline is already on [Dispatchers.Main]; [withContext] makes that a
     * guarantee rather than an assumption.
     */
    suspend fun playFromSearch(context: Context, query: String): Outcome =
        withContext(Dispatchers.Main) {
            val appCtx = context.applicationContext
            val component = serviceComponent(appCtx)
                ?: return@withContext Outcome.Failed("noService")

            // Replace any binding from a previous launch — one at a time, never stacked.
            release()

            var pending: MediaBrowser? = null
            // null = timed out, false = YouTube refused us, true = connected.
            val connected: Boolean? = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val callback = object : MediaBrowser.ConnectionCallback() {
                        override fun onConnected() {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onConnectionFailed() {
                            Log.w(TAG, "YouTube refused the browser connection (onGetRoot rejected)")
                            if (cont.isActive) cont.resume(false)
                        }

                        override fun onConnectionSuspended() {
                            Log.w(TAG, "browser connection suspended before we could play")
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                    val mb = MediaBrowser(appCtx, component, callback, null)
                    pending = mb
                    runCatching { mb.connect() }.onFailure {
                        Log.w(TAG, "MediaBrowser.connect() threw", it)
                        if (cont.isActive) cont.resume(false)
                    }
                }
            }

            val mb = pending
            if (mb == null || connected != true) {
                runCatching { mb?.disconnect() }
                return@withContext Outcome.Failed(
                    if (connected == null) "connectTimeout" else "connectRefused"
                )
            }

            val token = runCatching { mb.sessionToken }.getOrNull()
            if (token == null) {
                runCatching { mb.disconnect() }
                return@withContext Outcome.Failed("noToken")
            }

            val ctrl = runCatching { MediaController(appCtx, token) }
                .onFailure { Log.w(TAG, "MediaController from browser token failed", it) }
                .getOrNull()
            if (ctrl == null) {
                runCatching { mb.disconnect() }
                return@withContext Outcome.Failed("noController")
            }

            val dispatched = runCatching { ctrl.transportControls.playFromSearch(query, null) }
                .onFailure { Log.w(TAG, "playFromSearch threw", it) }
                .isSuccess
            if (!dispatched) {
                runCatching { mb.disconnect() }
                return@withContext Outcome.Failed("playThrew")
            }

            browser = mb
            controller = ctrl
            Log.d(TAG, "playFromSearch dispatched headlessly: \"$query\"")
            Outcome.Dispatched
        }

    /**
     * The controller for the session this class started, if a launch is still current.
     *
     * The nudge prefers it over [MediaSessions.controllerFor] on a browser launch so that
     * verification works on a phone where notification-listener access was never granted —
     * without it, such a phone would see no session, decide the browser did nothing, and
     * fire the deep link on top of playback that had actually started.
     */
    fun activeController(): MediaController? = controller

    /** Drop the current binding. Called before a new launch, and by tests. */
    fun release() {
        runCatching { browser?.disconnect() }
            .onFailure { Log.w(TAG, "disconnect failed", it) }
        browser = null
        controller = null
    }
}
