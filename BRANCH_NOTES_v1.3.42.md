# v1.3.42 — open YouTube with the https App Link instead of `vnd.youtube:`

Branch: `fix-wrong-video-and-stt-v1.3.41` (stacked on v1.3.41) · versionCode 48 · versionName 1.3.42

## Why

"เปลี่ยนคลิปไม่ทัน" has a mechanism, and it isn't slowness.

`vnd.youtube:VIDEO_ID` is a private scheme. When YouTube's task already exists, a VIEW
intent with `NEW_TASK` **brings that task forward without delivering the intent** — YouTube
never learns a new video was requested, and `startActivity` returns cleanly so we think it
worked. Field log 1786104958601: eleven consecutive locked switches, 22 deliveries counting
re-fires, `mediaActualTitle` frozen on one playlist for twelve minutes.

It only happens with the screen **locked**. Log 1786763666528, same build, screen unlocked:
two switches back to back, both landed on the first delivery, no re-fire needed
(`openYoutube;nudge→confirmed`).

v1.3.36's answer was `CLEAR_TASK` — kill YouTube's task and restart it at the requested
video. That works, but it is a full cold app start, slow enough that the result arrives
after we have given up (log 1786178611552: declared `stillPrior`, and the *next* interaction
found the requested video playing). Worse, the late arrival then lands on top of whatever
command came next — the wrong-video bug fixed in v1.3.41.

## What changed

Videos now open with `https://www.youtube.com/watch?v=…`, package-targeted at
`com.google.android.youtube`. An App Link goes through the normal intent-filter path rather
than a private scheme, so it has a real chance of reaching the running activity as a new
intent instead of being swallowed.

**This has never been tried.** The URL has been in `buildYoutubeIntent` since the first
commit, but only as the fallback for devices without the YouTube app — `resolveActivity` on
`vnd.youtube:` always succeeds on this phone, so that line has never executed once.

Safe by construction:

* package-targeted → no app-chooser dialog can appear
* used only if YouTube claims it (`resolveActivity`), else falls through to exactly the
  previous behaviour
* worst case equals today's failure mode, not a worse one

**New setting: "เปิด YouTube ด้วยลิงก์เว็บ", default ON.** Default on because the behaviour it
replaces is known-broken; the switch is there so it can be turned off in one tap without
waiting for a build if it misbehaves.

`CLEAR_TASK` stays for now as the re-fire escalation. If the App Link lands reliably, the
re-fire should stop firing at all — that is the signal to remove it, and with it the
late-arrival problem.

## Acceptance — awaiting rider validation

The whole point is one scenario, so do it properly:

1. Open a video. **Lock the screen.** Ask for a different one. Then a third. Then a fourth.
2. Every switch should land. In the log, `mediaOperations` should read
   `openYoutube:…;launch→fullScreenIntent;nudge→confirmed` — **without**
   `nudge→refireSwitch(clearTask)`. A re-fire appearing means the App Link was swallowed too
   and this idea is dead.
3. No app-chooser dialog, ever. If one appears, switch the setting off and say so.
