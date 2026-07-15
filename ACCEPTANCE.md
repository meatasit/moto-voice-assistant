# Acceptance Suite (v1.3.20+)

Real-device scenarios that gate any commit touching audio/media code. Runs on
Samsung Galaxy S24 Ultra + Vimoto V11X helmet with the phone screen locked
(the field-log condition that exposed the Spotify hijack in v1.3.19).

**Rules**
- All seven scenarios (A–G) must pass **two rounds in a row** before merge.
- Every pass attaches the debug-log JSON export as evidence.
- Screen must be **locked** for scenarios A–D unless noted — Background
  Activity Launch behaviour differs between locked / unlocked, and the field
  log proved the locked path is where things break.
- Rider validates — the acceptance runner is human. Automated instrumentation
  cannot exercise the SCO ↔ A2DP transitions reliably.

---

## A — Cold YouTube open (baseline)

Preconditions: no media playing, no other app has an active MediaSession.

1. Press BVRA.
2. Say "เปิด youtube กรรมกรข่าว" (or any known-good query).
3. Listen for confirmation TTS "เล่นแล้วค่ะ".
4. Verify the correct video is playing.

**Pass criteria**
- `mediaTargetPkg = com.google.android.youtube`
- `mediaOperations` includes `openYoutube→…;nudge→confirmed`
- `launchBlocked = false`
- Rider hears the confirmation, then the video.

## B — YouTube open while Spotify is holding focus

Preconditions: Spotify is playing something. Screen locked.

1. Press BVRA.
2. Say "เปิด youtube [query]".
3. Listen for confirmation.
4. Verify YouTube plays the requested video and **Spotify has been paused**.

**Pass criteria**
- `mediaOperations` shows `prepauseTarget→com.spotify.music` (or equivalent
  targeted pause), NOT a bare media-key dispatch.
- `mediaTargetPkg = com.google.android.youtube`
- Spotify is paused; YouTube is playing the new video.
- No "everything silent" state.

## C — Opening media while the screen is locked

v1.3.24 adds a full-screen-intent launch path so a locked "open YouTube" can actually
SUCCEED (the riding case), instead of only failing honestly. Behaviour now depends on
the **"เปิดสื่อตอนจอล็อค"** permission (System Status row / USE_FULL_SCREEN_INTENT).

### Precondition for C-with-permission: grant it first
System Status → "เปิดสื่อตอนจอล็อค" must be 🟢. On Android 14 tap the row → grant.

### C-ok — locked open SUCCEEDS (permission granted)
Preconditions: phone locked; "เปิดสื่อตอนจอล็อค" granted.

1. Press BVRA → "เปิด youtube [query]".
2. Verify YouTube actually opens over the lock screen and audio plays.

**Pass criteria**
- `screenLocked = true`, `mediaOperations` contains `launch→fullScreenIntent`
- ends with `nudge→confirmed`, `finishReason = ok`, `launchBlocked = false`
- Rider hears the video — screen may show YouTube over the keyguard (or play behind a
  secure lock). No "unlock first" error.

### C-switch — switch to a different video while locked (permission granted)
Preconditions: video A open, screen locked, permission granted.

1. Press BVRA → "เปิด youtube [DIFFERENT query → video B]".
2. Verify it switches to B.

**Pass criteria**
- `launch→fullScreenIntent`, then `nudge→confirmed`, `got:` matches `want:` (B).
- If B genuinely can't load, `nudge→launchBlocked(stillPrior)` — but with the permission
  the switch should now land.

### C-denied — permission OFF must still be honest (fallback)
Preconditions: phone locked; "เปิดสื่อตอนจอล็อค" NOT granted.

1. Press BVRA → "เปิด youtube [query]".
2. Listen for `เปิดไม่ได้ตอนจอล็อคค่ะ ลองปลดล็อคก่อนนะคะ`.

**Pass criteria**
- `launchBlocked = true`, `finishReason = launch_blocked`, `screenLocked = true`
- `mediaOperations` shows `launch→startActivity(lockedNoFsiPerm)` then
  `nudge→launchBlocked(noSession|stillPrior)`; error includes `no_fsi_permission`.
- Rider hears the honest error — **never silent, never the wrong app**.

### C-unlocked — no regression
1. Unlock, "เปิด youtube [query B]" → B plays.

**Pass criteria**
- `screenLocked = false`, `launch→startActivity`, `nudge→confirmed`, `finishReason = ok`.

## D — "เล่นต่อ" after locked YouTube

Preconditions: opened a video (via A), then pressed lock.

1. Press BVRA.
2. Say "เล่นต่อ".
3. Listen for confirmation "เล่นแล้วค่ะ".
4. Verify the SAME video resumes (not a different app).

**Pass criteria**
- `mediaTargetPkg = com.google.android.youtube` (from `lastOpenedApp()`).
- `mediaOperations` shows `playContinue[com.google.android.youtube]` and
  possibly `playContinue→refireDeeplink` if the session had died.
- No media-key dispatch (grep the log for `→mediaKey`).

## E — "เล่น spotify ต่อ" — hint override

Preconditions: YouTube was last opened via A; Spotify is installed but has
no active session.

1. Press BVRA.
2. Say "เล่น spotify ต่อ".
3. Verify Spotify resumes (or its launch intent fires if there was no session).

**Pass criteria**
- `mediaTargetPkg = com.spotify.music` — the explicit hint beat `lastOpenedApp`.
- `mediaOperations` shows `playContinue[com.spotify.music]`.
- YouTube stays paused.

## F — "หยุด" stops the right thing

Preconditions: YouTube is playing.

1. Press BVRA.
2. Say "หยุด".
3. Verify YouTube pauses and no other app suddenly starts.

**Pass criteria**
- `mediaOperations` includes `pauseTarget→com.google.android.youtube`.
- YouTube session state transitions to paused.
- No unexpected wake of Spotify or another app.

## G — Seek forward + resume

Preconditions: YouTube is playing something with a duration ≥ 60 seconds.

1. Press BVRA.
2. Say "เลื่อน 30 วิ".
3. Verify playback jumps forward ~30 s and **continues playing**.

**Pass criteria**
- `mediaOperations` includes `seek:30s[com.google.android.youtube]`.
- Playback resumes automatically (v1.3.12 auto-resume rule).
- No media-key fallback fires (unless notification-listener permission is
  denied on purpose for a control test).

---

## Log-attachment template

For each scenario, attach a snippet like:

```
Scenario A — cold YouTube open
Round 1: [timestamp] target:com.google.android.youtube ops:openYoutube→VID;nudge→confirmed  end:ok
Round 2: [timestamp] target:com.google.android.youtube ops:openYoutube→VID;nudge→confirmed  end:ok
```

Two consecutive `end:ok` entries with matching `target` + `ops` prove the
scenario is stable.
