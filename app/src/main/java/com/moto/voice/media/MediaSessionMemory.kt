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

    /** Called by the pipeline right after a successful [WebhookResponse] youtube_play resolves. */
    fun rememberYoutube(videos: List<WebhookResponse.Video>, playedId: String, playedTitle: String) {
        this.videos = videos
        this.currentIndex = videos.indexOfFirst { it.id == playedId }.coerceAtLeast(0)
        this.currentTitle = playedTitle
    }

    /** Called by the pipeline when FM starts — used for "เมื่อกี้อะไร" answers. */
    fun rememberFm(stationName: String) {
        this.videos = emptyList()
        this.currentIndex = -1
        this.currentTitle = stationName
    }

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
    }
}
