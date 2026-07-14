# CLAUDE.md — Rules for Working on Moto Voice

Guidance for Claude Code sessions on this repo. Read every session.

## Iron rules for media operations (v1.3.20 sprint)

Field log `moto_voice_debug_1784028862496.json` proved that scattered media
logic caused Spotify to hijack YouTube requests. Every media-touching commit
from v1.3.20 onward must respect these three rules, enforced by
[`MediaOrchestrator`](app/src/main/java/com/moto/voice/media/MediaOrchestrator.kt):

### Rule #1 — Every op has an explicit targetPkg

Media keys (`KEYCODE_MEDIA_PLAY / PAUSE / FF / REW`) are the "aimless gun"
that routed to whatever session was topmost — Spotify in the field log.

- With notification-listener permission granted: use `MediaController.transportControls`
  with an **explicit target package**. Never dispatch a bare media key.
- Without permission: media-key fallback is allowed because we can't name a
  target anyway. `MediaOrchestrator.Result.MediaKeyFallback` marks this path.

Violation to look for in reviews: any call to
`AudioManager.dispatchMediaKeyEvent(...)` outside of
`MediaOrchestrator` or `MediaStopper.dispatchExternalPauseOnly` (which is
gated by the orchestrator's hasPermission check).

### Rule #2 — Remember lastOpenedApp + lastVideoId

"เล่นต่อ" targets **what WE opened**, not "whichever session is topmost right
now". Enforced by [`MediaSessionMemory`](app/src/main/java/com/moto/voice/media/MediaSessionMemory.kt):

- If the rider explicitly names an app in the sentence ("youtube" / "spotify"),
  the name wins. Caught locally in `LocalIntercept.PlayContinue` **before** the
  webhook so the LLM can't guess wrong.
- Otherwise, target is `MediaSessionMemory.lastOpenedApp()` (default YouTube).
- If the target has **no active session** (BAL block, etc.), refire the deep
  link for `lastVideoId()` rather than dispatching `play()` into thin air —
  which was the Spotify-hijack vector.

### Rule #3 — Every op logs targetPkg + actual result

The debug log must be able to reconstruct what happened without guesswork.

- `DebugEntry.mediaTargetPkg` — the package name the op targeted.
- `DebugEntry.mediaOperations` — semicolon-separated op log
  ("openYoutube→VID123;nudge→play#1;nudge→confirmed").
- `DebugEntry.launchBlocked` + `FinishReason.LAUNCH_BLOCKED` — when the deep
  link fired but the target app's session never registered within the poll
  window. Speak `ErrorSpeech.LAUNCH_BLOCKED_LOCKED` — **never silent**.

## Acceptance Suite requirement

Every commit that touches audio/media code must run [`ACCEPTANCE.md`](ACCEPTANCE.md)
scenarios A–G on a real device (Samsung S24 Ultra + Vimoto V11X helmet) and
attach the resulting debug-log JSON to the PR / branch notes. **Two clean
rounds in a row** before merge. Rider validates — Claude cannot.

If Claude cannot run acceptance (no device access from the sandbox), state so
explicitly in the response and mark the change as "awaiting rider validation".

## Stabilization sprint discipline (v1.3.20)

- **ห้ามแก้ก่อนพิสูจน์** — never fix before proving with a field-log
  evidence line. Every code change targets a specific observable symptom.
- **ห้ามประกาศเสร็จโดยไม่มี log แนบ** — never claim a media fix is done
  without a log excerpt showing the new behaviour on a real device.
- **ห้ามเพิ่ม feature ระหว่างสปรินต์** — no new features during the
  stabilization sprint. Only consolidation + honest error paths.

## Test conventions

- Pure-JVM tests live in `app/src/test/java/…` and must not require
  Android instrumentation (no live `Handler`, `Context`, or `MediaSession`).
- `MediaOrchestrator` initializes its `Handler` lazily so pure tests can
  exercise `resolveTarget` and read `SPOTIFY_PKG` without hitting
  `Looper.getMainLooper()`.
- Instrumentation-required paths (nudge polling, deep-link, session-appearance
  timing) are validated by the Acceptance Suite, not JUnit.

## Version bump

Every media/audio change bumps `versionCode` + `versionName` in
`app/build.gradle.kts`. Version bumps land in the same commit as the code
change, never separately.
