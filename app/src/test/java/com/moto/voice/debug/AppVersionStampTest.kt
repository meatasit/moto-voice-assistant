package com.moto.voice.debug

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.5 — every [DebugEntry] carries the build that produced it.
 *
 * Reviewing field log 1789561893967 the first question was "did the rider run the build with
 * the v1.4.4 fixes in it?" and the export could not answer: no version anywhere in the file,
 * and every field present had existed since v1.4.2. Per-entry rather than per-file because
 * entries are excerpted one at a time into branch notes and chat.
 *
 * Pure JVM: no Application boots here, so [DebugLog.appVersion] stays null and entries
 * inherit that — the stamp itself is an instrumented path (PackageManager).
 */
class AppVersionStampTest {

    @Test fun entriesCarryWhateverVersionTheLogWasBoundTo() {
        val e = DebugLog.new()
        assertTrue(
            "a pure-JVM entry inherits DebugLog.appVersion, which no Application has set",
            e.appVersion == DebugLog.appVersion,
        )
    }

    @Test fun theFieldIsSerialisedIntoTheExport() {
        val e = DebugEntry(appVersion = "1.4.5 (54)")
        assertTrue(e.toString().contains("1.4.5 (54)"))
    }
}
