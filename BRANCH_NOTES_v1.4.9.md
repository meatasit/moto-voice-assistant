# v1.4.9 — UX/UI pass for the rider coming back after a month

Branch: `claude/epic-darwin-fjfedd` · versionCode 58 · versionName 1.4.9 · **awaiting rider validation**

Requested by the rider before picking the app up again after ~4 weeks away: a UX/UI review,
the fixes, and a Release. **No pipeline, audio, media or NLU code is touched** — the sprint
rule (ห้ามแก้ก่อนพิสูจน์) still holds there and nothing in this branch has a field log behind
it. Everything below is screen-level: what the rider sees and taps while parked.

## What changed

| area | before | after |
|---|---|---|
| Theme | Custom dark-only "night ride" palette, fixed colours in every layout | **Material 3 / "Google style"**: `Theme.Material3.DayNight`, `DynamicColors` (wallpaper palette on Android 12+), amber-seeded M3 scheme as the pre-12 fallback, light + dark. Layouts use theme attributes (`?attr/colorSurface…`) only. Filled tonal cards, M3 type scale, Material icon buttons instead of emoji in the header/quick actions, pill chips on `secondaryContainer`. |
| Home | Hero hint said "แตะเพื่อตั้งเป็นผู้ช่วยหลัก" but the tap opened the status page | Hero tap goes to the **fix** while something blocking is outstanding (role → OS picker, permissions → system dialog); status page only when healthy. Hint text matches. |
| Home | No way to try a command without the helmet button | **"ลองสั่งเลย"** button on the hero: starts `VoiceCommandService` directly — the same front door as BVRA / the QS tile — so SCO → beep → STT → brain → TTS can be checked while parked. Hidden while RECORD_AUDIO is missing (a microphone FGS cannot start without it). |
| Home | Command list only in README | **"พูดได้ว่า…" card** with the voice commands and the answer words; collapsible, state remembered (`AppSettings.homeCommandsExpanded`). |
| Home | Alerts header always red "ต้องแก้ก่อนใช้งาน", even when only optional Yellow rows (battery, media control) were listed | Header follows the worst row: Red → "ต้องแก้ก่อนใช้งาน" in red, Yellow-only → "แนะนำให้ตั้งค่า" in amber. |
| System status | Labels mixed English/Thai ("Default Assistant", "Battery unrestricted", "Webhook", "TTS"), and the TTS row renamed itself from "TTS" to "เสียงพูด (TTS)" when its result landed | One Thai label per row, same string for the Pending placeholder and the result. Optional rows say "(ทางเลือก)"; detail lines say what the gap costs. |
| Settings | `AppSettings.persona` (ค่ะ/ครับ) existed but had no control since the Azure voice dropdown went — reachable only via settings import | **Persona toggle** in "เสียงของจาวิส"; applies to `PersonaHolder` immediately so the preview and the next command use it. |
| History / Debug log | "ล้าง" cleared on a single tap | Confirmation dialog (the log is the only evidence of what went wrong on a ride). |
| Onboarding | Step 4 ticked itself whenever a URL existed (always — there is a default); test result was a Toast | ✅ only after a successful round-trip in this screen; result reads inline in the step. |

## What this does NOT change

* `VoiceCommandPipeline`, `MediaOrchestrator`, `Earcon`, `BluetoothAudioRouter`, `ThaiTTS`,
  `LocalIntercept`, `CommandParser`, `ErrorSpeech` — untouched. Acceptance A–I behaviour is
  by construction identical to v1.4.8.
* The dark-only decision from v1.4.0 is reversed: the app now follows the system theme. A
  rider who wants it dark at night sets the phone dark (or schedules it); nothing in the app
  forces either.

## Claude could NOT run

* `assembleDebug` / `testDebugUnitTest` locally — no Android SDK in the sandbox and
  `dl.google.com` is refused by the sandbox proxy. CI builds and tests on the PR.
* Acceptance A–I — no device. Awaiting rider validation on the S24 Ultra + Vimoto V11X.

## What to look at on the device

1. Home in light and dark system theme; colours should follow the wallpaper.
2. "ลองสั่งเลย" → beep → "โทรหา …" → the normal confirm flow, same as from the helmet.
3. Settings → คำลงท้าย → ครับ → "ทดลองฟัง" should end in ครับ.
4. Status page labels no longer jump when the TTS / Webhook rows finish.
