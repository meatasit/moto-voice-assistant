# v1.4.1 — diagnostics for cold-launch `noSession` failures

Branch: `diag-cold-launch-v1.4.1` · versionCode 50 · versionName 1.4.1

## Evidence

Field log `moto_voice_debug_1789483749397.json` (15 Sep 2026 evening, build-112): six
`youtube_play` in 30 minutes, **6/6** `launch→fullScreenIntent → nudge→launchBlocked(noSession)`,
all `screenLocked=true`, `fsiTrampolineRan=true`, `fsiTrampolineLaunchOk=true`,
`mediaCtrlPkgMiss=com.spotify.music` (a paused Spotify session; nothing was playing, so no
`repauseForeign`). YouTube never registered a session inside the 15.8 s cold window.

Everything the v1.4.0 round changed is healthy in the same log: `llmProvider=api`,
`_model=gpt-5.6-luna`, `webhookTimeMs` 2.2–4.6 s, `engineChoiceReason=android` on every row.

Put next to the previous morning's log `1789440952407`:

| YouTube state at launch (locked) | outcome |
|---|---|
| no session (cold) | `noSession` — 4/4 yesterday, 6/6 today |
| has a session (warm) | `refireSwitch(clearTask)` → confirmed, or confirmed directly |

The split is total, but it is *inferred* from which ops appear. The rider has also seen, with
his own eyes, YouTube stuck on its "Connect your devices" cast prompt after a voice launch —
a prompt that only appears when a castable device is on the same network, i.e. on home Wi‑Fi,
and that would keep a cold YouTube from ever starting playback (= no MediaSession).

## What changed — logging only, no behaviour change

* `DebugEntry.mediaPriorTitle` — YouTube's session title at launch, null on a cold launch.
* `DebugEntry.netTransport` — `wifi` / `cellular` / `other` / `none` at launch.

Both stamped in `MediaOrchestrator.openYoutube`. Nothing about launching, polling or spoken
lines moves. Version bumped because the file is media code (CLAUDE.md).

## What the next log should answer

1. Is every `noSession` a `mediaPriorTitle=null` (cold) launch, and every success warm?
2. Is every cold failure on `netTransport=wifi`, and does the same cold launch work on
   `cellular`? If yes, the cast prompt is the mechanism and the fix is in YouTube's settings
   (or Wi‑Fi off before the ride), not in this app.

Rider validates — Claude cannot run acceptance.
