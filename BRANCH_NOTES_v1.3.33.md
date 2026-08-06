# v1.3.33 — ready-cue race (warm case), TTS debug cross-contamination, stale prev_exit noise, SeekParser aliases

Field log: `moto_voice_debug_1786010970975.json` (rider-supplied, analyzed by Claude —
not yet run through ACCEPTANCE.md on-device).

## Bug 1 — ready cue STILL races on WARM SCO reconnects → FIXED (awaiting rider validation)

v1.3.32 fixed the *cold* first-press case (800ms settle) and added
`readyEarconRoute`/`scoColdConnect` instrumentation. This log is the first with that
instrumentation live, and it shows the race is broader than cold-start.

**Evidence:**
- Every `finishReason=no_speech` entry with `scoState=connected` in this log shows
  `readyEarconRoute=phone` — 3/3, including entries with `scoColdConnect=false` (a WARM
  reconnect, already past the first-press case).
- The rider's own next utterance, twice, confirms it out loud:
  - ts `1785979570955`: "เมื่อกี้ไม่ได้ยินสัญญาณที่ให้พูดเริ่มพูดได้เลยนะ" ("didn't hear the
    ready signal just now, just start talking") — spoken immediately after the
    `no_speech` entry at ts `1785979558545` (`scoState=connected`, `readyEarconRoute=phone`).
  - ts `1785892513682`: "เมื่อกี้ไม่ได้ยินเสียงสัญญาณที่ให้เริ่มพูดเลยนะครั้งแรกที่กด" — same
    pattern, following the `no_speech` entry at ts `1785892499257`.

**Mechanism:** `connectSco()` resolves after a *fixed* delay (`SCO_SETTLE_MS=300ms` warm /
`SCO_COLD_SETTLE_MS=800ms` cold) and the pipeline immediately reads
`communicationRouteIsSco()` once and plays `Earcon.ready()`. The fixed delay is a guess,
not a synchronization — `AudioManager.communicationDevice` doesn't reliably flip within it,
so the read races and reports (and plays) `phone` even though the SCO connect itself
succeeded. Rider doesn't hear the cue → doesn't speak in time → `no_speech`.

**Fix (`BluetoothAudioRouter.awaitScoRouteSettled` + `VoiceCommandPipeline`):** after
`connectSco()` succeeds, poll `communicationRouteIsSco()` for up to
`EARCON_ROUTE_POLL_BUDGET_MS=400ms` (60ms interval) before reading the route / firing the
earcon, instead of trusting the single delayed read. Bounded, so a helmet that never
confirms SCO still gets the earcon on schedule (phone speaker, same as today) — this only
removes the race, it doesn't add a new stall path.

**What the next field log should show:** `no_speech` entries with `scoState=connected`
should now show `readyEarconRoute=sco`. If `phone` still shows up, `EARCON_ROUTE_POLL_BUDGET_MS`
needs to grow.

## Bug 2 — TTS debug fields bleeding across interactions → FIXED

**Evidence (smoking gun):** entry ts `1785894704170` — `ttsSynthMs: 5` (a cache hit, no
synth call happened) but `azureError: "synth failed after 601ms"`. Those two facts are
mutually exclusive for the same speak() call. Also: `"speak returned ERROR"` — the literal
string `AndroidTtsEngine.speak()` returns on a synchronous `TextToSpeech.ERROR` — showed up
in `azureError` on entries whose `ttsEngine` was `azure`/`android_fallback_failed`, i.e. a
string that can only originate from Android's engine landed under the Azure-named field.

**Mechanism:** `TtsRouter.markDebug()` resolved `DebugLog.entries().firstOrNull()` — "the
current head" — *inside each async callback* (Azure's synth+playback run on a background
`Thread`). If the rider pressed the button again before that callback fired,
`DebugLog.new()` had already pushed a fresh entry to the front, and the stale callback
wrote its engine/error onto the WRONG (newer) interaction's entry. This inflated the
apparent Azure/TTS failure rate in every field log and made the failures impossible to
correlate with what actually happened.

**Fix (`TtsRouter.speak`):** capture `DebugLog.entries().firstOrNull()` once, synchronously,
at the top of `speak()`, and thread that same reference through every callback of that one
call instead of re-resolving "current head" each time.

**Caveat:** this is a logging-fidelity fix, not a claim that Azure/Android failures are
now zero — the genuine failures (both engines returning `speak returned ERROR` /
`synth failed` on some turns, e.g. ts `1785980519444`, `1785980504266`, `1785979570955`)
are still unexplained and worth its own investigation once the log stops cross-contaminating
entries. Also worth noting separately: `ThaiTTS.speak`/`speakAwait` treat a *total* TTS
failure (both engines) identically to success (`onDone` fires either way) — the rider gets
silence with no fallback earcon on that path. Flagged, not changed this round (no new
features during the stabilization sprint; this needs its own evidenced, scoped fix).

## Bug 3 — stale `prev_exit:OTHER(16)` re-logged on every cold Service restart → FIXED

