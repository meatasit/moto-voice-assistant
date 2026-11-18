# v1.4.12 — the assistant went mute: a failed speak is no longer silent or permanent

Branch: `claude/busy-dijkstra-h4yi7e` · versionCode 61 · versionName 1.4.12 · **awaiting rider validation**

Field log 1794968515443, `appVersion 1.4.11 (60)` on all 50 entries. Rider: "ล่าสุดมี Bug ไม่มีเสียง AI เลย แต่สั่งงานแล้วทำนะ".

## The evidence

`actionTimeMs` on a `youtube_play` is *confirmation TTS + 800 ms SCO teardown + launch*.

| when | `actionTimeMs` | `scoTeardownMs` | left for the spoken line |
|---|---|---|---|
| 11–13 Nov (entries 49–26) | 3053 – 5578 | ~815 | 2.2 – 4.7 s — a sentence |
| **14–18 Nov (entries 24–0)** | **842 – 926** | ~815 | **30 – 110 ms — nothing was played** |

Last good line: entry 1794531789128 (13 Nov 08:03, 5308 ms). First mute one: 1794620370434
(14 Nov 08:39, 869 ms). Same build before and after — the app did not change, the phone did.
Every mute entry still says `ttsEngine: android`, no `error`: the commands ran (SCO, STT,
webhook, media all normal), only the voice was gone, and the log had no field to say why.

(The three ~2.3 s entries in the mute period are `browser→playFromSearch` cold starts — the
extra time is the MediaBrowser connect, not speech.)

## The finding

`speakAwait` came back in tens of milliseconds, so the engine refused or failed the line at
once and `ThaiTTS` resumed on `onError` exactly as it does on `onDone` — silently. The
process hosts the VoiceInteractionService and lives for days holding ONE `TextToSpeech`
(`TtsRouter` singleton). When that binding goes bad — the TTS engine app updated or
restarted underneath us — the platform does not rebuild it and every later `speak()` fails
instantly, until the app process dies.

**What the log cannot prove:** which failure it was (dead binding vs. the engine erroring,
e.g. Thai voice data gone after an engine update). v1.4.11 recorded nothing about a failed
speak. This build handles the first and names the second.

## Fix

* **Rebuild and retry once** (`AndroidTtsEngine`) — a line that fails (`speak()` returns
  ERROR, engine `onError`, or "done" in <200 ms for a real sentence) tears the
  `TextToSpeech` down, builds a new one and says the line again. Once per line, and only
  while it is still the newest line (`TtsRecovery.shouldRebind`).
* **`ttsError` on the debug entry** — `speakReturnedError→rebind→ok`,
  `code -4→rebind→failed(code -4)`, `doneTooFast(41ms)→…`, `initFailed(-1)`,
  `thaiVoiceMissingData` / `thaiNotSupported` (from `setLanguage`, stamped on every entry
  it affects). Null = spoken normally.
* **A failed engine init fails its queued lines** instead of leaving them waiting forever,
  and the next line tries a fresh init.

No media code touched (iron rules #1–#3 unaffected).

## What the next log should show

* Normal: no `ttsError`, `actionTimeMs` back to 3–5 s on a `youtube_play`.
* The binding died again: one entry with `ttsError: speakReturnedError→rebind→ok` — and the
  rider **heard** that line, about a second late.
* `…→rebind→failed(…)` or `thaiVoiceMissingData`: the phone's TTS engine itself is broken —
  Settings → General management → Text-to-speech: check the preferred engine and
  re-download the Thai voice. Send the log.

## Right now, on v1.4.11

Force-stop Moto Voice (or reboot the phone) and the voice should return, if the cause is
the dead binding.

## Claude could NOT run

Acceptance A–I — no device. Nor the local build: this sandbox has no Android SDK, so
`testDebugUnitTest` + `assembleDebug` are verified by CI on the PR, not here.
