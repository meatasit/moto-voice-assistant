# v1.4.5 — start YouTube without a window: the locked cold launch

Branch: `fix-locked-cold-mediabrowser-v1.4.5` (on `main`, v1.4.4) · versionCode 54 · versionName 1.4.5

## The proof

Field log `moto_voice_debug_1789561893967.json` contains the cleanest evidence yet for the
mechanism v1.4.2 proposed. The **same video id**, the **same lock state**, 40 seconds apart:

```
19:46:28  "เปิดเพลงสากลชิวๆ"          keyguardSecure=true screenLocked=true screenInteractive=false
          openYoutube:eoXtKw_bW_s;link→webTargeted;launch→fullScreenIntent;nudge→launchBlocked(noSession)
          fsiTrampolineRan=true  fsiTrampolineLaunchOk=true  finishReason=launch_blocked

19:47:07  "เปิด YouTube เพลงสากลชิวๆ"  keyguardSecure=true screenLocked=true screenInteractive=false
          openYoutube:eoXtKw_bW_s;link→webTargeted;launch→fullScreenIntent;
          nudge→sessionSeen(none);nudge→play#1;nudge→confirmed
          playbackState=playing  mediaActualTitle == mediaExpectedTitle  finishReason=ok
```

Nothing about permissions, the network or the intent form differs between the two. The
full-screen intent was honored both times (`fsiTrampolineLaunchOk=true`). The only thing that
changed is that the first attempt left YouTube's process warm, and the second attempt landed
in it.

That is the v1.4.2 mechanism, now reproduced within one log: an Activity started from the FSI
trampoline is created **behind the secure keyguard and never becomes visible**. YouTube begins
playback when its player is visible; unseen, it never plays and never registers a MediaSession
— which is the only thing the nudge can verify. The rider's own report closes the loop:
*"พอลองปลดหน้าจอก็เจอ youtube เปิดอยู่"* — the Activity was there the whole time, waiting for a
window.

The rider also said it used to work. It still does, in the two shapes the logs have always
shown landing: **warm** switches (a live playback service takes the new video with no UI) and
**unlocked** launches. Only locked + cold is broken, and it is broken every time.

## The fix — bind to the service instead of starting an Activity

Every recent field entry reports `mediaBrowserAvail=yt=true`: the YouTube app publishes a
`MediaBrowserService`. That is the interface Android Auto and the system assistant use to start
playback headlessly. It **binds to a Service**, so there is no Activity, no window, and nothing
for the keyguard to block.

New [`YoutubeMediaBrowser`](app/src/main/java/com/moto/voice/media/YoutubeMediaBrowser.kt):
resolve the service, connect, take the session token, build a `MediaController`, dispatch
`playFromSearch`. `MediaOrchestrator.openYoutube` takes that path **first** — and only —
when `shouldTryMediaBrowser(enabled, locked, priorTitle)` holds: the setting is on, the screen
is locked, and `priorTitle == null` (cold). Warm switches and unlocked launches never reach it
and keep the deep link, which can name an exact video id.

Going headless-first rather than as a rescue after the 15 s cold window also removes the stray
Activity entirely, which is the second half of the rider's complaint.

### Never worse than v1.4.4

Every failure returns a reason that lands in `mediaOperations`, and the deep link then runs
exactly as before: `browser→noService`, `browser→connectRefused` (YouTube's `onGetRoot` may
reject an unknown caller — normal for media apps), `browser→connectTimeout` (3 s ceiling),
`browser→noToken`, `browser→noController`, `browser→playThrew`.

And if the headless start dispatches but produces nothing, the nudge fires the deep link
**once** at `REFIRE_STILL_PRIOR_MS`, then extends the window to a full cold start. The two
paths are complementary rather than redundant: the deep link then runs against a YouTube the
browser attempt has already warmed, which the twin entries above show is the state it lands in.
The fallback is suppressed while the session is PLAYING or BUFFERING, so it can never restart
something that is already working.

On a phone without notification-listener access the nudge now falls back to the controller the
browser handed us. Without that, `controllerFor()` is null by definition, the nudge would read
a perfectly good headless start as "no session", and the fallback would fire a deep link on top
of music that had already begun.

## The trade, stated plainly

`playFromSearch` takes a **search string, not a video id** — YouTube picks. We search the full
video title (`browserSearchTerm`, which prefers `expectedTitle` over the rider's phrasing) since
that is the most specific string we hold, and the existing title verification judges the result
exactly as it judges a deep link. Nothing on this path reports success on its own.

If it plays something else, the rider hears that video plus the honest `SWITCH_NOT_LANDED`
line. That is a real regression risk against today's *silence*, which is why
`AppSettings.youtubeMediaBrowser` (Settings → "เปิดเพลงได้ตอนจอล็อค", default ON) turns the
whole path off and restores v1.4.4 byte for byte, with no new build.

## What this does NOT change

* `playContinue`'s refire hits the identical wall — it reaches `fireYoutubeIntent` only when
  YouTube has no session, which on a locked phone *is* the cold case. Left alone deliberately:
  the sprint rule is one proven symptom per fix, and no field log shows it yet. Obvious next
  candidate if this lands.
* Verification is still title-based. Nobody has checked whether YouTube's session metadata
  carries the video id.
* The earcon routing problem (`Earcon.scoActive`) is untouched.

## What the next log should show

1. `browser→playFromSearch` on locked cold launches — and whether `nudge→confirmed` follows.
2. If not: which reason fired. `browser→connectRefused` would mean YouTube rejects us and this
   whole approach is dead; say so and revert rather than tuning it.
3. `browser→fallbackDeeplink` frequency — how often the headless start dispatches but does
   nothing.
4. `mediaActualTitle` vs `mediaExpectedTitle` on browser launches — does searching the full
   title land the right video, or does YouTube substitute a mix?

## Validation status

Unit tests cover the pure gate (`MediaBrowserGateTest`): the locked+cold case is the only one
that takes the new path, and what it searches for. The connect / bind / `playFromSearch` half
needs a real device and belongs to the Acceptance Suite.

**Acceptance was NOT run — no device access from the sandbox, and the Android SDK is not
reachable from this environment either, so this branch has not been compiled locally; CI is the
first compile.** Per `CLAUDE.md`, this change is **awaiting rider validation**: scenarios A–I on
the S24 Ultra + V11X, two clean rounds, with the debug-log JSON attached here before merge.
