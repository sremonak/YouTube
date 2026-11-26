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

## Status (implemented in PR #1, build `Vidora-1.0.29`+)

| Item | Status |
|---|---|
| PoToken provider (BotGuard WebView, ported from NewPipe) | ✅ Done — Settings → Advanced shows status/toggle |
| Stream URL expiry / 403 recovery | ✅ Done — up to 3 back-off re-extractions on HTTP errors + pre-emptive refresh when resuming after 4 h |
| In-app updater | ✅ Done — GitHub Releases, changelog, download + install, "skip this version" |
| Crash & error reporter | ✅ Done — dialog on next start, copy or open pre-filled GitHub issue; "Report a problem" in Settings |
| Own release keystore | ⏸ Skipped by owner — the shared debug key is kept (CI still supports `VIDORA_KEYSTORE_B64` if ever wanted) |
| SABR streaming | ⏳ Waiting for NewPipe Extractor PR #1568; the client fallbacks below cover today's playback |
| Playback when NewPipe is blocked (VPN, "not a bot") | ✅ Done (1.0.39) — yt-dlp's EJS solver in a WebView solves `sig`/`n`; TV client (with account) honours the pre-roll wait; Safari HLS and mobile-web fallbacks; verified on an emulator by `e2e.yml` |
| YouTube look | ✅ Done (1.0.39) — YouTube palette, Home / Shorts / Subscriptions / You bar, flat chips, watch-page layout |
| Account like the official app | ✅ Done — Home uses YouTube's own personalised chips (All / Music / Mixes…) + YouTube Music home; Subscriptions tab = account feed; You tab = account history / liked / watch later; watched videos are added to YouTube history; sign-in waits for `LOGIN_INFO`; Settings → Account check |
| Gestures (double-tap, hold 2×, swipe brightness/volume, pinch zoom) | ✅ Done |
| Seek-bar preview thumbnails | ✅ Done |
| Captions: picker, auto-translate, size, download | ✅ Done (`.vtt`; YouTube doesn't serve `.srt`) |
| Audio-language picker | ✅ Done (remembered) |
| Equalizer / bass boost / volume boost | ✅ Done — loudness normalisation via `loudnessDb` is not exposed by the extractor yet |
| Shorts vertical player | ✅ Done |
| Queue persistence | ✅ Done |
| Comments (replies, pinned, hearted, timestamps) | ✅ Done |
| Live chat | ✅ Done — live streams show a polling chat panel (pause/resume) instead of comments |
| Return YouTube Dislike | ✅ Done (attribution in About) |
| DeArrow | ✅ Done (opt-in) |
| Content filters | ✅ Done |
| Local playlists | ✅ Done (create, rename, reorder, share, download all) |
| Subscription groups | ✅ Done |
| New-video notifications (RSS + WorkManager) | ✅ Done |
| Import NewPipe / LibreTube / FreeTube / Takeout / OPML | ✅ Done (+ NewPipe-format export) |
| Signed-in like / dislike / subscribe sync | ✅ Done (toggle) + save to / remove from / create YouTube playlists |
| Batch download | ✅ Playlists, local playlists and channels; custom download folder / SD card (Storage Access Framework). Metadata embedding ⏳ later |
| Android Auto, widget, Quick Settings tile | ✅ Done |
| Android TV | ✅ Launcher entry + banner + visible D-pad focus ring on cards; a dedicated 10-foot UI ⏳ later |
| Bengali localisation | ✅ Full UI (≈250 strings incl. all settings, dialogs, empty states) |
| OkHttp 5 | ✅ 5.1.0 (5.5.x needs compileSdk 37) |
| Unit tests in CI | ✅ `testDebugUnitTest` (versions, PoToken base64, imports, filters, storyboards, EQ, search refine, continue watching) |
| F-Droid / fastlane metadata | ✅ `fastlane/metadata/android` |
| Baseline Profiles | ✅ ProfileInstaller + app `baseline-prof.txt` (library profiles compiled on sideloaded installs) |
| Piped / Invidious fallback | ✅ Last-resort stream source when YouTube blocks the network |
| Room, Coil 3, lint/detekt | ⏳ Later — large migrations with little user-visible gain today |
| Incognito mode | ✅ Settings + one-tap toggle in Library — no watch/search history |
| Voice search | ✅ Mic button in the search bar |
| Search refinements | ✅ Length, upload date, hide live (client-side; the extractor only supports content type server-side) |
| Continue watching | ✅ Home row with half-watched videos and time left |
| Seek-bar markers | ✅ Chapters and SponsorBlock segments marked on the seek bar |
| Sleep timer "end of video" | ✅ Done |
| Stats for nerds | ✅ Codec, resolution, bitrate, buffer, dropped frames |
| Loudness normalisation | ✅ "Stable volume" from YouTube's `playerConfig.audioConfig.loudnessDb` |
| Download post-processing | ✅ Parallel multi-connection fetch, SponsorBlock cut (keyframe, lossless), MP4 metadata + cover art, subtitle .vtt |
| Comments write / like / reply | ✅ Signed in, YouTube's own comment API |
| YouTube Music library | ✅ Liked songs, library playlists, albums, artists |
| PoToken player client | ✅ MWEB with WebView BotGuard tokens (full quality ladder) in the fallback chain |
| History controls | ✅ Clear on device, manage/pause YouTube history (My Activity) |
| Chromecast | ⏳ Needs Google Cast SDK (Play Services) + IP-bound URL handling |
