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
    val videos: List<Video>? = null,
) {
    data class Video(
        val id: String = "",
        val title: String = "",
    )
}
