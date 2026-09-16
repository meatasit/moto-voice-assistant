package com.moto.voice.network

import com.google.gson.annotations.SerializedName

data class WebhookResponse(
    val action: String = "speak",
    val contact: String? = null,
    val query: String? = null,
    val frequency: Double? = null,
    val speak: String = "",
    @SerializedName("stream_url") val streamUrl: String? = null,
    @SerializedName("station_name") val stationName: String? = null,
    @SerializedName("video_id") val videoId: String? = null,
    @SerializedName("video_title") val videoTitle: String? = null,
    /**
     * v1.4.2 — the untruncated title (n8n v3.9). [videoTitle] is cut to 60 chars for TTS;
     * YouTube's MediaSession reports the full one. Field log 1789518388540: the session was
     * PLAYING the requested video, but "…Billboard Top 100 🎧🔥" (60) vs "…Billboard Top 100
     * 🎧🔥 Justin Bieber Billie Eilish Miley Cyrus" (101) is 59% — under the verifier's 70%
     * prefix rule — so the app kept polling and finally declared the launch failed while the
     * rider could see it had opened. Verify against this; speak [videoTitle].
     */
    @SerializedName("video_title_full") val videoTitleFull: String? = null,
    val videos: List<Video>? = null,
) {
    data class Video(
        val id: String = "",
        val title: String = "",
        @SerializedName("title_full") val titleFull: String? = null,
    ) {
        /** Title to verify playback against — the full one when the workflow sent it. */
        val verifyTitle: String get() = titleFull?.takeIf { it.isNotBlank() } ?: title
    }
}
