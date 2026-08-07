# v1.3.35 — home screen becomes a dashboard

Branch: `feat-home-screen-v1.3.35` · versionCode 41 · versionName 1.3.35

## Why

Rider report (7 Aug 2026): *"บางครั้งอัพเดทเสร็จลืมเปิดสื่อตอนจอล็อค"* — an APK update can
leave the `USE_FULL_SCREEN_INTENT` grant switched off, and the only surface that says so
was `SystemStatusActivity`, two taps deep behind Settings. The failure is then discovered
mid-ride: a locked "เปิดกรรมกรข่าว" that quietly does nothing.

Field log `moto_voice_debug_1786072158662.json`, entry `1786066108846`, is that failure
mode with the grant present but the session never arriving — same rider-visible symptom,
which is exactly why the grant state has to be legible before mounting up:

```
"finishReason": "launch_blocked",
"mediaOperations": "openYoutube:guFvD-Uuj7g[…];launch→fullScreenIntent[…];nudge→launchBlocked(noSession)[…]",
"screenLocked": true
```

Meanwhile home was showing a 4-row permission checklist that had read "ได้รับแล้ว ✓" for
months, a Set-Default button for a role already held, and a how-to card for the only user
of the app — who wrote it.

## What changed

**New — `pipeline/HomeAlerts.kt`** (pure, JVM-tested). Decides which `StatusRow`s deserve
home-screen space: kind ∈ {DefaultAssistant, Permissions, LockScreenLaunch, MediaCtrl,
Battery} and state ∈ {Red, Yellow}. Green and Pending never qualify.

Deliberately excluded:

* `Helmet` / `Internet` — Yellow/Red during perfectly normal use (helmet off, phone
  indoors). A permanent warning trains the rider to ignore the panel. The banner already
  carries "ออฟไลน์" and the full page still shows both.
* `Webhook` / `Tts` — the async checks. Home renders `checkSync()` only, so opening the
  app never fires a webhook round-trip and the screen paints instantly.

Keys off kind + state rather than `fixIntent != null` so the predicate is testable without
constructing an `android.content.Intent` (CLAUDE.md test conventions).

**New — `data/HistoryLabels.kt`** (pure) + **`actions/HistoryReplay.kt`** (Android).
Icon/title/subtitle and tap-to-repeat lifted out of `HistoryActivity` so home renders the
identical rows instead of a second copy of the when-blocks drifting apart.

**`MainActivity` + `activity_main.xml`** rebuilt:

| Removed | Replaced by |
| --- | --- |
| 4-row permission checklist (always visible) | alert rows, shown only when not granted |
| "ตั้งเป็น Default Assistant" button | `DefaultAssistant` alert row (in-app role request kept) |
| "📜 ประวัติ" button | the history itself, inline, 8 most recent |
| "วิธีใช้" card | — (solo rider, `how_to_use_*` strings deleted) |

Status banner is now tappable → `SystemStatusActivity`; a "🩺 สถานะระบบ" button sits next to
"⭐ โปรด" for discoverability. Alert taps keep the in-app flows where they exist
(`RequestMultiplePermissions` for permissions, `RoleManager` for the assistant role, with
the app-details page as fallback when the rider has hit "don't ask again"); every other row
uses its own `fixIntent`, falling back to the status page so a tap is never a no-op.
Everything recomputes in `onResume`, so returning from an OS settings screen reflects the
new grant immediately.

## Tests

`HomeAlertsTest` (9) — green/pending never alert, LockScreenLaunch Red does, Helmet and
Internet stay off home, async rows stay off home, healthy install yields zero alerts,
filtering preserves checker order.

`HistoryLabelsTest` (8) — wording for every action, blank-title YouTube fallback, empty-STT
subtitle, replayable set.

No media/audio code was touched: `MediaOrchestrator`, `SeekParser`, the TTS router and the
whole voice pipeline are byte-identical. This is presentation only.

## Acceptance

Claude cannot run the Acceptance Suite (no device access). **Awaiting rider validation.**

Because A–G exercise the voice pipeline and nothing in this branch touches it, the
meaningful checks here are the home screen itself:

1. Healthy install → no "ต้องแก้ก่อนใช้งาน" section at all; history sits directly under the
   quick-access row.
2. Revoke "เปิดสื่อตอนจอล็อค" in OS settings → reopen the app → row appears in red; tap
   opens the full-screen-intent settings page; return → row is gone.
3. Revoke the microphone permission → row appears; tap shows the system dialog (not a trip
   through Settings); grant → row is gone.
4. Tap a YouTube history row → the clip reopens. Tap a "คุยกับจาวิส" row → nothing happens
   (by design — nothing to replay).
5. "ดูทั้งหมด" still opens the full list with the clear button.
6. Sanity: one normal voice command still works end-to-end (regression guard on the
   untouched pipeline).
