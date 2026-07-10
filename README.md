# Moto Voice

Thai voice assistant for use while riding a motorcycle. Runs on Android (Samsung Galaxy S24 Ultra tested), routes audio through a Bluetooth helmet (Vimoto V11X), and uses an n8n webhook as its LLM brain — with an offline rule-based fallback so essential commands work when the network doesn't.

**Latest APK:** [GitHub Releases](https://github.com/meatasit/moto-voice-assistant/releases/latest)

---

## What it does

Long-press Home / press the BVRA button on the helmet → say a command in Thai → the assistant executes and speaks confirmation through the helmet.

Supported voice commands (all Thai):

| Say | Result |
|---|---|
| "โทรหา / โทรไปหา [ชื่อ]" | Fuzzy-matches contact, confirms, dials |
| "โทรหารายการโปรด [หนึ่ง..ห้า]" | Dial a favorite slot (also supports "โทรออก", "ที่", "หมายเลข", "อันดับ") |
| "เปิด YouTube [ชื่อเพลง]" | Opens first result, or picks from 3 (toggle) |
| "อันต่อไป / เปลี่ยน / อันอื่น" | v1.3.8 — advance to the next video from the last webhook's list |
| "เมื่อกี้อะไร / เล่นอะไรอยู่" | v1.3.8 — read back the title of what's currently playing |
| "เปิดวิทยุ FM 91.5" | Streams via ExoPlayer w/ media session |
| "เปิดวิทยุ" (alone) | Resumes last-played station |
| "หยุด / พอ / เงียบ / ปิดเพลง" | Stops current media |
| "โทรกลับ" | Redials the last number this app called |
| "ทำอะไรได้บ้าง" | Speaks a short command list |
| "พูดอีกที" | Repeats the last TTS reply |

Bare openers auto-prompt for the payload (v1.3.6 §2) — say "เปิด YouTube" alone
and the assistant asks "เปิดอะไรดีคะ", combines the answer, and runs the full
command in one pipeline.

**คุยต่อเนื่องหลังตอบ (v1.3.8 B2)** — toggle in Settings, default ON. After a
conversational reply (webhook `action=chat`, `action=none`, cancelled call, or
stop), the mic auto-opens for 4 seconds so the rider can continue without
pressing BVRA again. Media actions (YouTube / FM) don't trigger the window —
the media is playing over the mic. Single-level only; no chatter loops.

**"จังหวะรอฟัง" slider (v1.3.6 §1)** — Settings slider 1.0–3.0 s (default 2.0 s)
that hints the recognizer how long to wait for silence before finalising the
STT result. Longer values let the rider pause mid-sentence without being cut
off. The platform recognizer treats this as a hint, so the slot-filling
follow-up above exists as the safety net when the hint is ignored.

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  Helmet BVRA button / Home long-press / Quick Settings tile  │
│         / Riding-Mode mic button / Notification tap          │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
                  VoiceAssistActivity            <── ACTION_ASSIST target
                           │
                           ▼
                  VoiceCommandService            <── mic foreground service
                           │
                           ▼
                  VoiceCommandPipeline
                           │
    ┌──────────────────────┼──────────────────────┐
    │                      │                      │
    ▼                      ▼                      ▼
Phone-state       BluetoothAudioRouter        Earcon (ready)
  guard            (SCO to helmet, 3s w/                │
                    300ms settle on API 31+)             ▼
                                              SpeechRecognizer (th-TH)
                                                        │
                                                        ▼
                                                LocalIntercept ────► handleIntercept
                                                        │              (stop / help /
                                                        │               resume-radio /
                                                        │               call-back / repeat)
                                                        │
                                                        ▼ (else)
                                              WebhookClient (n8n) ────► executeWebhookAction
                                                        │              call/youtube/fm/stop
                                                        │
                                          on failure ▼
                                              CommandParser (rules)
                                                        │
                                                        ▼
                                              action + ThaiTTS + Earcon (end)
                                                        │
                                                        ▼
                                              AppHistory + DebugLog
```

### Layers (packages)

```
com.moto.voice
├── audio/       BluetoothAudioRouter, Earcon, PhoneStateGuard
├── bt/          HelmetGreeter (auto-ready notification + greeting)
├── contacts/    ContactMatcher, ContactEntry
├── data/        AppSettings, AppMemory, AppHistory, FavoritesStore,
│                NetworkState, OfflineNotifier
├── debug/       DebugLog (developer trace, 50 entries)
├── media/       FmPlayerService (MediaSessionService for FM streaming)
├── network/     WebhookClient, WebhookResponse
├── nlu/         LocalIntercept, NumberWordParser, CommandParser,
│                ThaiNormalizer
├── pipeline/    VoiceCommandPipeline (top-level orchestrator)
├── service/     VoiceCommandService, VoiceInteractionService/-Session
├── tile/        MotoVoiceTileService (Quick Settings tile)
├── tts/         ThaiTTS
├── actions/     MediaStopper (dispatchMediaKeyEvent)
├── MainActivity, SettingsActivity, RidingModeActivity,
│   HistoryActivity, FavoritesActivity, OnboardingActivity, VoiceAssistActivity
└── MotoVoiceApplication (notification channels + HelmetGreeter registration)
```

## Webhook contract (n8n side)

Request:
```
POST {webhook_url}
Content-Type: application/json
X-Auth-Token: {token}

{ "text": "ข้อความจาก STT" }
```

Response:
```json
{
  "action": "call | youtube_play | fm | stop | none | speak",
  "contact": "ชื่อคน หรือ null",
  "query": "คำค้น หรือ null",
  "frequency": 91.5,
  "speak": "ประโยคที่ต้องพูดตอบเสมอ",
  "video_id": "id ตัวแรก หรือ null",
  "video_title": "ชื่อวิดีโอตัวแรก หรือ null",
  "videos": [{"id": "...", "title": "..."}],
  "stream_url": "URL สตรีมวิทยุ หรือ null",
  "station_name": "ชื่อสถานี หรือ null"
}
```

- `speak` is spoken via TTS on **every** response (no dead-end silence).
- `videos` is at most 3 entries.
- `frequency` is a Number, not a String.

### Adding a new FM station (n8n side)

The n8n workflow has a lookup table of station names → stream URLs. To add a station:

1. Edit the "stations" node in the n8n workflow.
2. Add a new row, e.g. `{ "match": ["เอฟเอ็ม 89", "89"], "name": "Banana FM", "url": "https://s.example/89", "freq": 89.0 }`.
3. When the LLM returns `action=fm` with your `frequency`, the workflow substitutes `stream_url` + `station_name`.
4. On the app side no changes are needed — `FmPlayerService` plays whatever `stream_url` it's given and shows `station_name` in the notification.

### Adding a new intent

If the LLM is asked to do something not in the current action list:

1. Add a new sealed variant in `WebhookResponse` if new payload fields are needed.
2. Add a `when` branch in `VoiceCommandPipeline.executeWebhookAction`.
3. Optionally add a `HistoryAction` variant if the action should be tap-to-repeat in History.
4. Optionally add a `LocalIntercept.Intercept` if there's an offline shortcut (like "stop").

## Local intercept — offline commands

`nlu/LocalIntercept.kt` catches a handful of commands **before** the webhook fires. These respond in under a second and work with no network.

- Stop patterns: `หยุด`, `พอแล้ว`, `ปิดเพลง`, `ปิดวิทยุ`, `เงียบ`, `หยุดพูด`, `หยุดเล่น`
- Help patterns: `ทำอะไรได้บ้าง`, `ช่วยเหลือ`, `สอนหน่อย`, `ใช้ยังไง`, `คำสั่งมีอะไร`
- Repeat patterns: `พูดอีกที`, `ว่าไงนะ`, `ทวนอีกที`, `พูดใหม่`
- Call-back patterns: `โทรกลับ`, `โทรเบอร์ล่าสุด`, `โทรคนล่าสุด`, `โทรอีกครั้ง`
- Resume-last-radio: exact match on `เปิดวิทยุ` / `เล่นวิทยุ` / `เอาวิทยุ`

Longer forms with parameters (e.g. `เปิดวิทยุ 91.5`) hit the webhook.

Phrase-boundary matching prevents `ปิดวิทยุ` firing inside `เปิดวิทยุ` — see `LocalInterceptTest`.

## Build & install

```bash
# From an Android SDK-equipped machine
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or download the latest signed debug APK from Releases and sideload it.

CI (`.github/workflows/build.yml`) runs unit tests + assembles a debug APK + creates a GitHub Release attached to every push to `main`.

## Development

- **Language:** Kotlin 1.9.24, AGP 8.4.2
- **Min SDK:** 26 (Android 8.0) — **Target SDK:** 34 (Android 14)
- **UI:** View Binding + Material3 theme (dark)
- **Concurrency:** Kotlin Coroutines throughout the pipeline
- **HTTP:** OkHttp 4.12 with `suspendCancellableCoroutine` wrapper
- **Media:** androidx.media3 1.3.1 (ExoPlayer + MediaSession)
- **Secure storage:** `androidx.security.crypto` for the auth token (with plaintext fallback + warning if the keystore is unavailable)
- **R8:** enabled for release builds with dedicated rules for Gson/OkHttp/media3

### Unit tests

Run: `./gradlew testDebugUnitTest`

Covered:
- `CommandParser` (rule-based Thai call intent)
- `ThaiNormalizer` (prefix strip, similarity, Levenshtein)
- `LocalIntercept` (all pattern groups + phrase-boundary regression tests)
- `NumberWordParser` (ordinals, cardinals, digits, cancel)
- `WebhookResponse` (Gson deserialization edge cases)
- `HistoryAction` (polymorphic JSON round-trip)
- `OfflineNotifier` (state-machine correctness)
- `SpeechRateClamp` (settings clamp math)

### Debug log

Every pipeline run creates a `DebugEntry` with STT partial/final, webhook request/response, per-stage timings (SCO / STT / webhook / action), `audioRoute` (SCO helmet vs phone mic), `sttConfidence`, `sttRetryCount`, and `finishReason`. 50 entries retained. Export as JSON via `DebugLogActivity`. The "Errors only" chip filters to entries whose `error` is set or whose `finishReason` isn't `ok` / `intercepted`.

## Troubleshooting

Every failure mode has a spoken diagnostic — the rider never hears silence.
Grep-friendly mapping from TTS line → root cause → fix:

| The rider hears | What happened | How to fix |
|---|---|---|
| "โหมดออฟไลน์ค่ะ ทำได้เฉพาะโทรกับหยุดเล่นนะคะ" | No internet reached the n8n webhook; announced once per outage | Check phone data or Wi-Fi. `SystemStatus → อินเทอร์เน็ต` should show green. |
| "กำลังคิดค่ะ รอสักครู่นะคะ" | Webhook has taken >3s. LLM likely warming up | Wait; will speak "อีกนิดนะคะ" at 10s. If timeout fires, fallback engages. |
| "ระบบหลักช้า ทำแบบออฟไลน์ให้ค่ะ" | Webhook timeout AND the spoken command matched a rule (`โทรหา…` / `หยุด`) | Command was executed offline. Check webhook health via `SystemStatus → Webhook`. Increase timeout in Settings if this happens often on your NAS. |
| "ระบบช้ามากตอนนี้ ทำได้เฉพาะโทรกับหยุดเล่นค่ะ" | Webhook timeout AND command wasn't a rule | Retry with `โทรหา [ชื่อ]` or `หยุด`. Long-term: bring n8n back online. |
| "ระบบปฏิเสธการเชื่อมต่อค่ะ ตรวจสอบโทเค็นในแอปนะคะ" | HTTP 401 from webhook | Open Settings → Auth Token, verify it matches the value n8n's X-Auth-Token expects. Default: `meatasit`. |
| "เซิร์ฟเวอร์มีปัญหาชั่วคราวค่ะ ลองใหม่อีกครั้งนะคะ" | HTTP 5xx or other non-401 error | Check n8n logs. Rule-based fallback is attempted for matching commands. |
| "ค้นหาไม่ได้ชั่วคราวค่ะ ลองพูดชื่อเจาะจงกว่านี้…" | Webhook succeeded but returned no `videos[]` / `video_id` | Say a more specific song / video title. n8n side may also need a wider search query. Deliberately does NOT open YouTube search page — you can't tap results while riding. |
| "เปิดสถานีไม่สำเร็จค่ะ สถานีอาจมีปัญหาชั่วคราว" | ExoPlayer failed the stream 3 times (2 retries + original) | Try another station. Check n8n's `stations` node URL is still valid. Some icecast streams reject non-standard User-Agents; the app uses `MotoVoice/1.1 (Android)`. |
| "ตอนนี้ไม่มีสัญญาณโทรศัพท์ค่ะ ลองใหม่อีกครั้งนะคะ" | `TelephonyManager.simState` isn't `READY` or device has no radio | Insert SIM / turn off airplane mode. `SystemStatus` doesn't yet show cell state; rely on the OS status bar. |
| "ไม่ได้ยินค่ะ พูดอีกครั้งนะคะ" | STT NO_MATCH / TIMEOUT / result <2 chars on the first main listen | Auto-listens again — just speak again louder. Common in wind. Sprint C tuned COMPLETE_SILENCE to 1200ms so this is faster. |
| "ยังไม่ได้ยินค่ะ ลองใหม่อีกครั้งนะคะ" | STT failed twice in a row | Press BVRA again. Consider closing helmet vents or lowering speed briefly. |
| "ใช้ไมค์โทรศัพท์" | BT SCO connection didn't complete in 3s | Reconnect helmet (`SystemStatus → หมวก`). Check helmet firmware. Battery is another common cause of intermittent SCO. |
| "อุปกรณ์ไม่รองรับการรับเสียง" | `SpeechRecognizer.isRecognitionAvailable()` = false | Install Google app / Play Services. Check `SystemStatus → TTS/STT`. |
| "กำลังใช้สายอยู่ ลองใหม่หลังวางสาย" | `PhoneStateGuard` detected `AudioManager.mode` was IN_CALL / IN_COMMUNICATION / RINGTONE | Hang up first. This is a preflight to avoid competing with the ongoing call. |
| "สิทธิ์ไมโครโฟนหายไปค่ะ เปิดแอปเพื่อแก้ไขนะคะ" (or contacts / call / Default Assistant variants) | Trigger-time preflight caught a permission or role loss (common after OEM OS update) | Notification is posted with a deep-link. Or use `SystemStatus`. |
| "ยกเลิกแล้วค่ะ" | Rider double-tapped BVRA (barge-in cancel) — or explicitly said "ยกเลิก" during confirmation | Not an error. Debug log shows `finishReason: barge_in_cancel` on double-tap. |

### System Status page (Settings → 🩺 สถานะระบบ)

Seven rows summarize every subsystem. Green = fine, Yellow = degraded, Red = broken.
Tap a Red/Yellow row to launch the OS settings page that fixes it.
"ทดสอบทั้งระบบ" runs everything and speaks the summary — perfect for a
pre-ride check without needing to unlock the phone.

### Backup / Restore

Settings → `⬇ Export…` writes a versioned JSON via SAF (choose location).
The file contains every user-tweaked setting **except the auth token** (rider re-enters after import per spec §8.1). `⬆ Import…` validates the schema, applies the values, and shows a Toast summary.

## License

Personal-use project. No warranty. Don't ride distracted.
