# v1.3.36 — the four bugs from field log `moto_voice_debug_1786104958601`

Branch: `fix-switch-and-earcon-v1.3.36` · versionCode 42 · versionName 1.3.36

24 interactions over 40 minutes. The rider dictated his own bug list into the log while
riding, which is why the STT rows read like a changelog:

* `"หอพักที่เจอตอนนี้ก็คือไม่เปลี่ยนเพลงไม่เปลี่ยนรายการให้นะ"` (บั๊กที่เจอ…)
* `"บักที่ 2 คือบอกว่าเปิดไม่ได้ตอนจองล็อค"`
* `"รักที่ 3 คือไม่เปลี่ยนรายการให้"` (บั๊ก…)
* `"นัดต่อไปคือเสียงของ AI มี 2 เสียง"` (บั๊ก…)
* `"สัญญาณให้พูดครั้งแรกไม่ได้ยินอีกแล้วยังไม่ได้"`

## 1. Locked switches never landed — 11 in a row

Every `openYoutube` between 1786101251443 and 1786101948805 ended
`nudge→launchBlocked(stillPrior)` with `mediaActualTitle` frozen on the same
`crazy chill song playlist - lauv,lany,keshi,austin.ect 💕` for twelve minutes — the clip
opened successfully at 1786101226948. Requests for เพลงไทย, เรื่องเล่าเช้านี้, ช่องนายอาร์ม and
several "เปลี่ยนรายการ" all bounced off it.

`fsiTrampolineRan=true` and `fsiTrampolineLaunchOk=true` on all eleven: the full-screen
intent was honored, the trampoline ran, `startActivity` did not throw. The intent simply
never navigated.

**This also disproves half of the v1.3.26 fix.** Each of the eleven shows
`nudge→refireSwitch` — 22 deliveries of the same `vnd.youtube:` VIEW intent, zero landings.
Re-sending an identical `FLAG_ACTIVITY_NEW_TASK` intent to a task that is already running
is the no-op: Android brings the existing task forward without delivering it.

So the re-fire now **escalates instead of repeating** — it adds `FLAG_ACTIVITY_CLEAR_TASK`,
tearing down YouTube's task and starting it fresh at the requested video. The first attempt
is unchanged (it is fast and works whenever YouTube is cold or idle); only the retry that
was proven useless behaves differently. Logged as `nudge→refireSwitch(clearTask)` so the
next field log says which one ran.

## 2. "เปิดไม่ได้ตอนจอล็อค ลองปลดล็อคก่อน" when the launch had actually fired

Entries 1786100870242 and 1786100948949: locked, `mediaCtrlPkgMiss=none` (nothing else
playing at all), `fsiTrampolineLaunchOk=true` — and the rider was told to unlock. Unlocking
would have changed nothing; YouTube was cold and had not registered a session inside the
9.8s window.

Two changes:

* `blockedLineFor(reason, locked, fsiHonored)` — a pure decision, unit-tested. The unlock
  advice is now reserved for the case it describes (locked **and** no FSI path taken,
  i.e. acceptance scenario C-denied). Everything else gets the honest
  `เปิดยูทูบไม่สำเร็จค่ะ ลองสั่งใหม่อีกครั้งนะคะ`.
* `pollWindowMsFor(priorTitle)` — a cold target (nothing was playing, so no prior session)
  waits 15s instead of 9s. Warm switches keep the tighter window so a genuine failure is
  still reported promptly. Three `noSession` blocks across two logs sat inside the old
  window with a launch that had succeeded.

## 3. The assistant spoke in two different voices

Eight of the 24 interactions fell back to the Android engine after a real Azure failure;
the rest used Azure. Same ride, two voices.

The failures are 93–536 ms — nowhere near the 2 s `SYNTH_TIMEOUT_MS`, so they are not
timeouts, but the log only ever said `"synth failed after 116ms"`: the exception went to
logcat and was dropped. `synthesizeToFile` throws `IllegalStateException("HTTP <code>")`
for a bad response and `IOException` subclasses for the network, so the class name alone
separates a DNS miss from a 401/429. The reason string now carries it.

