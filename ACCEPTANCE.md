# Acceptance Suite (v1.3.20+)

Real-device scenarios that gate any commit touching audio/media code. Runs on
Samsung Galaxy S24 Ultra + Vimoto V11X helmet with the phone screen locked
(the field-log condition that exposed the Spotify hijack in v1.3.19).

**Rules**
- All scenarios (A–H) must pass **two rounds in a row** before merge.
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

## B — YouTube open while Spotify auto-resumed on BT reconnect (the v1.3.25 field bug)

This is the exact scenario from field log `moto_voice_debug_1784173407858`: the
helmet auto-resumes Spotify when Bluetooth reconnects, Spotify grabs audio focus,
and every locked `เปิด youtube` then failed with `noSession` because YouTube could
never take focus. v1.3.25 pre-pauses the foreign player first.

Preconditions: **Power the helmet BT off, then on** (reproduces the reconnect
auto-resume) so Spotify is actively playing. Screen locked. "เปิดสื่อตอนจอล็อค" granted.

1. Confirm Spotify is playing (you should hear it after the BT reconnect).
2. Press BVRA.
3. Say "เปิด youtube [query]".
4. Listen for confirmation.
5. Verify YouTube plays the requested video and **Spotify has been paused**.

**Pass criteria**
- `mediaOperations` shows `prepauseForeign[com.spotify.music]` (targeted controller
  pause), NOT a bare media-key dispatch.
- `mediaForeignPaused = com.spotify.music=playing` (or `=buffering`) — proves Spotify
  was holding focus at launch, i.e. the field-bug condition was actually reproduced.
- `mediaTargetPkg = com.google.android.youtube`, ends `nudge→confirmed`,
  `launchBlocked = false`.
- On the locked FSI path, `fsiRan = true` (the OS honored the full-screen intent).
- Spotify is paused; YouTube is playing the new video. No "everything silent" state.

**If it still fails** (`launchBlocked = true`): the new diagnostic fields localize it —
`fsiRan = false` → the OS demoted the FSI (trampoline never launched); `fsiRan = true,
fsiLaunch = false` → trampoline ran but the YouTube deep link was BAL-dropped. Attach
the log either way — that is the evidence for the next fix.

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
- `fsiRan = true` (the OS honored the FSI and the trampoline launched YouTube)
- ends with `nudge→confirmed`, `finishReason = ok`, `launchBlocked = false`
- Rider hears the video — screen may show YouTube over the keyguard (or play behind a
  secure lock). No "unlock first" error.

### C-switch — switch to a different video while locked (permission granted)
Preconditions: video A **playing**, screen locked, permission granted. Do several
back-to-back switches (A→B→C→…) — field log 1784203179701 showed the switch, not the
cold open, is the flaky case.

1. Press BVRA → "เปิด youtube [DIFFERENT query → video B]".
2. Verify it switches to B.
3. Repeat with C, D, … to exercise the flaky path.

**Pass criteria (v1.3.26 fix 1 + 2)**
- `launch→fullScreenIntent`, then `nudge→confirmed`, `got:` matches `want:` (B).
- If the first deep-link delivery doesn't land, `mediaOperations` shows
  `nudge→refireSwitch` (the automatic one-time re-fire), then `nudge→confirmed`. The
  switch should land within the window without the rider repeating the command.
- **No dead silence:** because we no longer pre-pause A while locked, if a switch still
  fails the PRIOR video keeps playing (not silence). A genuine failure still ends
  `nudge→launchBlocked(stillPrior)` + honest TTS — but this should now be rare.
- Grep the log: locked switches must NOT show `pauseTarget→com.google.android.youtube`
  before the `openYoutube` (fix 2 — we don't pause our own video when locked).

**Pass criteria (v1.3.29 — rapid repeat / re-fire must not be demoted)**
Do this round **fast**: back-to-back locked opens **within ~10s of each other**, which is
the case field log 1784256366258 failed on. Until v1.3.29 every launch re-used one
notification id, so a second open inside the notification's 12s lifetime was an *update*
and the OS never fired the full-screen intent.
- **`fsiRan = true` on EVERY locked open**, including the rapid repeats — not just the
  first one after a quiet gap. A single `fsiRan=false` means the demote is NOT (only) the
  stale-id bug and the rate-limiting hypothesis is back on the table.
- When `nudge→refireSwitch` appears, the entry that follows it must show `fsiRan = true` —
  the re-fire can now actually re-trigger the FSI instead of silently no-oping.
- No stacking: at most one "กำลังเปิดสื่อให้ค่ะ" notification visible at a time (each
  launch cancels the previous id before posting a fresh one).

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

### D-refire — "เล่นต่อ" while Spotify auto-resumed (v1.3.25 refire focus-guard)

Preconditions: YouTube was opened then its session died (or force-stop YouTube), and
the helmet BT was just reconnected so Spotify is auto-playing. Screen locked. This
exercises the `playContinue→refireDeeplink` path, which v1.3.25 also pre-pauses.

1. Confirm Spotify is playing.
2. Press BVRA → "เล่นต่อ".
3. Verify YouTube's last video resumes and Spotify is paused.

**Pass criteria**
- `mediaOperations` shows `playContinue→refireDeeplink[com.google.android.youtube]`
  **preceded by** `prepauseForeign[com.spotify.music]`.
- `mediaForeignPaused = com.spotify.music=playing`.
- Ends `nudge→confirmed`, `launchBlocked = false`; Spotify paused, YouTube playing.

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

## H — Prompt / re-listen pacing (v1.3.27, by ear)

Rider preference (2026-07-17): prompts and the "not heard, try again" re-listen pace
**serially** — the assistant speaks the whole line, THEN a beep, THEN a clear ~0.3s
gap, THEN the mic opens. No more speaking-over-the-beep pile-up on the helmet.

Trigger both prompt moments on the helmet (SCO):
1. **Re-listen:** press BVRA, stay silent (or mumble) so it says "ไม่ได้ยินเลย พูดอีกที"
   → confirm you hear the full line, then the dual beep, then a beat of silence, THEN
   it's your turn. The beep must NOT overlap the sentence.
2. **Confirm question:** trigger a confirm (e.g. call with confirmation on) → same
   pacing: full "…ใช่ไหมคะ", then beep, then gap, then your turn.

**Pass criteria (by ear — no log field)**
- The answer-listen beep lands AFTER the prompt finishes, never during it.
- There's a clear short silence between the beep and needing to speak.
- Nothing feels "ติดกัน / พูดหลังสัญญาณ".

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
