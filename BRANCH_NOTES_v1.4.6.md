# v1.4.6 — three findings from field log 1789637279880

Branch: `fix-log-1789637279880-v1.4.6` · versionCode 55 · versionName 1.4.6 · **awaiting rider validation**

Rider's verdict on v1.4.5: **"รอบนี้ถือว่าใช้งานได้ราบลื่น"**. The log agrees — the numbers
below are the point of this document as much as the fixes are.

## v1.4.5 scorecard

`appVersion: "1.4.5 (54)"` on all ten entries, so for the first time this is checkable at all.

| | v1.4.5 fix | this log |
|---|---|---|
| ✅ | **§4** version stamp | present on every entry — §4 did its whole job by making this table possible |
| ✅ | **§5** `followup_stt` | three chats, three `followup_stt 11`, none of them reading as a failed interaction |
| ✅ | **§5b** polite-tail guard | "เปิดเพลงให้ฟังหน่อย" has a payload and correctly still reached the webhook (`slotFilled: false`) |
| ❓ | **§2** `spotify_play` | rider never asked for Spotify. **Untested** |
| ❓ | **§1** `refireNoSession` | **never fired — and nothing needed it.** See below |
| ❓ | **§5b** positive half | no bare "เปิด YouTube ให้ฟังหน่อย" this session. **Untested** |

**Five of five `youtube_play` commands with a video id ended `ok` and `confirmed`. Zero
`launch_blocked`.** The previous log was six blocked out of seven.

§1 deserves an honest note: the one cold locked launch in this log (entry 1789608398866,
wifi) landed on its first delivery — `sessionSeen(none);confirmed`. The noSession failure
never reproduced, so the escalation written for it is still unproven. It is not evidence the
fix works; it is evidence the situation did not arise.

## 1 — every warm switch pays 2.5 s for a delivery that does nothing

All four warm switches in this log are the same shape, and all four **landed on the
escalation, not on the first delivery**:

```
openYoutube:VID;link→webTargeted;launch→fullScreenIntent;
nudge→sessionSeen(playing);nudge→refireSwitch(clearTask);
link→webTargeted;launch→fullScreenIntent;nudge→play#1;nudge→confirmed
```

Entries 1789608495874, 1789608604885, 1789610031855, 1789610080257. First delivery: 0 for 4
here, 0 for 11 in field log 1786104958601 — a plain `NEW_TASK` intent handed to a task that
is already running is brought forward without the intent being delivered. v1.3.36 wrote that
down; we just kept paying for it once per switch.

`priorTitle` already tells us at fire time which case we are in, so the first delivery now
carries `CLEAR_TASK` when YouTube has a live session. Saves `REFIRE_STILL_PRIOR_MS` plus a
wasted trampoline and FSI notification on every switch. **A cold target keeps the plain
intent** — there is nothing to clear, and tearing down a launch that is merely slow is
precisely the mistake `REFIRE_NO_SESSION_MS` is written to avoid. The stillPrior escalation
stays armed (`refired` is untouched by the first fire), so a switch that somehow still misses
behaves exactly as it does today.

**This is the one change in this branch that touches a path the rider just called smooth.**
Its worst case is one wasted CLEAR_TASK; its best case is ~2.5 s off every switch. Reject
this commit alone if you would rather not spend the risk — the other two stand on their own.

## 2 — "เปิดรายการถัดไปของไอ้อาร์" went to the cloud for an answer we were holding

Entry 1789610009315:

```
sttFinal: "เปิดรายการถัดไปของไอ้อาร์"
webhookResponse: action=youtube_play, video_id=null, videos=[]
                 speak="หาวิดีโอไม่เจอ เปิดหน้าค้นหาให้แทนนะคะ"
webhookTimeMs: 8015        mediaCtrlUsed: false
```

