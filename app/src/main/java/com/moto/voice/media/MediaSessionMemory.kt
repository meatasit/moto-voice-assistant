package com.moto.voice.media

import com.moto.voice.network.WebhookResponse

/**
 * In-memory record of the most-recently-opened media (YouTube or FM), used by the
 * v1.3.8 B5 contextual intercepts: "อันต่อไป" / "เปลี่ยน" / "อันอื่น" / "ไม่เอาอันนี้"
 * (advance to next video in the current webhook's `videos` array) and "เมื่อกี้อะไร" /
 * "เล่นอะไรอยู่" (tell the rider what just started).
 *
 * Deliberately NOT persisted:
 *   * The "next" context only makes sense inside the same riding session — once the
 *     app process dies or the rider power-cycles the phone the current video list
 *     is stale (yesterday's news livestream is not what "อันต่อไป" should open).
 *   * The stateless webhook always returns a fresh videos[] for every query, so
 *     losing this on restart is a natural boundary.
 *
 * Thread-safe writes via @Volatile fields — reads may race writes and see stale but
 * self-consistent snapshots, which is fine (worst case: an intercept opens what was
 * previously current for one interaction, then the next one is up-to-date).
 */
object MediaSessionMemory {

    @Volatile private var videos: List<WebhookResponse.Video> = emptyList()
    @Volatile private var currentIndex: Int = -1
    @Volatile private var currentTitle: String = ""

    /**
     * v1.3.20 — the package name of the media app we most-recently deep-linked into.
     * Consumed by [com.moto.voice.media.MediaOrchestrator.playContinue] to answer
     * "เล่น (ต่อ)?" without ambiguity: target is what WE opened, not "whichever
     * session happens to be topmost right now" (which was Spotify in the field
     * log 1784028862496 — YouTube session never registered, Spotify was on top,
     * rider heard Spotify instead of YouTube).
     */
    @Volatile private var lastOpenedApp: String? = null
    /** v1.3.20 — most recent YouTube videoId. Used to refire the deep-link on "เล่นต่อ" if no session exists. */
    @Volatile private var lastVideoId: String? = null

    /** Called by the pipeline right after a successful [WebhookResponse] youtube_play resolves. */
    fun rememberYoutube(videos: List<WebhookResponse.Video>, playedId: String, playedTitle: String) {
        this.videos = videos
        this.currentIndex = videos.indexOfFirst { it.id == playedId }.coerceAtLeast(0)
        this.currentTitle = playedTitle
        this.lastOpenedApp = MediaSessions.YOUTUBE_PKG
        this.lastVideoId = playedId
    }

    /** Called by the pipeline when FM starts — used for "เมื่อกี้อะไร" answers. */
    fun rememberFm(stationName: String) {
        this.videos = emptyList()
        this.currentIndex = -1
        this.currentTitle = stationName
        // FM is our own service, not a "deep-linkable app" — clear lastOpenedApp
        // so a later "เล่นต่อ" doesn't try to refire an FM deep link (there isn't one).
        this.lastOpenedApp = null
        this.lastVideoId = null
    }

    /** v1.3.20 — record any app we deep-linked into (e.g. Spotify). videoId nullable for non-YouTube. */
    fun rememberOpenedApp(packageName: String, videoId: String?) {
        this.lastOpenedApp = packageName
        this.lastVideoId = videoId
    }

    /** v1.3.20 — target for "เล่นต่อ" when the rider doesn't say the app name explicitly. */
    fun lastOpenedApp(): String? = lastOpenedApp

    /** v1.3.20 — refire target for YouTube "เล่นต่อ" when no session exists. */
    fun lastVideoId(): String? = lastVideoId

    /**
     * @return the next [WebhookResponse.Video] in the list (currentIndex + 1) if it
     *   exists, else null (list is empty OR we're already at the last entry).
     */
    fun nextVideo(): WebhookResponse.Video? {
        val list = videos
        val next = currentIndex + 1
        if (next < 0 || next >= list.size) return null
        return list[next]
    }

    /** Update state after "อันต่อไป" opens the returned candidate. */
    fun advanceTo(video: WebhookResponse.Video) {
        val list = videos
        val idx = list.indexOfFirst { it.id == video.id }
        if (idx >= 0) currentIndex = idx
        currentTitle = video.title.ifBlank { currentTitle }
    }

    /** Read-only view of what was last opened — used by "เมื่อกี้อะไร" replies. */
    fun currentTitle(): String = currentTitle

    /** Whether we have anything to talk about — true after any rememberXxx call. */
    fun hasContext(): Boolean = currentTitle.isNotBlank()

    /** Test hook — clears everything so tests don't leak into each other. */
    internal fun resetForTest() {
        videos = emptyList()
        currentIndex = -1
        currentTitle = ""
        lastOpenedApp = null
        lastVideoId = null
    }
}
