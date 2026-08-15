# v1.3.40 — v1.3.38 without the change that broke it

Branch: `relight-v1.3.40` · versionCode 46 · versionName 1.3.40

## The diagnosis, closed

v1.3.38 (build-100) made the app unusable with the helmet on — press BVRA, nothing happens,
and an empty debug log because no interaction ever completed.

Field log `moto_voice_debug_1786763666528` closed it. Same broken build, run **without the
helmet**:

```
"scoState": "no_headset",  "readyEarconRoute": "phone",  "scoColdConnect": true
"เปิดเพลงให้ฟังหน่อย"            → openYoutube → nudge→confirmed → playing
"เปิด YouTube รายการเรื่องเล่าเช้านี้" → openYoutube → nudge→confirmed → playing
```

Everything worked. With no headset, `Earcon.scoActive` stays false, the tones take the old
`STREAM_MUSIC` path, and the interaction runs normally. Helmet on → `scoActive` true →
`ToneGenerator(STREAM_VOICE_CALL, …)` → dead. That flag is the only difference between the
two runs, so the stream switch is the cause. No guessing left.

## What changed

**Withdrawn: the earcon stream switch.** Tones go out on `STREAM_MUSIC` again. The problem it
aimed at — the ready cue landing somewhere the helmeted rider can't hear — is real and still
open, but the next attempt goes behind a setting that is off by default, so it gets tried
while parked instead of discovered mid-ride.

**Hardened, so this class of failure cannot repeat.** `tone.startTone()` sat in a bare
`try/finally`, so anything it threw propagated out of `Earcon.ready()` — and that call site in
`VoiceCommandPipeline` was the one Earcon call in the file that wasn't wrapped. A cue is
decoration; failing to play one must never cost the rider a command. Both the tone call and
the call site are guarded now.

**Kept from v1.3.38** (none of it is implicated — the no-helmet run exercised all of it):

* **Azure 401 latch.** Every synth failure in log 1786688875809 was
  `IllegalStateException: HTTP 401`. Once seen, everything routes to Android for the rest of
  the process (`engineChoiceReason: android_azure_401`) so the rider hears one voice instead
  of a mix of Azure-from-cache and Android-fresh. Saving credentials in Settings clears it.
* **Stop-before-speak.** Azure plays through its own `MediaPlayer` and Android through the
  platform engine, so a line started while another is playing doesn't replace it — both come
  out (*"2 เสียงพูดทับกัน แต่คนละประโยค"*). `TtsRouter.speak` now stops what's in flight first.
* **The v1 earcon sound.** `TONE_PROP_BEEP` 180ms for *speak now*, `TONE_PROP_ACK` 300ms for
  *stopped*, volume 80 → 100. Rider: *"เสียงสัญญาณ Version แรกๆ ฟังง่ายและดังกว่า"*.

## Not in this release

The rider also reported YouTube's **"Connect your devices"** interstitial (cast to the Samsung
TV) swallowing playback: the deep link opens YouTube, the dialog is on top, nothing plays.
Nothing in this app can dismiss another app's dialog, and the nudge already does the only
thing available — `transportControls.play()`, up to three attempts. This is a YouTube app
setting, not a code fix. Noted here so it isn't mistaken for a regression.

## Tests

`AzureAuthRejectedTest` (7), unchanged from v1.3.38.

## Acceptance — awaiting rider validation

1. **Helmet ON, press BVRA** — the thing v1.3.38 broke. Must beep, listen, and answer.
2. **Helmet ON, open something** — `nudge→confirmed`, and the log should show
   `readyEarconRoute` (either value is fine; the cue is no longer allowed to break anything).
3. **By ear** — the start cue should be the old louder beep again.
4. **One voice throughout**, no overlap. Log: `engineChoiceReason: android_azure_401`.
