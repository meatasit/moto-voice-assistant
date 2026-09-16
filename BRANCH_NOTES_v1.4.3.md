# v1.4.3 — a no-break space made a playing live stream "not switched yet"

Branch: `fix-title-nbsp-v1.4.3` · versionCode 52 · versionName 1.4.3

Field log `1789524511710` (16 Sep 2026, morning ride, helmet on, phone locked) — the first
log from a v1.4.2 build. Read it newest-last:

| entry ts | rider said | prior session | ops (abridged) | result |
|---|---|---|---|---|
| 1789521665113 | เปิดรายการเรื่องเล่าเช้านี้ | **none (cold)** | `link→webTargeted` → FSI → `sessionSeen(stopped)` → play#1..#3 → **confirmed** | ✅ |
| 1789522142513 | เปิดเพลงให้ฟังหน่อย | เรื่องเล่าเช้านี้ | `sessionSeen(playing)` → `refireSwitch(clearTask)` → play#1 → **confirmed** | ✅ |
| 1789522544366 | เปิดกรรมกรข่าวคุยนอกจอ | Top Hits 2026 | `sessionSeen(playing)` → `refireSwitch(clearTask)` → `launchBlocked(stillPrior)`, state `stopped` | ❌ slow live load |
| 1789522604183 | เปิดกรรมกรข่าวคุยนอกจอ (again) | Top Hits 2026 | `sessionSeen(stopped)` → `launchBlocked(wrongVideo)` with `playbackState=playing` and **actual title == expected title** | ❌ **false negative** |

Every launch: `keyguardSecure=true`, `screenInteractive=false`, `netTransport=cellular`,
`mediaBrowserAvail=yt=true,ytm=false`.

## What v1.4.2 answered

1. **A cold launch behind a secure keyguard CAN work.** Entry 1789521665113 is the first
   field-logged cold launch (`mediaPriorTitle` absent) on a locked phone that reached
   `nudge→confirmed`. YouTube registered a session in `stopped`, and our targeted `play()`
   started it. The "never visible → never plays" mechanism from the v1.4.2 note is therefore
   not absolute. What is new in this build is `link→webTargeted`: the manifest `<queries>`
   block made the *targeted* https intent resolve for the first time. One success is not
   proof, but it is the first counter-example in twelve logs.
2. **Full-title verification works.** Entry 1789522142513: `video_title` was cut to 60
   ("…Spotify Mix "), `title_full` was 97, and the verdict confirmed at once.
3. **The UI-less route exists on this phone.** `mediaBrowserAvail=yt=true` — YouTube exposes
   a `MediaBrowserService`. Nothing uses it yet (stabilization sprint); it is the fallback to
   design if cold launches keep failing.

## The bug this build fixes — `wrongVideo` on an exact match

Entry 1789522604183 ended `launchBlocked(wrongVideo)` while `playbackState=playing` and the
two titles printed identically:

```
mediaExpectedTitle: Live "กรรมกรข่าว คุยนอกจอ" 16 กันยายน 2569
mediaActualTitle:   Live "กรรมกรข่าว คุยนอกจอ" 16 กันยายน 2569
```

Byte-compared, they differ at one code point: YouTube's session title has **U+00A0
NO-BREAK SPACE** between "16" and "กันยายน"; the Data API title (via n8n) has U+0020.
`YoutubeVerify.normalize` only trimmed and lower-cased, so equality failed, the prefix rule
failed too (the divergence is mid-string), the verdict stayed `SWITCHED`, and at window end
`playingDecision` returned `WrongVideo`. The rider heard "ยังเปลี่ยนคลิปไม่ทัน" over the live
stream that was playing, then said so out loud (entry 1789522637867).

### Fix

`YoutubeVerify.normalize` now collapses every run of whitespace — `\s`, all of Unicode
category Z (which is where U+00A0 lives; Kotlin's `\s` does not cover it), and the zero-width
characters U+200B/C/D and U+FEFF — to a single plain space before trimming and lower-casing.
`MIN_PREFIX_RATIO` is untouched; `TitleWhitespaceVerifyTest` pins the two titles from the
log and re-asserts the two-episodes case from log 1784078976959 with a no-break space in it.

## Still open

* **Live streams load slowly on cellular after a `CLEAR_TASK` restart.** Entry
  1789522544366: after the re-fire the session sat in `stopped` on the old title through the
  15 s cold window and was still `stopped` 33 s later (the seek at 1789522577350). The second
  request a minute later found it playing. No code change — one occurrence, and the honest
  line ("hasn't switched yet, try again") was correct that time.
* "ถ้าเปิดคลิปต่อเลย" came back from the workflow as `action=seek` with `frequency=0`; the app
  ran `playContinue`. n8n prompt territory, rider owns it.

Rider validates — Claude cannot run acceptance. Two clean rounds before merge per
`ACCEPTANCE.md`.