**No behaviour change here — this is instrumentation.** The next log should say whether the
fix is credentials, quota, or cellular, and that decides what v1.3.37 does about it.

Also fixed while in there: `azureError` was written on failure and never cleared, so a later
speak on the same entry left `engineChoiceReason=azure_used` next to a stale error. And the
nudge's out-of-interaction announcement was stamping the *finished* interaction's entry,
producing rows like `cacheHit=true, ttsSynthMs=1, azureError="synth failed after 599ms"` —
impossible on its face, and it cost a debugging session. It now speaks via
`ThaiTTS.speakUnlogged`.

## 4. No "start speaking" cue on the first press — and the two cues sounded alike

**First press.** Both recent logs open with `scoColdConnect=true` + `readyEarconRoute=phone`
+ `finishReason=no_speech`: the cue went to the phone speaker, the helmeted rider heard
nothing, said nothing, and the interaction died. Every warm press in both logs shows
`readyEarconRoute=sco`, so the 400 ms poll budget added in v1.3.33 is sufficient warm and
insufficient cold — a cold connect already burns `SCO_COLD_SETTLE_MS` (800 ms, observed
`scoTimeMs≈806`) before polling starts. The cold path now gets 2 s. It costs nothing when
the route settles early (the poll returns the moment it flips) and only ever applies to the
first press after process start.

**Two cues, clearly separated.** Rider, 7 Aug: *"แยก 2 เสียงให้ชัดเจน ระหว่างเริ่มรอฟังกับหยุดฟัง
เพราะตอนนี้ ระหว่าง AI พูด ไม่รู้ว่าพูดได้ตอนไหน"*. The old language had three tones that were
hard to separate on a helmet: `ready` was one beep, `endInteraction` is one tone, and
`answerListen` was two beeps at the *same* pitch (which reads as one longer beep through
wind noise).

Reduced to the two states the rider acts on:

| meaning | sound |
| --- | --- |
| **speak now** (mic open) | two tones going **UP** — DTMF_1 (697+1209 Hz) → DTMF_9 (852+1477 Hz), 210 ms |
| **stopped listening** | one short low tone — unchanged |

`ready` and `answerListen` now share the rising motif: the required action is identical in
both cases, and one sound to recognise beats two to tell apart at 90 km/h. **This merges a
distinction the v1.3.9 spec drew** (button-required vs not) — flagged here so it can be
reverted if riding proves it matters.

The end tone is deliberately NOT a descending motif: v1.3.13 shipped that and the rider
rejected it (*"แย่กว่าเดิม"*), reverted in v1.3.14.

## Tests

`LaunchBlockedContractTest` (7, new) — the unlock line is never spoken when the FSI was
honored; `stillPrior` always says "switch didn't land" regardless of lock/FSI; cold window
exceeds warm and exceeds the 9 s that failed in the field.

Earcon tone choice is not JVM-testable (ToneGenerator) — it is scenario H, by ear.

## Also

`CLAUDE.md` said scenarios A–G, `ACCEPTANCE.md`'s rules line said A–H, the file has A–I.
Both now say A–I.

## Acceptance

Claude has no device access — **awaiting rider validation.** The rider agreed to a scoped
round rather than the full A–I:

1. **C-switch** — the headline fix. Open clip A, lock, then ask for B, C, D back to back.
   Each must switch. In the log, `nudge→refireSwitch(clearTask)` followed by
   `nudge→confirmed` is the fix working; another run of `launchBlocked(stillPrior)` means
   CLEAR_TASK is not enough either and the next move is the `https://` watch URL.
2. **First press of a cold app** — force-stop Moto Voice, press BVRA, and confirm the
   rising two-tone is audible **in the helmet**. Log must show `readyEarconRoute=sco` with
   `scoColdConnect=true`.
3. **By ear** — start cue vs stop cue are now obviously different mid-ride.
4. **A (cold YouTube open)** — should now succeed where it previously said "ปลดล็อคก่อน";
   if it still fails, the line must be "เปิดยูทูบไม่สำเร็จค่ะ ลองสั่งใหม่อีกครั้งนะคะ".
5. Send the log back — item 3's Azure detail is the whole point of this round for the
   two-voices bug.
