package com.moto.voice.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Type

/**
 * The adapter class inside AppHistory.kt is private; duplicate its logic here to
 * verify that the JSON schema is stable — the on-disk payload survives updates.
 */
class HistoryActionSerializationTest {

    private val gson = GsonBuilder()
        .registerTypeAdapter(HistoryAction::class.java, TestAdapter())
        .create()

    @Test fun roundTripCall() {
        val e = HistoryEntry(1L, "โทรหาแม่", "กำลังโทรหาแม่", HistoryAction.Call("แม่", "0812345678"))
        val json = gson.toJson(e)
        val back = gson.fromJson(json, HistoryEntry::class.java)
        assertEquals(e, back)
    }

    @Test fun roundTripYoutube() {
        val e = HistoryEntry(2L, "เปิด เพลง", "กำลังเปิด", HistoryAction.YoutubeOpen("dQw4w9WgXcQ", "Test"))
        val back = gson.fromJson(gson.toJson(e), HistoryEntry::class.java)
        assertEquals(e, back)
    }

    @Test fun roundTripFm() {
        val e = HistoryEntry(3L, "เปิดวิทยุ 91.5", "เปิด", HistoryAction.FmPlay("https://s/x", "HotWave", 91.5))
        val back = gson.fromJson(gson.toJson(e), HistoryEntry::class.java)
        assertEquals(e, back)
    }

    @Test fun roundTripStop() {
        val e = HistoryEntry(4L, "หยุด", "หยุดแล้ว", HistoryAction.Stop)
        val back = gson.fromJson(gson.toJson(e), HistoryEntry::class.java)
        assertEquals(HistoryAction.Stop, back.action)
    }

    @Test fun unknownTypeFallsBackToStop() {
        val json = """{"timestamp":5,"heard":"","spoken":"","action":{"type":"weird_new_thing"}}"""
        val back = gson.fromJson(json, HistoryEntry::class.java)
        assertEquals(HistoryAction.Stop, back.action)
    }

    @Test fun frequencyOptional() {
        val e = HistoryEntry(6L, "", "", HistoryAction.FmPlay("https://s", "X", null))
        val back = gson.fromJson(gson.toJson(e), HistoryEntry::class.java)
        assertTrue(back.action is HistoryAction.FmPlay)
        assertNull((back.action as HistoryAction.FmPlay).frequency)
    }

    /** Same shape as the private adapter inside AppHistory.kt. */
    private class TestAdapter : JsonSerializer<HistoryAction>, JsonDeserializer<HistoryAction> {
        private val g = com.google.gson.Gson()
        override fun serialize(src: HistoryAction, t: Type, c: JsonSerializationContext): JsonElement =
            g.toJsonTree(src).asJsonObject.also { it.addProperty("type", src.type) }
        override fun deserialize(json: JsonElement, t: Type, c: JsonDeserializationContext): HistoryAction {
            val o = json.asJsonObject
            return when (o.get("type")?.asString) {
                HistoryAction.TYPE_CALL -> g.fromJson(o, HistoryAction.Call::class.java)
                HistoryAction.TYPE_YT -> g.fromJson(o, HistoryAction.YoutubeOpen::class.java)
                HistoryAction.TYPE_FM -> g.fromJson(o, HistoryAction.FmPlay::class.java)
                HistoryAction.TYPE_STOP -> HistoryAction.Stop
                HistoryAction.TYPE_SPEAK -> g.fromJson(o, HistoryAction.Speak::class.java)
                else -> HistoryAction.Stop
            }
        }
    }
}