**Evidence:** every `prev_exit_other(16)` entry in this log carries the same reason but a
different (growing) `age` — 1002815866ms, 1017711310ms, 1041721026ms, 1089875213ms,
1118602864ms — all pointing at the *same* single exit event from ~13 days ago. And every
one of the 5 occurrences is followed, within 44–345ms, by a failed interaction
(`no_speech` ×4, `barge_in_cancel` ×1) — i.e. `classifyPreviousExit()` firing is itself a
reliable marker that this Service instance just cold-started, which is exactly the
scenario Bug 1 targets.

**Mechanism:** `VoiceCommandService` deliberately stays alive between interactions (see its
own kdoc — this was already fixed once, specifically to avoid restart noise). But when it
*does* idle-stop and cold-restart, `classifyPreviousExit()` unconditionally re-logs
whatever `getHistoricalProcessExitReasons(.... maxNum=1)` returns — with no check for
"have I already reported this exact exit event before" — so it re-announces the same
13-day-old crash/kill on every cold start, forever, burning a `DebugLog` slot (50-entry
ring buffer) each time with zero new information.

**Fix:** persist the last-reported exit `timestamp` in the existing `WATCHDOG_PREFS`
SharedPreferences (alongside `KEY_LAST_CREATE`); skip logging when the current
`getHistoricalProcessExitReasons` result isn't newer than what was already reported.

**Not fixed here:** the underlying "first interaction after a cold Service restart tends
to fail" correlation should improve as a side effect of Bug 1's fix (same cold-audio-stack
root cause), but that's a hypothesis for the next field log to confirm, not a claim.

## Bug 4 — SeekParser gaps: "เดินหน้า" verb, "เลือด" mishearing → FIXED

Both fell through to the (slower, less reliable) webhook instead of the local intercept.

- **"เดินหน้า 20 นาที"** (ts `1785980519444`) — "เดินหน้า" ("advance"/"drive forward") is a
  real forward synonym, just never in the loose-verb list. Added alongside
  เลื่อน/ข้าม/skip/กรอ in `FORWARD_REGEX`.
- **"เลือด 50 นาที"** — logged **twice**, identical wording (ts `1785980504266` → −300s,
  ts `1785894312901` → +300s). Same input, opposite webhook guesses, because "เลือด"
  ("blood") carries no directional word for the LLM to anchor on. This reads as an STT
  mishearing of "เลื่อน" (same phonetic-confusion class as the existing v1.3.15 "เดือน"
  alias). Added as a second alias in `FORWARD_MISHEARING_REGEX`, STRICT (requires a
  direction word or number+unit) so real mentions of "blood" don't false-positive.

Both are local-intercept only — offline, instant, and (for the "เลือด" case) now
deterministic instead of an LLM coin-flip. New JUnit coverage in `SeekParserTest.kt`.

## Not fixed — Spotify `launch_blocked` (ts `1785931419851`), insufficient evidence this round

This log's one `launch_blocked` entry shows `mediaCtrlPkgMiss=com.spotify.music`, matching
the pattern from earlier field logs (1784028862496, 1784173407858) that got the v1.3.25/
v1.3.31 pre-pause + per-tick re-pause fixes. But the specifics here don't match those
fixes' failure mode:
- `screenLocked=false` (not the BAL-lock case).
- `mediaOperations` shows **no** `repauseForeign`/`prepauseForeign` log line firing during
  the poll — meaning `foreignActivePlayers()` never saw Spotify actively PLAYING/BUFFERING
  during the whole 9.8s window, so it wasn't fighting YouTube for focus this time.
- `webhookTimeMs=10676ms` — the n8n round-trip alone took nearly 11 seconds, on an
  otherwise-normal request. That kind of latency eating into the fixed poll window is a
  plausible independent explanation for the session never registering in time.

`mediaCtrlPkgMiss` here just means "Spotify was the only session that existed at
declare-time," not "Spotify was proven to be the blocker" — those are different claims and
this one occurrence doesn't distinguish them. Per sprint discipline (ห้ามแก้ก่อนพิสูจน์), no
code change against a single, ambiguous occurrence. **Next field log:** if this recurs,
check specifically whether `repauseForeign` fires (→ Spotify is still the cause, escalate
the pause-war fix) or whether `webhookTimeMs` is consistently very high on `launch_blocked`
entries (→ the poll window needs to account for slow webhook latency, a different fix).

## Also observed, not app-fixable this round

- Two `no_speech` entries (`scoState=no_headset`) are plain phone-mic environmental misses
  (STT 7/11 with no partial or a self-echo-shaped partial) — no BT route involved, likely
  road/wind noise. Nothing in-app to change.
- `webhookTimeMs` runs 7–16s on several entries in this log (chat fallback + youtube_play).
  That's n8n/LLM-side latency, same family as the v1.3.32 Bug 2 note — rider-owned.

## Acceptance (rider — Claude has no device/build access)

Media/audio change → run ACCEPTANCE.md A–G on the S24 Ultra + Vimoto V11X, **two clean
rounds**. Specifically re-check:
1. A **warm** re-press (helmet already connected, not the very first press) still gets an
   audible ready beep — confirm the exported log shows `readyEarconRoute=sco` on entries
   with `scoColdConnect=false`.
2. Seek: "เดินหน้า 20 นาที" and "เลือด 50 นาที" should now show
   `webhookRequest="[intercepted: Seek]"` (local, not webhook) and consistently seek
   **forward**.
