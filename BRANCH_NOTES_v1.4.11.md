# v1.4.11 — cold YouTube behind the keyguard: go in by the service door

Branch: `claude/epic-darwin-fjfedd` · versionCode 60 · versionName 1.4.11 · **awaiting rider validation**

Field log 1794225000029, `appVersion 1.4.10 (59)` on every entry. Rider: "เมื่อวานเย็นใช้งานไม่ได้เลย".

## Scorecard first

| | this log |
|---|---|
| ❌ cold `youtube_play` behind a secure keyguard | **5 for 5 `launchBlocked(noSession)`** (18:28–18:31): เพลงชิวๆ, เพลง 3 คน, โหนกระแส, เพลงสากล, and "กด Play เลย" |
| ✅ the trampoline | `fsiTrampolineRan=true`, `fsiTrampolineLaunchOk=true` every time — the deep link fired |
| ✅ nothing else in the way | `mediaCtrlPkgMiss: none` — no session from any app for the whole 15 s window |
| ✅ **the v1.4.7 probe** | `mediaBrowserConnect: connected(token)` — **5 for 5** |
| ❓ v1.4.10 `restartSettling` | not exercised: no warm switch happened (nothing ever started) |
| ❌ `playContinue` gate | entry 1794222694408: `refireNoSession(clearTask)` behind a secure keyguard that the gate was written to skip |

## The finding

This is the v1.4.5 / v1.4.7 shape, unchanged: an Activity started from the full-screen-intent
trampoline is created behind a secure keyguard and never becomes visible, YouTube only
starts its player once it is visible, no player means no `MediaSession`, and the nudge has
nothing to poll. Whether YouTube registers a session anyway is YouTube's call: the same
morning (field log 1794208929620, entry 1794187133692) the same shape landed once on
`sessionSeen(none);play#1..3;confirmed`. The evening ride got five zeros in a row.

v1.4.7 planted a probe for exactly this moment: *"if the next log says `connected(token)`,
v1.4.8 has a real fix to build."* Every entry in this log says `connected(token)`. YouTube
lets us bind to its MediaBrowserService — the interface Android Auto uses — and hands us a
session token. That path binds to a **Service**, not an Activity, so there is no window for
the keyguard to hold.

## Fix

`YoutubeMediaBrowser` (written for v1.4.5 in PR #25, never merged — this is its first
release) plus the orchestrator wiring:

* **Gate** — `shouldTryMediaBrowser(enabled, locked, priorTitle)`: only **locked AND cold**
  (`priorTitle == null`). Unlocked launches and warm switches keep the deep link, which can
  name an exact video id. `MediaBrowserGateTest` pins all five cells.
* **openYoutube / playContinue-refire** — in that shape, connect (3 s ceiling), take the
  session token, `playFromSearch(<full title, else query>)`. Op log: `browser→playFromSearch`,
  or `browser→<reason>` (`noService`, `connectRefused`, `connectTimeout`, `noToken`,
  `noController`, `playThrew`) and then the ordinary deep link. Never silent (Rule #3).
* **Deep link held in reserve** — the nudge gives the headless start `REFIRE_STILL_PRIOR_MS`
  (2.5 s) to reach PLAYING/BUFFERING; if not, `browser→fallbackDeeplink` fires the deep link
  once and extends the window to a cold start's budget. The two are complementary: the deep
  link now runs against a YouTube the bind has already warmed, the state the morning entry
  landed in. **Never worse than v1.4.10.**
* **Verification unchanged** — the nudge judges a browser start by title exactly as it
  judges a deep link; the browser's own controller is used only when the notification
  listener is denied, so a good headless start is never misread as "no session".
* **Setting** — "เปิดเพลงได้ตอนจอล็อค" (default ON) in Settings → พฤติกรรม. Off = exactly
  v1.4.10. The trade for the service door is precision: `playFromSearch` takes a search
  string, so YouTube chooses; the title check will say so if it chooses wrong.
* **playContinue stamps `keyguardSecure`** — it never did, so `shouldRefireNoSession` read
  "not secure" and fired a CLEAR_TASK restart that cannot land behind a secure keyguard
  (entry 1794222694408: `mediaBlockedKeyguard: locked=true,secure=false` on a phone whose
  every other entry says `keyguardSecure: true`).

## What the next log should show

On a cold locked open: `openYoutube:VID;browser→playFromSearch;nudge→sessionSeen(...);
nudge→confirmed` with `playbackState: playing` and the expected title — ideally inside 3 s,
with no `launch→fullScreenIntent` at all. If YouTube takes the bind but ignores the search:
`browser→playFromSearch;browser→fallbackDeeplink;link→…;launch→fullScreenIntent;…` and then
whatever v1.4.10 would have done. If the title check reports `wrongVideo` more than once,
switch the setting off and send the log — that is the precision trade biting.

## Claude could NOT run

Acceptance A–I — no device. The bind / `onGetRoot` / `playFromSearch` half is instrumented
behaviour and belongs to the road test. Locally: `assembleDebug` + `testDebugUnitTest`.
