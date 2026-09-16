# v1.4.2 — the launch that "failed" was playing; full-title verify + honest `sessionLost`

Branch: `diag-cold-launch-v1.4.2` · versionCode 51 · versionName 1.4.2

## The Wi‑Fi / cast-prompt hypothesis is dead

v1.4.1 was built to test "YouTube stuck on its *Connect your devices* prompt on home Wi‑Fi".
The rider's answer the same morning: *"ผมขี่มอไซ ตอนนั้นไม่มี wifi อยู่แล้ว"* — the six failures in
log `1789483749397` happened on the road, on mobile data. Hypothesis withdrawn.

## What still stands, and the mechanism that fits it

Cold launch (no YouTube session) while locked → `noSession`, 10/10 across two logs. Warm
switch → lands, every time. Unlocked → lands (log 1786763666528).

The mechanism that fits all three: an activity started from the FSI trampoline is created
**behind the secure keyguard** and never becomes visible. YouTube starts playback when its
player becomes visible; unseen, it creates no MediaSession. A *warm* YouTube already has its
playback service alive, so the new video is delivered to that service and plays with no UI
needed — which is also why the `CLEAR_TASK` restart works: the task dies, the service does not.
(Log 1789440952407 supports the "starts once seen" half: the 09:12 cold launch that was declared
`noSession` was found *playing* at 09:16, right after the rider would have looked at the phone.)

`LockLaunchActivity`'s header comment — *"audio then plays even if YouTube's UI stays behind a
secure keyguard"* — has never been demonstrated for a cold start in any field log.

## New evidence — field log `1789518388540`, entry 1789518322022 (16 Sep, phone mic, locked)

```
mediaOperations:   openYoutube:E0Y8OEo_zOc;launch→fullScreenIntent;nudge→launchBlocked(noSession)
mediaExpectedTitle: Pop Music 2025 - Top Pop Songs 2025 - Billboard Top 100 🎧🔥
mediaActualTitle:   Pop Music 2025 - Top Pop Songs 2025 - Billboard Top 100 🎧🔥 Justin Bieber Billie Eilish Miley Cyrus
playbackState:      playing
```

`mediaActualTitle` / `playbackState` are only written when the poll HAS a controller. So the
cold launch **did** start the requested video mid-window. Two things then went wrong:

1. **The verifier rejected the match.** The workflow cuts `video_title` to 60 chars for TTS;
   the session's title is 101. 60/101 = 59% < the 70% prefix rule (which exists to keep two
   episodes of one series apart — log 1784078976959 — and must stay). Verdict `SWITCHED` with
   an expected title → keep polling instead of confirming.
2. **The session then disappeared** before the window closed, so the final verdict was
   `noSession` — "couldn't open" — spoken to a rider who unlocked the phone and found YouTube
   open on that very video. His question: *"มันเปิดแล้วไม่ autoplay รึเปล่า"*. Yes: it started,
   and stopped. YouTube pauses when its player is not visible; behind the keyguard it never is.

## What changed

### Fix — verify against the full title (n8n v3.9 + app)
* Workflow `Attach Videos` now also sends `video_title_full` and `videos[].title_full`
  (untruncated). `video_title` stays 60 chars for speech. Old builds ignore the new fields.
* `WebhookResponse.Video.verifyTitle` prefers the full title; both `openYoutube` call sites
  pass it as `expectedTitle`. `FullTitleVerifyTest` pins the exact titles from the log.
  Had this been in place, entry 1789518322022 would have confirmed at the first playing tick.

### Honest line — `sessionLost`
* The nudge remembers whether it ever saw the target's session (`nudge→sessionSeen(<state>)`
  in `mediaOperations`). Window end with no controller is now `sessionLost` when one was
  seen, `noSession` when none ever was. `sessionLost` speaks the new
  `MEDIA_STOPPED_AFTER_OPEN`: "เปิดยูทูบแล้วแต่วิดีโอหยุดเองค่ะ ลองปลดล็อคจอดูนะคะ".

### Diagnostics (unchanged from the first cut of this note)

* `DebugEntry.keyguardSecure`, `screenInteractive` — lock type and screen state at launch.
* `DebugEntry.mediaBrowserAvail` — whether YouTube / YouTube Music expose a
  `MediaBrowserService`. If either does, there is a UI-less way to start playback
  (`MediaBrowser.connect()` → `playFromSearch()`) that never touches the keyguard.
* `mediaOperations` now records which intent form fired: `link→webTargeted` /
  `link→vnd` / `link→webUntargeted` / `link→search` / `link→searchWeb`.
* **Manifest `<queries>`** for the two YouTube packages + the MediaBrowserService action.
  Needed for the probe above — and it is very likely a silent fix on its own: without it,
  Android 11+ hides YouTube from `resolveActivity()`, so every "prefer targeted" branch in
  `buildYoutubeIntent` has been falling through to the **untargeted** https fallback, and the
  v1.3.42 App Link change never actually ran. From this build the targeted forms resolve.
  Whether that changes anything on the road is exactly what the `link→…` op will show.

## What the next log should answer

1. `keyguardSecure=true` + `mediaPriorTitle=null` on every `noSession`? (mechanism confirmed)
2. Which `link→…` form fires now, and does the cold launch behave differently with it?
3. `mediaBrowserAvail` — is the UI-less route available on this phone?

Two questions only the rider can answer: when a cold launch "fails", does YouTube start
playing the moment the phone is unlocked? And is YouTube Premium background play on?

Rider validates — Claude cannot run acceptance.
