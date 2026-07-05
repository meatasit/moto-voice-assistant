package com.moto.voice.network

import com.google.gson.annotations.SerializedName

data class WebhookResponse(
    val action: String = "speak",
    val contact: String? = null,
    val query: String? = null,
    val frequency: String? = null,
    @SerializedName("stream_url") val streamUrl: String? = null,
    @SerializedName("video_id") val videoId: String? = null,
    val speak: String = "",
)
