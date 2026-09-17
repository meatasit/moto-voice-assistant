package com.moto.voice.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.6 — a warm switch spends its first deep-link delivery on a known no-op.
 *
 * Field log 1789637279880, all four warm switches, identical shape:
 *
 * ```
 * openYoutube:VID;link→webTargeted;launch→fullScreenIntent;
 * nudge→sessionSeen(playing);nudge→refireSwitch(clearTask);
 * link→webTargeted;launch→fullScreenIntent;nudge→play#1;nudge→confirmed
 * ```
 *
 * Every one landed — and every one landed on the ESCALATION. The first delivery navigated
 * zero times out of four here, and zero times out of eleven in field log 1786104958601,
 * because a plain NEW_TASK intent handed to a task that is already running is brought
 * forward without the intent being delivered. `priorTitle` tells us at fire time which case
 * we are in, so the first delivery can carry CLEAR_TASK and save the rider
 * REFIRE_STILL_PRIOR_MS plus a wasted trampoline.
 *
 * A cold target keeps the plain intent: there is no task to clear, and tearing down a launch
 * that is only slow is exactly the mistake REFIRE_NO_SESSION_MS is written to avoid.
 */
class WarmSwitchRestartTest {

    @Test fun warmSwitchRestartsTheTask() = assertTrue(
        "YouTube is already playing something — a plain delivery is a proven no-op",
        MediaOrchestrator.firstFireNeedsRestart(priorTitle = "GTA6 โดนแฮ็คมาโชว์แบบแปลกๆ"),
    )

    @Test fun coldLaunchKeepsThePlainIntent() = assertFalse(
        "nothing to clear, and CLEAR_TASK on a slow cold start would tear down a working launch",
        MediaOrchestrator.firstFireNeedsRestart(priorTitle = null),
    )

    /**
     * The cold path is the one that reached `sessionSeen(none);confirmed` on its FIRST
     * delivery in the same log (entry 1789608398866) — it must not be disturbed.
     */
    @Test fun theColdPathThatWorksIsLeftAlone() = assertFalse(
        MediaOrchestrator.firstFireNeedsRestart(priorTitle = null),
    )
}
