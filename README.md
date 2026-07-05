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
| "เปิด YouTube [ชื่อเพลง]" | Opens first result, or picks from 3 (toggle) |
| "เปิดวิทยุ FM 91.5" | Streams via ExoPlayer w/ media session |
| "เปิดวิทยุ" (alone) | Resumes last-played station |
| "หยุด / พอ / เงียบ / ปิดเพลง" | Stops current media |
| "โทรกลับ" | Redials the last number this app called |
| "ทำอะไรได้บ้าง" | Speaks a short command list |
| "พูดอีกที" | Repeats the last TTS reply |

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

Every pipeline run creates a `DebugEntry` with STT partial/final, webhook request/response, per-stage timings (SCO / STT / webhook / action), and any error. 50 entries retained. Export as JSON via `DebugLogActivity`.

## License

Personal-use project. No warranty. Don't ride distracted.
