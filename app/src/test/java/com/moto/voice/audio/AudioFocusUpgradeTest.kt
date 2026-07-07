package com.moto.voice.audio

import android.media.AudioFocusRequest
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sanity check on the constants — the upgrade path relies on the OS mapping
 * AUDIOFOCUS_GAIN to "permanent" and TRANSIENT_MAY_DUCK to "transient".
 * If these ever change (they won't, they're API contract) we'd want to notice.
 */
class AudioFocusUpgradeTest {

    @Test fun transientMayDuckExists() {
        // Constant present in the Android SDK — used by AudioFocusRouter.request().
        assertEquals(3, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
    }

    @Test fun gainIsPermanent() {
        // Constant present in the Android SDK — used by AudioFocusRouter.upgradeToPermanent().
        assertEquals(1, AudioManager.AUDIOFOCUS_GAIN)
    }

    @Test fun grantedResultConstantStable() {
        assertEquals(1, AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    @Test fun audioFocusRequestApiPresent() {
        // Belt-and-braces: the class we depend on must exist in the min-SDK we target.
        // Sprint E onward this class is API 26+ and minSdk is 26.
        val cls = AudioFocusRequest::class.java
        assertEquals("AudioFocusRequest", cls.simpleName)
    }
}
