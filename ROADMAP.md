# Vidora — Improvement Roadmap (research, Nov 2026)

This plan comes from three things:
- An audit of the current Vidora code (about 6.4k lines of Kotlin).
- A comparison with NewPipe, LibreTube and PipePipe.
- YouTube's latest anti-third-party changes.

Priority: **P0** = keeps the app working, **P1** = premium features users notice, **P2** = engineering quality.

---

## P0 — Reliability (most important)

| # | Item | Why | How |
|---|---|---|---|
| 1 | **PoToken provider (BotGuard)** | NewPipe Extractor 0.27 removed Android/iOS poToken support. Web clients now need a poToken, or playback fails with 403 / "confirm you're not a bot". | Port NewPipe's `PoTokenWebView` + `PoTokenGenerator` + `PoTokenProviderImpl` (GPL-3.0, compatible). Register it with `YoutubeStreamExtractor.setPoTokenProvider(...)`. |
| 2 | **SABR streaming readiness** | YouTube is removing classic streaming URLs step by step. LibreTube 32.1 shipped SABR, and PipePipe says visionOS is the last endpoint without a poToken. | Add a `StreamSource` abstraction now. Follow NewPipe Extractor PR #1568 (stream API refactor) and port a SABR Media3 `DataSource` when it lands. |
| 3 | **Stream URL expiry / 403 recovery** | googlevideo URLs expire after about 6 h, so long pauses or queues break. | On HTTP 403, re-extract the stream and keep the playback position. |
| 4 | **In-app updater** | When YouTube changes, users need a fixed APK fast. | Check this repo's GitHub Releases API, show a changelog, then download and install the APK (`REQUEST_INSTALL_PACKAGES`). |
| 5 | **Crash and error reporter** | Today there's no visibility into failures. | A global `UncaughtExceptionHandler` and an "Error details" screen with a copy/share stack trace. No telemetry. |
| 6 | **Own release keystore** | The committed debug keystore means anyone can sign an "update". | Generate a private keystore and set the `VIDORA_KEYSTORE_B64` secret. CI already supports it. |

## P1 — Premium features

**Player**
- Gestures: double-tap ±10 s, swipe for brightness/volume, pinch to zoom/fill, long-press for 2× speed.
- Seekbar preview thumbnails (storyboards from the extractor).
- **Captions/subtitles**: track picker, auto-translate, style options, and `.srt` download.
- **Audio language picker** for dubbed or multi-audio videos. Today only the original track is chosen.
- Loudness normalisation (YouTube `loudnessDb`), equaliser / bass boost (`android.media.audiofx`).
- **Shorts**: vertical swipe player.
- Queue persistence across restarts.

**Content**
- **Comments** (with replies, pinned and hearted) via `CommentsInfo`.
- **Live chat**, now supported by the extractor (0.27).
- **Return YouTube Dislike**: `https://returnyoutubedislikeapi.com/votes?videoId=…`. Needs attribution; rate limit is 100 per minute.
- **DeArrow** for clickbait-free titles and thumbnails (`sponsor.ajay.app/api/branding`).
- Content filters: block channels or keywords, hide watched videos, hide Shorts everywhere.

**Library and account**
- Local **playlists** (create, rename, reorder, export).
- Subscription **groups**.
- **New-video notifications** via a periodic WorkManager job and channel RSS feeds (`youtube.com/feeds/videos.xml?channel_id=`). RSS is also fast and doesn't need login.
- Import from NewPipe or LibreTube backups and Google Takeout CSV.
- Signed-in actions: like, subscribe and save to a YouTube playlist from inside Vidora.

**Downloads**
- Batch-download a whole playlist or channel.
- Embed subtitles, cover art and metadata tags in audio files.
- Pause/resume all, SD-card folder picker, schedule (night / Wi-Fi).

**Platform**
- Android Auto (`MediaLibraryService`), a home-screen widget, and a Quick Settings tile.
- Android TV (leanback launcher + D-pad UI).

## P2 — Engineering quality

- **Localisation**: move hard-coded strings into `strings.xml` and add a Bengali (`values-bn`) translation.
- **Dependencies**: align OkHttp 4.12 with the extractor's OkHttp 5.5.1, move to Coil 3, update the Compose BOM.
- **Performance**: Baseline Profiles + Macrobenchmark for startup, and R8 full mode.
- **Storage**: move the library from JSON to Room so large histories and playlists scale.
- **Quality gates**: unit tests (parsers, `AccountApi`, `Muxer`), plus ktlint/detekt and Android Lint in CI.
- **Accessibility**: content descriptions, TalkBack checks, font scaling.
- **Privacy option**: optional Piped/Invidious proxy so YouTube never sees the user's IP (the LibreTube model).
- **Distribution**: F-Droid/fastlane metadata and an automatic changelog.

## Suggested order

1. **Sprint 1 (stability):** PoToken, 403 recovery, updater, crash reporter.
2. **Sprint 2 (player):** gestures, captions, audio language, seek thumbnails.
3. **Sprint 3 (content):** comments, Return YouTube Dislike, DeArrow, Shorts player, content filters.
4. **Sprint 4 (library):** playlists, notifications, imports, signed-in actions.
5. **Sprint 5 (polish):** Bengali localisation, Room, baseline profiles, tests, Android Auto/TV.