`LocalIntercept.matchesAsPhrase` accepts a pattern only at index 0 or **after a space**, and
Thai does not put spaces between words. "ถัดไป" sits at index 10 preceded by "ร", so
`NextVideo` never matched. The sentence went to the cloud, which searched for the phrase
literally, found nothing, and spent 8 s doing it.

Meanwhile `MediaSessionMemory` was still holding that exact channel's list from entry
1789608604885 twenty-three minutes earlier, and `nextVideo()` would have answered
**"หนี้ทางเทคนิค (Technical Debt)"** — which is what the rider asked for.

Fixed with a narrow regex rather than by loosening `matchesAsPhrase`: a NOUN
(อัน/คลิป/รายการ/ตอน/เพลง/วิดีโอ) immediately followed by ถัดไป/ต่อไป. Requiring the noun is
what keeps **"เล่นต่อไป"** out — that is a resume, it belongs to `PLAY_CONTINUE_REGEX`, and
reading it as "skip to the next video" would be the worst possible misfire. Loosening
`matchesAsPhrase` would have taken it. `NextItemInterceptTest` pins both halves.

(The rider heard `YOUTUBE_NOT_FOUND`, not the workflow's "เปิดหน้าค้นหาให้แทนนะคะ" — the app
deliberately refuses to dump a rider into YouTube search. That part was correct. Worth
telling n8n to stop promising it.)

## 3 — the follow-up window has never worked, and the log can't say why

Spec v1.3.8 B2 opens a passive 4 s mic after a conversational reply. Across both logs:

| log | chats that opened a follow-up | dropped with error 11 | `followupUsed` |
|---|---|---|---|
| 1789561893967 | 5 | 5 | false ×5 |
| 1789637279880 | 3 | 3 | false ×3 |

**Eight for eight.** The rider has never once been able to continue a conversation without
pressing the button again — silently, because v1.4.4's blank-result line and v1.4.5's
`followup_stt` label both made it *look* correct rather than making it *work*.

No fix here: nothing in the export says whether the recognizer dropped instantly or held the
mic for the full window, and those point at different causes (ห้ามแก้ก่อนพิสูจน์). So the
stamp now carries the elapsed time — `followup_stt 11@85ms` vs `followup_stt 11@4100ms` — and
the next log decides it. The same stamp makes the pipeline's "errored inside 800 ms so it
never really listened" transient rule auditable from the export instead of inferred.

## Tests

`NextItemInterceptTest`, `WarmSwitchRestartTest`, `SttErrorStampTest`. All pure JVM.

**Claude could not run them** — no Android SDK in the sandbox and Gradle cannot reach Google
Maven from it, so `testDebugUnitTest` does not execute locally; CI runs it on this PR. The
`NEXT_ITEM_REGEX` — the one change that could steal a working command — was hand-verified
18/18 against every sentence in this log plus the resume forms it must not take.

## What the next log should show

* `appVersion: "1.4.6 (55)"`.
* A warm switch as `openYoutube:VID;link→webTargeted;launch→fullScreenIntent;nudge→sessionSeen;
  nudge→play#1;nudge→confirmed` — **no `refireSwitch`**, and `actionTimeMs` down by roughly
  the re-fire delay. A `refireSwitch` still appearing means the first CLEAR_TASK missed and
  finding 1 should be reverted.
* "เปิด(รายการ|คลิป|เพลง)ถัดไป…" answered locally: no `webhookRequest`, straight to
  `openYoutube` on the remembered list.
* `followup_stt 11@<n>ms` — the number is the finding.

**Still owed** (v1.4.5 §1/§2, v1.4.4): `nudge→refireNoSession(clearTask)` on a locked cold
open that fails, a `spotify_play`, `nudge→cancelled(newInteraction)`,
`openYoutube→launchFailed`, `launchBlocked(sessionLost)`, `SEEK_AMOUNT_UNKNOWN`.

Rider validates — Claude cannot run acceptance. ACCEPTANCE.md A–I on the S24 Ultra + Vimoto
V11X, **two clean rounds in a row** before merge, debug-log JSON attached.
