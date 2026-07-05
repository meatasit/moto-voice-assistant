package com.moto.voice.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class WebhookResponseTest {
    private val gson = Gson()

    @Test
    fun parsesCallAction() {
        val json = """{"action":"call","contact":"สมชาย","speak":"กำลังโทร"}"""
        val r = gson.fromJson(json, WebhookResponse::class.java)
        assertEquals("call", r.action)
        assertEquals("สมชาย", r.contact)
        assertEquals("กำลังโทร", r.speak)
    }

    @Test
    fun parsesYoutubeWithSnakeCaseVideoId() {
        val json = """{"action":"youtube_play","video_id":"dQw4w9WgXcQ","speak":"ok"}"""
        val r = gson.fromJson(json, WebhookResponse::class.java)
        assertEquals("dQw4w9WgXcQ", r.videoId)
    }

    @Test
    fun parsesFmWithStreamUrl() {
        val json = """{"action":"fm","frequency":"88","stream_url":"https://s.example/live","speak":"เปิด"}"""
        val r = gson.fromJson(json, WebhookResponse::class.java)
        assertEquals("fm", r.action)
        assertEquals("88", r.frequency)
        assertEquals("https://s.example/live", r.streamUrl)
    }

    @Test
    fun usesDefaultsForMissingFields() {
        val json = """{"speak":"hi"}"""
        val r = gson.fromJson(json, WebhookResponse::class.java)
        assertEquals("speak", r.action)
        assertEquals("hi", r.speak)
    }
}
