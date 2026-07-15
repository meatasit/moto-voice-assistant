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

## C — Launch blocked (BAL while locked)

Two variants — the v1.3.21 fix (field log 1784074856214) targets **C2**, where a
YouTube session already exists so the old "no session" detector never fired.

### C1 — no YouTube session at all
Preconditions: phone locked, nothing playing; keep locked 30+ s before triggering.

1. Press BVRA → "เปิด youtube [query]".
2. Listen for `เปิดไม่ได้ตอนจอล็อคค่ะ ลองปลดล็อคก่อนนะคะ` (LAUNCH_BLOCKED_LOCKED).

**Pass criteria**
- `launchBlocked = true`, `finishReason = launch_blocked`, `screenLocked = true`
- `mediaOperations` ends with `nudge→launchBlocked(noSession)`
- Rider hears the honest error — **never silent, never Spotify**.

### C2 — switch video while YouTube is already open (the reported bug)
Preconditions: open video A (via A), then **lock the screen**. YouTube session
stays alive playing A.

1. Press BVRA → "เปิด youtube [a DIFFERENT query → video B]".
2. Verify the video does **not** switch (stays on A) AND the rider is told so.

**Pass criteria**
- `launchBlocked = true`, `finishReason = launch_blocked`, `screenLocked = true`
- `mediaOperations` ends with `nudge→launchBlocked(stillPrior)` — **must NOT** contain
  `nudge→play#N` or `nudge→confirmed` (the old bug resumed video A and faked success).
- `want:"<title B>"` ≠ `got:"<title A>"` in the log line proves the switch didn't land.
- Rider hears `เปิดไม่ได้ตอนจอล็อคค่ะ`; video A is **not** resumed into a fake success.

### C3 — same test UNLOCKED must still switch (no regression)
1. Unlock, repeat C2 step 1.
2. Verify video B actually plays.

**Pass criteria**
- `launchBlocked = false`, `finishReason = ok`, `screenLocked = false`
- `mediaOperations` shows `nudge→confirmed`; `got:` matches `want:` (video B).

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
