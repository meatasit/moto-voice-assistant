package com.moto.voice.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.12 — rider's rule: music opens from YouTube unless the rider says Spotify.
 * A `spotify_play` from the workflow is honoured only when the sentence names Spotify.
 */
class MusicSourceRuleTest {

    @Test fun plainOpenMusicOverrulesSpotify() {
        assertTrue(MusicSourceRule.mustUseYoutube("spotify_play", "เปิดเพลงให้ฟังหน่อย"))
        assertTrue(MusicSourceRule.mustUseYoutube("spotify_play", "เปิดเพลงสากลชิวๆ"))
    }

    @Test fun namingSpotifyKeepsSpotify() {
        assertFalse(MusicSourceRule.mustUseYoutube("spotify_play", "เปิดเพลงจาก spotify"))
        assertFalse(MusicSourceRule.mustUseYoutube("spotify_play", "เปิดเพลงจาก Spotify ให้หน่อย"))
        assertFalse(MusicSourceRule.mustUseYoutube("spotify_play", "เปิดเพลงจากสปอติฟาย"))
    }

    @Test fun recogniserSpellingsOfSpotifyCount() {
        assertTrue(MusicSourceRule.namesSpotify("เปิดเพลงจาก Sportify"))
        assertTrue(MusicSourceRule.namesSpotify("เปิดสปอทิฟาย"))
        assertTrue(MusicSourceRule.namesSpotify("เปิดสปอร์ติฟาย"))
        assertTrue(MusicSourceRule.namesSpotify("เปิดสปอตติฟาย"))
    }

    @Test fun otherActionsAreNeverTouched() {
        assertFalse(MusicSourceRule.mustUseYoutube("youtube_play", "เปิดเพลง"))
        assertFalse(MusicSourceRule.mustUseYoutube("fm", "เปิดวิทยุ"))
        assertFalse(MusicSourceRule.mustUseYoutube("chat", "เปิดเพลง"))
    }

    @Test fun actionCaseAndPaddingDoNotMatter() {
        assertTrue(MusicSourceRule.mustUseYoutube(" Spotify_Play ", "เปิดเพลง"))
    }

    @Test fun retrySentenceNamesYoutube() {
        assertEquals("เปิดเพลงสากล จาก YouTube", MusicSourceRule.youtubeRetryText(" เปิดเพลงสากล "))
    }
}
