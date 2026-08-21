# v1.3.41 — three bugs from field log `moto_voice_debug_1787294052224`

Branch: `fix-wrong-video-and-stt-v1.3.41` · versionCode 47 · versionName 1.3.41

## 1. The assistant played the wrong thing and called it success

The rider's report, and the log agrees exactly:

| # | said | happened |
| --- | --- | --- |
| 1 | เปิดเรื่องเล่าเช้านี้ | ✅ worked |
| 2 | เปิดเพลงบรรเลงสากล | silence |
| 3 | เปิดรายการกรรมกรข่าว | **played the instrumental music from #2** |

```
ts 1787275646588  want: crazy chill song playlist…      → launchBlocked(stillPrior)
ts 1787275720201  want: ดนตรีเพราะๆ บรรเลง…              → launchBlocked(stillPrior)
ts 1787275893887  want: Live "กรรมกรข่าว คุยนอกจอ" 21 ส.ค.
                  got:  ดนตรีเพราะๆ บรรเลง…              → nudge→confirmed   ← wrong, and reported as success
```

Two failures compounding:

* The CLEAR_TASK restart from the music command **landed after its own window closed**. That
  is the same late-arrival behaviour v1.3.37 extended the window for; it still isn't always
  enough, and a late restart is now switching YouTube while a *later* command is being
  verified.
* `YoutubeVerify.Verdict.SWITCHED` only means *"the title moved away from the prior one"*.
  The nudge confirmed on any verdict except UNKNOWN, so that late arrival read as success
  for the command that happened to be in flight.

`playingDecision(verdict, expectedKnown, windowExhausted)` — pure, unit-tested — replaces
that rule:

| situation | decision |
| --- | --- |
| title matches the target | confirm |
| title changed, **no** target to check against ("อันต่อไป") | confirm |
| title changed to something else, time left | keep polling |
| title changed to something else, window over | **wrongVideo** |
| no title yet, window over | confirm (audio is playing) |

`wrongVideo` speaks the same line as a failed switch — *"ยังเปลี่ยนคลิปไม่ทันค่ะ ลองสั่งเปลี่ยน
อีกครั้งนะคะ"* — because something IS audible; "can't open" would contradict what he hears.

The rider now gets told the truth instead of silence plus a wrong video.

## 2. The first-press cue, finally addressed — but opt-in

*"เสียงสัญญาณให้พูดครั้งแรก ไม่เคยได้ยินเลยตอนนี้"*, and the log shows why. Every
`no_speech` entry in this file is a **first press** with `readyEarconRoute: "phone"` while
`scoState` is already `connected` — the cue went to the phone speaker, he heard nothing,
said nothing, and the interaction died. The press after it is always `sco`.

Two changes:

* Route poll budget 2s → **4s**. It costs nothing when the route settles early (the poll
  returns the instant it flips) and only ever runs long on a press that was going to miss.
* **New setting: "ส่งเสียงสัญญาณเข้าหูฟัง", default OFF.** This is v1.3.38's
  `STREAM_VOICE_CALL` change — the one that made the app unusable — brought back as
  something he can switch on while parked. The tone calls are guarded now (v1.3.40), so it
  can no longer take an interaction down either way. If it works, it's the real fix; if it
  doesn't, he flips it back and nothing is lost.

## 3. Two "didn't hear you" lines back to back

*"ประโยค ไม่ได้ยินเลยค่ะลองใหม่อีกครั้ง แล้วยังไม่ทันรอฟังก็พูดขึ้นมาว่า ยังไม่ได้ยิน อีกครั้ง"*.

The sequence: he never heard the start cue (bug 2), so he said nothing → *"ไม่ได้ยินค่ะ
พูดอีกครั้งนะคะ"* → the re-listen died instantly on `STT 11`
(`ERROR_SERVER_DISCONNECTED`) → *"ยังไม่ได้ยินค่ะ ลองใหม่อีกครั้งนะคะ"*. Two near-identical
sentences with no real chance to speak in between, and both blaming him for silence when
the second one was Google's recognizer dropping the connection.

The blank-result branch now checks whether the last listen failed on a recognizer error
rather than on silence, and says so: **"ระบบฟังเสียงขัดข้องค่ะ กดปุ่มลองใหม่นะคะ"** — different
wording, and it tells him the useful thing (press again, don't talk louder).

## Tests

`PlayingDecisionTest` (8).

## Acceptance — awaiting rider validation

1. **Three commands in a row**, helmet on, screen locked: a show, then music, then a
   different show. Each must play what was asked for, or say it couldn't — never play
   something else while reporting success.
2. **First press of a session** — is the cue audible in the helmet now that the budget is 4s?
   If not, go to Settings → **ส่งเสียงสัญญาณเข้าหูฟัง**, switch it on **while parked**, and
   press again. Report which of the two states works.
3. **Press and stay silent** — you should hear "ไม่ได้ยินค่ะ พูดอีกครั้งนะคะ", a real 6s gap,
   and then at most one closing line. If the recogniser drops, the closing line should be the
   new "ระบบฟังเสียงขัดข้อง" one, not a second "ยังไม่ได้ยิน".
