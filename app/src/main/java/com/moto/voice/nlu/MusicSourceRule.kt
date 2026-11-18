package com.moto.voice.nlu

/**
 * v1.4.12 — rider's rule: **music opens from YouTube unless the rider says Spotify.**
 *
 * "อยากให้เพิ่มกฏว่าถ้าสั่งเปิดเพลง ให้ Default เป็น youtube นอกจากสั่งว่าให้เปิดจาก Spotify" — one day
 * a plain "เปิดเพลง" came back as a Spotify playlist. Which app to open is the workflow's
 * (LLM's) answer, `action=spotify_play`, and the app used to obey it unconditionally.
 *
 * This is the app-side guard: a `spotify_play` is honoured only when the rider's own
 * sentence names Spotify. Otherwise the pipeline asks the workflow again with YouTube named
 * and plays that. Same idea as Rule #2's "the rider's word wins over a guess".
 */
object MusicSourceRule {

    /** Op-log token (Rule #3) written when a `spotify_play` was overruled. */
    const val OP_OVERRULED = "spotifyNotNamed→askYoutube"
    /** Op-log token when the second ask did not come back as `youtube_play` either. */
    const val OP_RETRY_FAILED = "askYoutube→failed"

    /**
     * Spotify as the recogniser writes it: Latin (`spotify`, and the common `sportify`
     * slip) or Thai (สปอติฟาย / สปอทิฟาย / สปอตติฟาย / สปอร์ติฟาย).
     */
    private val SPOTIFY_NAMED = Regex("spor?t+ify|สปอ(?:ร์)?[ตท]+ิฟาย", RegexOption.IGNORE_CASE)

    fun namesSpotify(riderText: String): Boolean = SPOTIFY_NAMED.containsMatchIn(riderText)

    /** True when the workflow chose Spotify for a sentence that never asked for it. */
    fun mustUseYoutube(action: String, riderText: String): Boolean =
        action.trim().equals("spotify_play", ignoreCase = true) && !namesSpotify(riderText)

    /** The sentence sent on the second ask — the rider's words with the source made explicit. */
    fun youtubeRetryText(riderText: String): String = "${riderText.trim()} จาก YouTube"
}
