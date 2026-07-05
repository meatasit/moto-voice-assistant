package com.moto.voice.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val json = """{"action":"youtube_play","video_id":"dQw4w9WgXcQ","video_title":"Never","speak":"ok"}"""
        val r = gson.fromJson(json, WebhookResponse::class.java)
        assertEquals("dQw4w9WgXcQ", r.videoId)
        assertEquals("Never", r.videoTitle)
    }

    @Test
    fun parsesYoutubeVideosArray() {
        val json = """
            {"action":"youtube_play",
             "video_id":"A","video_title":"first",
             "videos":[{"id":"A","title":"first"},{"id":"B","title":"second"},{"id":"C","title":"third"}]}
        """.trimIndent()
        val r = gson.fromJson(json, WebhookResponse::class.java)
        assertNotNull(r.videos)
        assertEquals(3, r.videos!!.size)
        assertEquals("A", r.videos[0].id)
        assertEquals("third", r.videos[2].title)
    }

    @Test
    fun parsesFmWithNumericFrequency() {
        val json = """{"action":"fm","frequency":91.5,"station_name":"Cool FM","stream_url":"https://s.example/live","speak":"เปิด"}"""
        val r = gson.fromJson(json, WebhookResponse::class.java)
        assertEquals("fm", r.action)
        assertEquals(91.5, r.frequency!!, 0.0001)
        assertEquals("Cool FM", r.stationName)
        assertEquals("https://s.example/live", r.streamUrl)
    }

    @Test
    fun parsesStopAction() {
        val json = """{"action":"stop","speak":"หยุดแล้ว"}"""
        val r = gson.fromJson(json, WebhookResponse::class.java)
        assertEquals("stop", r.action)
        assertEquals("หยุดแล้ว", r.speak)
    }

    @Test
    fun parsesNoneAction() {
        val json = """{"action":"none","speak":"สวัสดี"}"""
        val r = gson.fromJson(json, WebhookResponse::class.java)
        assertEquals("none", r.action)
    }

    @Test
    fun usesDefaultsForMissingFields() {
        val json = """{"speak":"hi"}"""
        val r = gson.fromJson(json, WebhookResponse::class.java)
        assertEquals("speak", r.action)
        assertEquals("hi", r.speak)
        assertNull(r.contact); assertNull(r.query); assertNull(r.frequency)
        assertNull(r.videoId); assertNull(r.videoTitle); assertNull(r.videos)
        assertNull(r.streamUrl); assertNull(r.stationName)
    }

    @Test
    fun tolerantOfEmptyVideosArray() {
        val json = """{"action":"youtube_play","query":"hello","videos":[],"speak":"ค้นหา"}"""
        val r = gson.fromJson(json, WebhookResponse::class.java)
        assertNotNull(r.videos)
        assertTrue(r.videos!!.isEmpty())
        assertEquals("hello", r.query)
    }

    @Test
    fun tolerantOfMissingSpeak() {
        val json = """{"action":"none"}"""
        val r = gson.fromJson(json, WebhookResponse::class.java)
        assertEquals("", r.speak)  // data-class default
    }
}
