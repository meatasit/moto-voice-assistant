# v1.4.4 — ten audit findings, one release

Branch: `fix-audit-v1.4.4` (stacked on `fix-title-nbsp-v1.4.3`) · versionCode 53 · versionName 1.4.4

After v1.4.3 the rider asked for a full review of the media/pipeline code. Eight finder
angles and four verifiers over `media/`, `pipeline/` and `WebhookResponse.kt` produced ten
findings that survived verification (nine traced through the code, one plausible). They are
not backed by a field log each — the sprint rule is "no fix before proof" — but every one is
either an **honest-error path** (Rule #3: never silent, never a wrong line) or a **contract
the orchestrator already states** and did not keep. No new features.

| # | where | what was wrong | fix |
|---|---|---|---|
| 1 | `MediaOrchestrator.openYoutube` | When the deep link could not be fired (no resolvable intent, `startActivity` threw, FSI notification not posted) it returned `NoTarget` with no nudge, and both call sites discarded the Result. Rider heard "กำลังเปิด…" then nothing, log said `ok`. | New `Result.LaunchFailed`; entry stamped `launchBlocked` + `LAUNCH_BLOCKED` + `openYoutube→launchFailed`; `handleYoutube`, `handleNextVideo`, `handlePlayContinue` and the seek-resume branch speak `LAUNCH_FAILED_NO_SESSION`. Same for the `playContinue` refire. |
| 2 | `MediaSessionMemory` | `rememberYoutube` stored the 60-char speech title; `playContinue`'s refire verified against it — the v1.4.2 bug, reproduced on the "เล่นต่อ" path. | `currentVerifyTitle` kept beside `currentTitle` (speech, unchanged for "เมื่อกี้อะไร"); `rememberYoutube(…, playedVerifyTitle)` and `advanceTo` fill it; refire verifies against it. `VerifyTitleMemoryTest`. |
| 3 | nudge poll | `sawSession` latched on the first controller — on a warm switch that is the OLD video's session — so `sessionLost` ("opened then stopped") was spoken for switches that never landed, ahead of the unlock advice. | `sawNewSession` via pure `countsAsNewSession(verdict, priorTitle)`: cold launch → any session; warm → only a title that moved away from the prior. `NudgeLifecycleContractTest`. |
| 4 | `foreignActivePlayers` | Only YouTube was excluded, so our own `FmPlayerService` media3 session was "foreign" and got `pause()`d every 500 ms tick while a YouTube nudge polled. | `isForeignPackage(pkg, selfPkg)` also excludes the app's own package. Test. |
| 5 | `MediaOrchestrator.seek` | The one op that did not `cancelPendingNudge()`; an in-flight warm-switch nudge could `CLEAR_TASK`-restart YouTube on top of the seek. | Cancels like every other op. |
| 6 | nudge lifetime | Only media ops cancelled a pending nudge; call / chat / fm / seek-by-amount did not, so a 15 s poll could `QUEUE_FLUSH` a call confirmation with "เปิดไม่สำเร็จ" and stamp `LAUNCH_BLOCKED` on the old entry. | Public `supersedePendingNudge(reason)`, called at the top of every interaction (`runPipelineBody`). The cancel is logged into the superseded entry as `nudge→cancelled(newInteraction)` so a missing confirm/blocked op is explained. |
| 7 | `"seek"` dispatch | `(frequency ?: 0.0).toInt()` — a MISSING amount became "0 = resume", which with no live session re-fired the last deep link (video restarts) while speaking "ย้อนกลับให้ค่ะ". | Pure `SeekAmount.decide`: null/NaN → `SEEK_AMOUNT_UNKNOWN` (new line, asks for an amount); explicit 0 → resume; else rounded seconds. `SeekAmountTest`. |
| 8 | STT blank-result line | `lastListenWasServerError` was copied from `wasTransientError`, whose set excludes the server family and is forced false on retries — so it was always false and STT 11 spoke "ไม่ได้ยินเลย". | Set from `lastListenErrorClass == ServerError`, the signal that was already maintained. |
| 9 | `YoutubeVerify.normalize` | v1.4.3 put zero-width chars in the whitespace class, so `กรรมกร\u200Bข่าว` became `กรรมกร ข่าว` and did not match. | Strip `[\u200B-\u200D\uFEFF\u00AD]` first, then collapse `[\s\p{Z}]+`. Test pins the ZWSP-inside-a-word case; the test file now spells every invisible character as an escape. |
| 10 | nudge clock | Deadlines on `System.currentTimeMillis()`, ticks on `Handler.postDelayed` (uptime); `NudgeDecider` documents uptime. A suspend between ticks could expire the window with no CPU time spent. | All nudge timing on `SystemClock.uptimeMillis()`. (Plausible, not field-proven; the change is what the decider's contract already asked for.) |

## What this does NOT change

* Verification is still title-based. The altitude review suggested verifying by the video
  id if YouTube's session metadata carries it (art URI / media URI); nobody has looked, so
  that starts as a diagnostic, not a fix.
* `playContinue` on a non-YouTube target still returns `Success` after a bare app launch
  (silent, app opened paused) — verified, below the cut, rare path ("เล่น Spotify ต่อ").
* Dedupe key for `youtube_play` still uses the raw `video_id` — verified, below the cut.
* Cleanup items (reason strings → enum, settings sync copy-paste, 2–3 `getActiveSessions`
  IPCs per tick) are real and untouched: not error paths, stabilization sprint.

## What the next log should show

* `nudge→cancelled(newInteraction)` on an entry whenever the rider spoke again inside a
  launch window — and NO `repauseForeign[com.moto.voice]` anywhere.
* `openYoutube→launchFailed` / `playContinue→launchFailed` with a spoken line, never an `ok`
  entry that went silent.
* `launchBlocked(sessionLost)` only on entries whose `mediaActualTitle` is not the
  `mediaPriorTitle`.
* `SEEK_AMOUNT_UNKNOWN` if the workflow ever sends `seek` without `frequency` (then fix the
  prompt).

Rider validates — Claude cannot run acceptance. Two clean rounds before merge.
