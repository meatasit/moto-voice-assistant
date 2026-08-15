# v1.3.39 — revert v1.3.38

Branch: `revert-v1.3.38` · versionCode **45** · versionName 1.3.39

**This release contains no new work. It is v1.3.37's code with a raised version number.**

## Why

Rider, immediately after installing build-100 (v1.3.38): *"โปรแกรมล่าสุดไม่สามารถใช้งานได้เลย
กดปุ่มแล้วไม่เกิดอะไรขึ้นเลย"* — press BVRA, nothing happens at all.

The attached log `moto_voice_debug_1786761868857.json` is `[]` — completely empty. That is
worse than a log full of failures: [`DebugLog`](app/src/main/java/com/moto/voice/debug/DebugLog.kt)
keeps entries in a `CopyOnWriteArrayList` in memory with no persistence, so an empty export
means **no interaction has completed since the process started**. A crash on every press
would produce exactly this: the process dies, the in-memory log dies with it, and the next
export is empty again.

v1.3.38 is the only variable, so it goes back out. Restoring service beats finishing the
diagnosis — and with no device access I cannot reproduce it here.

## What was in v1.3.38 (now reverted)

1. Azure 401 latch — route everything to Android once the key is rejected
2. `TtsRouter.speak()` stopping any in-flight speech first
3. Earcon back to `TONE_PROP_BEEP` / `TONE_PROP_ACK`, volume 80 → 100
4. **Earcon tones moved to `STREAM_VOICE_CALL` when SCO is up** ← prime suspect

(4) is the one that touched a subsystem the interaction cannot start without. `Earcon.play`
guards the `ToneGenerator` *constructor* with `runCatching`, but `tone.startTone(...)` is
inside a bare `try/finally` — an exception there propagates out of `Earcon.ready()`, and
that call in `VoiceCommandPipeline` is **not** wrapped (unlike the `runCatching {
Earcon.endInteraction() }` in the finally block). A throw on a `STREAM_VOICE_CALL`
ToneGenerator would take the interaction — and possibly the process — down before anything
audible happened.

That is a hypothesis, not a diagnosis. It is not being fixed blind.

## versionCode

**45, not 43.** Android refuses to install an APK whose versionCode is lower than the
installed one, so a plain revert would produce a build the phone would not accept over
build-100. The version number goes forward even though the code goes back.

## What the diagnosis needs next

The empty log gives nothing to work with, so the next step needs information from the device
rather than another guess:

* Does the **app itself** open, or does it crash/close?
* On the home screen, is the red "ต้องแก้ก่อนใช้งาน" panel showing — specifically **Default
  Assistant**? If the update was installed with a different signature the OS does an
  uninstall + reinstall, which wipes app data and drops the assistant role. BVRA then does
  nothing and no log is ever written — the same symptom, an entirely different cause, and
  nothing to do with v1.3.38.
* If the app runs: `adb logcat` around a press would name the exception in one line.

## Re-landing v1.3.38

Piece by piece, not as one release, and with the earcon change hardened first:

* the whole of `Earcon.play` wrapped so a tone can never take an interaction down
* the stream switch behind a setting, off by default, so it can be tried without risking a
  ride
* the Azure 401 latch and the stop-before-speak fix shipped separately from anything audio-routing

The finding that produced v1.3.38 still stands and is worth re-landing: every Azure failure
in field log 1786688875809 was `IllegalStateException: HTTP 401` — the subscription key is
being rejected, which is what makes the assistant alternate between two voices.
