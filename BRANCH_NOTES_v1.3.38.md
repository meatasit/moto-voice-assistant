# v1.3.38 — the two voices were a dead Azure key

Branch: `fix-two-voices-and-earcon-v1.3.38` · versionCode 44 · versionName 1.3.38

Field log `moto_voice_debug_1786688875809`. The rider's verdict on v1.3.37: *"ผมว่ามันทำงาน
ได้นะ"* — switching, live shows and song choice all behaving. Three things left.

## 1. "AI 2 เสียง" — found it

v1.3.37 restored the Azure failure detail, and every single failure in this log is the same
string:

```
"azureError": "synth failed after 73ms — IllegalStateException: HTTP 401"
"azureError": "synth failed after 262ms — IllegalStateException: HTTP 401"
"azureError": "synth failed after 581ms — IllegalStateException: HTTP 401"
… twelve of them, 67ms to 688ms
```

**The Azure subscription key is being rejected.** Not a flaky network — the failures are
67–688ms, far too fast to be anything but an immediate rejection, and the code turns a
non-2xx into `IllegalStateException("HTTP <code>")`.

That is exactly the two-voices symptom. Lines already in the on-disk TTS cache still play in
the Azure voice (`engineChoiceReason: azure_used`, `cacheHit: true`); every line that has to
be synthesised fresh 401s and falls back to the Android voice. Same ride, two voices,
alternating on whether that sentence had been spoken before.

**The real fix is a working key — that part is on the rider.** What the app does now is stop
making it worse: the first 401 latches `AzureTtsState.authRejected()` and the router sends
everything to Android for the rest of the process, logged as
`engineChoiceReason: android_azure_401`. One voice, consistently, instead of a mix. Saving
credentials in Settings clears the latch, so pasting a good key and hitting preview brings
Azure straight back.

## 2. "2 เสียงพูดทับกัน แต่คนละประโยค"

Different bug, same complaint line. The Azure engine plays through its own `MediaPlayer` and
Android TTS through the platform engine, so starting a line while another is still playing
does not replace it — both come out. Easy to hit: the pipeline speaks its reply and
`MediaOrchestrator`'s nudge announces a blocked launch a few seconds later.

`TtsRouter.speak` now stops whatever is in flight first. The newer line wins, which is also
the more useful one.

## 3. The start cue: back to the v1 sound, and out the right pipe

Rider: *"เสียงสัญญาณ Version แรกๆ ฟังง่ายและดังกว่า"*.

The v1.3.36/37 experiment — a rising DTMF pair, then shorter pips — was chasing "start vs
stop are too alike". It made the cue harder to hear, which is worse. DTMF tones are quieter
than the PROP family through a helmet, and I had also shortened them.

* **start** → back to the original v1.3.9 `TONE_PROP_BEEP`, 180ms
* **stop** → `TONE_PROP_ACK` at 300ms — same loud family, so "quieter" can't be mistaken for
  "different"; separation is now short-bright vs long-flat
* **volume** → 80 → 100

**And the routing, which is probably the actual reason it keeps being missed.** This file
assumed `STREAM_MUSIC` "routes through the current output — helmet if SCO is up". The logs
disagree: even after v1.3.37 gave the route poll a 2s budget, presses with
`scoState=connected` still report `readyEarconRoute=phone`. While SCO is up for the mic, the
**VOICE_CALL** stream is what the headset link carries; STREAM_MUSIC can still be sitting on
A2DP or the phone speaker. The tones now go out on `STREAM_VOICE_CALL` whenever the pipeline
says SCO is live, and stay on `STREAM_MUSIC` when there is no headset (VOICE_CALL would land
on the earpiece).

This is the first change that treats the *route* rather than the *timing*, so it is the one
to watch in the next log.

## Also in this log

* `refireSwitch(clearTask)` → `nudge→play#1` → `nudge→confirmed` on two switches — the
  v1.3.37 window extension works.
* Live shows resolving correctly: กรรมกรข่าว คุยนอกจอ 14 ส.ค. and เรื่องเล่าเช้านี้ 14 ส.ค. both
  opened as live.
* One `stillPrior` and three `noSession` remain. The `fsiTrampolineRan=false` case from the
  previous log appears once (1786586441014) — still not diagnosed, still not guessed at.

## Tests

`AzureAuthRejectedTest` (7) — the latch trips on 401 and only on 401, clears on success,
and the reason string is distinct from the other Android routes.

## Acceptance — awaiting rider validation

1. **Press BVRA with the helmet on** — the start cue should be back to the old louder beep
   AND audible in the helmet. Log: `readyEarconRoute=sco`.
2. **By ear** — short bright beep (speak now) vs long flatter tone (stopped).
3. **Listen for one voice** — every line should now be the Android voice until the key is
   fixed, never a mix. Log: `engineChoiceReason: android_azure_401`.
4. **Nothing should talk over anything.**
5. **Renew the Azure key** in the portal, paste it in Settings → preview. If it speaks in the
   Azure voice, everything goes back to Azure from that moment.
