# v1.4.7 — the locked-screen bug, finally understood

Branch: `fix-locked-nosession-v1.4.7` · versionCode 56 · versionName 1.4.7 · **awaiting rider validation**

Rider, on field log 1789649814596:

> "เปิด youtube แล้วเงียบ **พอปลดล๊อคหน้าจอ youtube ก็เล่นเอง**"

That second clause is the most useful sentence in this whole sprint, and it says the v1.4.5
fix was aimed at the wrong mechanism.

## First: this log is v1.4.5, not v1.4.6

`appVersion: "1.4.5 (54)"` on all six entries. Build 126 (v1.4.6) had been published ~3 minutes
before the first entry but was not installed, so **nothing in v1.4.6 is exercised here** — the
warm-switch CLEAR_TASK change, the next-item intercept and the `@<n>ms` STT stamp are all still
unproven. (This is precisely the question v1.4.5 §4 added the version stamp to answer, and it
answered it in one line.)

## What the log shows

Three `youtube_play`, three `launch_blocked`, all identical:

```
finishReason: launch_blocked      keyguardSecure: true      screenInteractive: false
mediaCtrlPkgMiss: "none"          netTransport: cellular    screenLocked: true
ops: openYoutube:VID;link→webTargeted;launch→fullScreenIntent;
     nudge→refireNoSession(clearTask);link→webTargeted;launch→fullScreenIntent;
     nudge→launchBlocked(noSession)
```

**v1.4.5's `refireNoSession` escalation ran for the first time — and failed 3 for 3.** It did
exactly what it was built to do: waited 6 s, tore YouTube's task down, fired a second honored
full-screen intent. YouTube still never registered a session.

## Why it could never have worked

The rider's second clause. He unlocked, and YouTube **started playing by itself**. So:

* the deep link had been delivered — twice,
* YouTube was open with the right video loaded,
* it was simply not playing, and had registered no MediaSession,
* and the instant its Activity could resume, it played.

YouTube does not start its player behind a secure keyguard. `LockLaunchActivity` declares
`showWhenLocked` so *our* trampoline shows over the lock screen, but YouTube's own activity
does not, and `requestDismissKeyguard` is a no-op against a secure keyguard. Re-delivering the
link cannot change any of that — which is why re-firing it a third, fourth or tenth time would
fail identically. **v1.4.5 §1 treated a resume problem as a delivery problem.**

## What changes

**1. Stop paying for the escalation where it is disproven.** `shouldRefireNoSession` now
returns false behind a secure keyguard. It cost a second wasted trampoline and extended the
window by `POLL_WINDOW_COLD_MS`, so the rider sat in silence ~21.8 s before being told
anything, instead of ~15.8 s. The gate reads the **live** keyguard, so if he unlocks
mid-window the retry is back in play; and the escalation is kept for the unlocked /
non-secure case, where it has never actually been tried.

**2. Say the true thing.** He was hearing `LAUNCH_FAILED_NO_SESSION` —
"เปิดยูทูบไม่สำเร็จค่ะ ลองสั่งใหม่อีกครั้งนะคะ". Both halves are wrong: it *did* open, and saying
it again provably cannot work (three for three). The other candidate,
`LAUNCH_BLOCKED_LOCKED` ("เปิดไม่ได้ตอนจอล็อค"), is also false for the same reason.

So the two locked cases are now two different facts with two different lines:

| situation | line |
|---|---|
| FSI never ran — the keyguard stopped the *intent*, nothing is open | `LAUNCH_BLOCKED_LOCKED` — "เปิดไม่ได้ตอนจอล็อค" (true) |
| FSI honored, secure keyguard, no session — **open and waiting** | **`MEDIA_WAITING_FOR_UNLOCK`** — "เปิดยูทูบไว้ให้แล้ว แต่ยังเล่นไม่ได้ตอนจอล็อคค่ะ ปลดล็อคจอแล้วจะเล่นเลยนะคะ" |

This **reverses a v1.3.36 decision**, and that deserves to be flagged rather than buried:
v1.3.36 saw `fsiTrampolineLaunchOk=true` on a failed locked open and *inferred* "the launch
worked, so the session was merely slow, so unlocking would not have helped." The inference was
wrong. It was right that the launch fired — that part stands, and is why we do not say
"เปิดไม่ได้" — but wrong that the keyguard was innocent. An observation beats an inference.

v1.3.36's actual complaint is still honored: it was about being told to unlock while audio was
audibly playing. That is `stillPrior` / `wrongVideo` / `sessionLost`, and all three keep their
own lines, unconditionally, with a test pinning it.

**3. Measure the only route left.** `mediaBrowserAvail: yt=true` has been in every log since
v1.4.2 and nobody ever checked whether YouTube's MediaBrowserService will talk to *us* — it is
built for Android Auto and may refuse an unknown caller. It is also the only way to reach
YouTube's session without its Activity resuming, i.e. the only candidate real fix left.

So `mediaBrowserConnect` now records a probe on the noSession path: `connected(token)` /
`connected(noToken)` / `refused` / `noService` / `connectThrew:…`. It connects, records,
disconnects. **Nothing is dispatched through it.** If the next log says `refused`, the lead is
closed for good and the honest line above is the whole answer; if it says `connected(token)`,
v1.4.8 has a real fix to build. Framework `android.media.browse.MediaBrowser`, so no new
dependency.

**4. `mediaBlockedKeyguard`** — the live `locked=…,secure=…` at the moment we gave up, rather
than `screenLocked` from 16 s earlier at fire time. It is the state the spoken line is advice
about, and it distinguishes "still locked" from "he unlocked mid-window and it failed anyway".

## Tests

`LaunchBlockedContractTest` (rewritten around the reversal, including that the two locked cases
must not share a line and that the audible cases never send him to unlock) and
`NoSessionRefireTest` (the disproof, and that unlocking re-arms the retry).

**Claude could not run them** — no Android SDK in the sandbox and Gradle cannot reach Google
Maven; CI runs `testDebugUnitTest` on this PR.

## What the next log should show

* `appVersion: "1.4.7 (56)"` — and with it, finally, v1.4.6's three untested changes.
* On a locked cold open that fails: **no** `refireNoSession`, a give-up at ~15.8 s instead of
  ~21.8 s, `mediaBlockedKeyguard: "locked=true,secure=true"`, and the rider hearing
  "ปลดล็อคจอแล้วจะเล่นเลย" instead of "ลองสั่งใหม่".
* **`mediaBrowserConnect` — this is the one that decides the next version.**

Rider validates — Claude cannot run acceptance. ACCEPTANCE.md A–I on the S24 Ultra + Vimoto
V11X, **two clean rounds in a row** before merge, debug-log JSON attached.
