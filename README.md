# Vidora — ad-free video client for Android

Vidora is a modern, fully native Android app (Kotlin + Jetpack Compose + Media3) for watching
public YouTube, PeerTube and Wikimedia Commons videos, with **background playback**, **picture-in-picture**,
**video/audio downloads in the quality you choose**, SponsorBlock and a fully customisable
design — no ad SDKs, with a local library. Signing in with a YouTube account is
**optional**; supported account features depend on the upstream platform (see [Sign in](#-optional-youtube-sign-in)).

> বাংলা সারসংক্ষেপ নিচে দেওয়া আছে ⬇️

---

## ✨ Features

| Area | What you get |
|---|---|
| **No ads** | Streams are fetched directly with [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) — no ads, no ad SDKs, no trackers inside the app. |
| **Background play** | Keeps playing with the screen off or the app closed. Notification, lock-screen, Bluetooth & headset controls (Media3 `MediaSessionService`). Video decoding pauses in the background to save battery. |
| **Picture-in-picture** | Auto-PiP when leaving the app (Android 12+ seamless auto-enter) or manual PiP button. |
| **Downloads** | Download **video** (144p → 4K) or **audio only** (M4A/AAC or WebM/Opus, every bitrate) with size estimates. High-resolution video+audio is merged on-device losslessly using `MediaMuxer` (no FFmpeg, small APK). Resumable 8 MB chunks, progress notifications, Wi-Fi-only option, retry/cancel, open/share/delete. Files go to `Movies/Vidora` and `Music/Vidora`, or **any folder / SD card you pick**. **Download all** for playlists and whole channels. Offline playback inside the app. |
| **Player** | Quality picker, audio-only mode, speed 0.25×–3×, loop, sleep timer (incl. *end of video*), skip silence, chapters + **chapter/SponsorBlock markers on the seek bar**, **stats for nerds**, resume where you left off, queue (play next / add to queue / shuffle, **remembered across restarts**), autoplay next, fullscreen with auto-rotate. |
| **Gestures** | Double-tap ±10 s, hold for 2× speed, swipe brightness/volume in fullscreen, pinch to zoom/fill, **seek-bar preview thumbnails** while scrubbing. |
| **Captions & audio** | Caption picker (manual + auto-generated), **auto-translate** into your language, text size, `.vtt` download. **Audio-language picker** for dubbed / multi-audio videos (remembers your choice). **Equalizer**, bass boost and volume boost. |
| **Comments & live chat** | Comments with replies, pinned and creator-hearted badges, tappable timestamps that seek the video. **Live chat** on live streams. |
| **Shorts** | Full-screen vertical swipe player (or turn it off to open Shorts in the normal player). |
| **Community** | **Return YouTube Dislike** counts, **DeArrow** clickbait-free titles & thumbnails (opt-in). |
| **Filters** | Block channels ("Don't recommend channel") and keywords, hide watched videos, hide Shorts — applied to every feed. |
| **SponsorBlock** | Automatically skips sponsors, intros, self-promo, etc. — choose which categories. |
| **Discover** | Country-biased Explore (IP/manual/device country), PeerTube and Commons tabs, plus YouTube category feeds, **Continue watching** row, search with suggestions, history and **voice search**, filters (videos/channels/playlists/music + length, upload date, hide live), channels with tabs, playlists with Play-all/Shuffle. |
| **Library (local)** | Subscriptions + subscription feed, **subscription groups**, history, liked videos, watch later, **your own playlists** (create, rename, reorder, share, download all) — all private, no login. **Incognito mode** (one tap in Library). Export/Import backup as JSON. **Import subscriptions** from NewPipe / LibreTube / FreeTube / Google Takeout CSV / OPML, export as NewPipe JSON. |
| **Notifications** | Optional new-video notifications for your subscriptions (checked every 1–24 h through fast channel RSS feeds, no login needed). |
| **Platform** | Home-screen **now-playing widget**, **Quick Settings tile**, **Android Auto** browsing (Continue watching, Liked, Watch later, Playlists, Trending music), **Android TV** launcher entry with D-pad focus highlighting. English + **বাংলা** (per-app language on Android 13+). |
| **Reliability** | Local **PoToken (BotGuard)** generation like NewPipe, so fewer "not a bot" / HTTP 403 errors; expired stream links are refreshed automatically after long pauses; **in-app updater** from GitHub Releases; crash reporter that opens a pre-filled GitHub issue (no telemetry). |
| **YouTube sign-in (optional)** | Optional session support for eligible account content (not guaranteed to resolve platform blocks), plus your **Recommended**, **Subscriptions feed**, **Liked**, **Watch later** and **History** — still with no ads. One-tap import of your YouTube subscriptions. Likes, dislikes and subscriptions made in Vidora can be **synced to your account** (toggle), and videos can be **saved to your YouTube playlists** (or a new one). |
| **Customisation** | Theme (System/Light/Dark/AMOLED black), Material You dynamic colour, 8 accent colours, corner roundness slider, card or compact-list layout, avatars on/off, custom in-app display name, **6 launcher icons × 4 launcher names** switchable from Settings. |
| **Responsive** | Phones, foldables and tablets: bottom bar on phones, navigation rail + side-by-side player on large screens, adaptive grids, edge-to-edge. |
| **Open links** | Share supported YouTube, curated PeerTube watch/channel links, or Commons `File:` links to Vidora. |
| **Glass UI + iPhone icons** | Frosted-glass top bars, bottom bar, mini player, sheets, dialogs and cards (real blur on Android 12+), **iPhone-style icons** everywhere (Framework7 Icons, toggle in Settings → Appearance). |
| **Fast start** | Touch-prefetch (streams resolve while your finger is still on the card), next-video prefetch, adaptive DASH, a 512 MB **disk cache** of watched segments, **AV1/VP9** when the phone decodes them in hardware, start-time in *Stats for nerds*. |
| **Data saver** | On mobile data: 360p cap, small thumbnails, low-bitrate audio, no preloading. |
| **Cast** | Chromecast / Google TV casting with remote controls (needs Google Play services). |
| **Live About page** | The in-app About page is loaded from [`about/ABOUT.md`](about/ABOUT.md) — edit it on GitHub and every installed app shows the change. |
| **Auto-updates** | New GitHub releases download in the background and install automatically (Android 12+ silently after the first in-app update). |

## ⚙️ Engineering reliability layer

See [ENGINEERING.md](ENGINEERING.md) for architecture and exact verification boundaries.

- Fast-first source updates, stable appended batches and per-provider retry controls.
- Bounded provider health cooldowns, cancellation-aware HTTP, rate-limit handling and request coalescing.
- Room-backed public feed snapshots: show cached rows first, refresh in the background, retain them
  offline. Snapshots exclude signed playback URLs/account feeds and are disabled for incognito searches.
- Home language query hints, local interests, creator diversity, freshness/watched ranking,
  **Why this video?**, **Less like this**, and reset controls. Search remains query-only.
- Error-aware playback recovery, limited URL refresh/codec fallback, retained position and rate-limit pauses.
- Local review-before-share diagnostics; no automatic uploads, URLs, cookies, query or IP data.
- Non-blocking settings startup, low-RAM opaque UI fallback, release-target benchmarks/profile generation.
- Pinned CI actions, dependency verification, wrapper distribution checksum and published APK SHA-256.
- Deterministic emulator release gate, separate from upstream-dependent real-network tests.

## 🌍 Guest multi-platform discovery

- **Explore** is the default Home tab, including when signed in. It interleaves country-topic
  YouTube and Commons results with a smaller global PeerTube sample. This is country-level
  relevance, not GPS, guaranteed local-language content, or a claim about where creators live.
- **Search** sends the submitted query unchanged to each selected provider; it never appends
  Home's country. Source chips: All sources, Public alternatives, YouTube, PeerTube, Commons.
  Channels/playlists/music filters currently use YouTube; other providers support video search.
- **PeerTube** uses public APIs on `tilvids.com`, `framatube.org`, and `video.blender.org`, with
  creator pages, local follows and latest uploads. Only these curated origins are routed.
- **Commons** uses `prop=videoinfo` with current API-supplied WebM/MP4 derivatives. Creator,
  credit, attribution, licence and original file page are retained in the description. Files
  without licence metadata are not listed. Public availability is not unrestricted reuse.
- Local likes, watch later, history, playlists, queue and supported follows need no login.
  PeerTube/Commons comments and account actions are not implemented; they are not login gates.
  Audio-only file downloads require a separately supplied audio rendition; HLS-only videos may
  play without offering a downloadable file. Codec support depends on the phone.
- Requests run independently with bounded timeouts. A failed source produces a notice while
  other results remain. **Find public alternatives** searches *different* public videos when
  playback fails; it does not unlock a restricted video. No guarantee of avoiding IP/platform
  blocks; private, paid, age-gated or other restricted media are not bypassed.

**Home country privacy:** Automatic mode uses `https://api.country.is/`, which sees the device's
public IP. Vidora reads only the ISO country, stores country + lookup time (not the IP), caches
for six hours, and falls back to the device locale on failure. Settings → Content → **Use IP
country for Home** disables lookups; **Content country** offers a manual country and skips the
IP request. Search does not trigger the lookup. Video/API providers also see normal connection
metadata, queries and playback requests. No GPS permission is used. The API's observed country
reflects a VPN/proxy egress when one is active.

**Downloads and rights:** public-source downloads include a `.attribution.txt` companion in
`Download/Vidora` (or the chosen folder), even for WebM. Keep it with the video and consult the
original page before reuse or redistribution. Software licences remain in Settings → Licences;
corresponding GPL source is linked on every APK release page.

Provider references: [PeerTube REST API](https://docs.joinpeertube.org/api-rest-reference.html),
[TimedMediaHandler API](https://www.mediawiki.org/wiki/Extension:TimedMediaHandler/API),
[country.is](https://country.is/).

### Multi-source verification

`./gradlew assembleRelease testDebugUnitTest` includes `GuestSourcesTest`: safe origins and file
URL round trips, public/sensitive/password filtering, HLS-only playback metadata, Commons
rendition safety and attribution, unchanged typed queries, Home country terms, independent
failure handling, continuations, all-provider failure, and coroutine cancellation.

On a phone: continue as guest → Explore → play PeerTube/Commons → save/like/queue → return from
Settings (feed retained) → change manual country (Home changes) → search the same query (no
country injected) → use Public alternatives after a blocked YouTube video → download a public
file and verify its attribution companion. Also check offline errors, unsupported comments,
rotation/PiP, and upgrading the existing installation. CI is not a substitute for these device
and network-dependent checks.

## 🔐 Optional YouTube sign-in

Open **Settings → Account → Sign in with YouTube** (or tap the account icon on Home / the
"YouTube account" section in Library).

1. Vidora shows Google's own sign-in page in a WebView. Your password is typed **only into
   Google's page**. Vidora never sees or stores it.
2. When sign-in finishes, Vidora keeps the youtube.com session cookies and fetches your name and
   avatar.
3. Signed in, you get:
   - Your own feeds on Home and in Library.
   - **Import YouTube subscriptions** into the local library.
   - **Use account for restricted videos**, on by default: a video, search, channel or playlist
     is tried anonymously first. If YouTube refuses (age-restricted, bot check, members-only…),
     Vidora retries once with your account.
4. **Sign out** from Settings wipes the session and the WebView cookies from the device.

**Fallback:** if Google blocks sign-in inside the WebView, use *"Google sign-in blocked? Paste
cookies instead"*. Sign in to youtube.com in a desktop browser, open DevTools → Network, pick a
request to `www.youtube.com` and paste its `Cookie` header. It must contain `SAPISID`.

**Privacy & security**
- The session is encrypted with AES-256-GCM. The key lives in the hardware-backed **Android
  Keystore**, and the encrypted file sits in `noBackupFilesDir`, so it is excluded from cloud
  backups.
- Cookies are only ever sent to `youtube.com` hosts, never to Vidora servers (there are none),
  PeerTube, Commons, country.is, SponsorBlock or googlevideo.
- Google doesn't officially support third-party clients. To be extra safe, you can use a
  secondary Google account.

## 📦 Getting the APK

Every push is built by GitHub Actions (`.github/workflows/build.yml`):

1. Open the repository's **Actions** tab → latest **Build APK** run → download the `Vidora-x.y.z` artifact (a zip containing the APK).
2. Push a tag like `v1.0.0` to automatically publish a **GitHub Release** with the APK attached:
   ```bash
   git tag v1.0.0 && git push origin v1.0.0
   ```
3. Install on your phone (allow "Install unknown apps" for your browser/file manager).

### Signing

By default CI signs with a **shared debug key** (`app/debug.keystore`, password `android`) so that
every build has the same signature and updates install over each other. For your own private
release key, add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `VIDORA_KEYSTORE_B64` | `base64 -w0 release.p12` (PKCS12 keystore) |
| `VIDORA_KEYSTORE_PASSWORD` | keystore password |
| `VIDORA_KEY_ALIAS` | key alias |
| `VIDORA_KEY_PASSWORD` | key password |

```bash
keytool -genkeypair -v -storetype PKCS12 -keystore release.p12 -alias vidora \
        -keyalg RSA -keysize 4096 -validity 10000
```
(Switching from the debug key to a release key requires uninstalling the app once.)

## 🛠 Build locally

Requirements: JDK 17+, Android SDK (API 36). Android Studio Narwhal or newer works out of the box.

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/
./gradlew assembleRelease    # minified, signed with the debug key unless secrets are set
```

## 🧱 Architecture

```
app/src/main/java/com/vidora/app/
├── VidoraApp.kt / Graph            app start-up, extractor init, singletons
├── MainActivity.kt                 Compose host, PiP, deep links, media controller
├── data/
│   ├── ContentRepository.kt        search, kiosks, streams, channels, playlists (NewPipe Extractor)
│   ├── NetworkDownloader.kt        OkHttp transport for the extractor
│   ├── SettingsRepository.kt       DataStore-backed settings
│   ├── LibraryStore.kt             local history / likes / watch later / subscriptions / playlists / groups (JSON)
│   ├── SubscriptionImport.kt       NewPipe / LibreTube / FreeTube / Takeout CSV / OPML import, NewPipe export
│   ├── Community.kt                Return YouTube Dislike + DeArrow clients
│   ├── ContentFilter.kt            blocked channels / keywords / watched / Shorts
│   └── SponsorBlock.kt             SponsorBlock API client
├── account/
│   ├── AccountManager.kt           session state, SAPISIDHASH auth headers, sign-out
│   ├── AccountApi.kt               signed-in InnerTube feeds (recommended, subs, liked, WL, history)
│   └── SecureStore.kt              Android Keystore AES-GCM encrypted storage
├── player/
│   ├── PlayerManager.kt            single ExoPlayer, quality/audio-only/queue/sleep/SponsorBlock
│   ├── PlaybackService.kt          MediaLibraryService → background playback, notification, Android Auto
│   ├── AutoLibrary.kt              browse tree for Android Auto / Wear
│   ├── AudioEffects.kt             equalizer, bass boost, loudness enhancer
│   ├── SessionStore.kt             queue persistence
│   └── StreamInterceptor.kt        client-matching headers for googlevideo requests
├── potoken/                        BotGuard PoToken generator (WebView, ported from NewPipe, GPLv3)
├── notify/                         RSS feed parser + new-video WorkManager job
├── remote/                         home-screen widget, Quick Settings tile, remote control
├── download/
│   ├── Downloads.kt                quality choices + WorkManager queue
│   ├── DownloadWorker.kt           chunked, resumable downloader (foreground worker)
│   ├── Muxer.kt                    lossless video+audio merge (MediaMuxer)
│   └── Storage.kt                  MediaStore publishing (Movies/Vidora, Music/Vidora)
├── ui/                             Compose screens, theme, components, download sheet
└── util/                           formatting, launcher icon switching, updater, crash reporter
tools/gen_icons.py                  generates launcher icons + activity-aliases
```

**Tech:** Kotlin 2.3, Jetpack Compose (Material 3), Media3 ExoPlayer 1.9, WorkManager, DataStore,
Coil, OkHttp, kotlinx.serialization, NewPipe Extractor 0.27.1. Min SDK 26 (Android 8.0), target SDK 36. Unit tests: `./gradlew testDebugUnitTest`.

### Adding launcher icons / names
Edit `ICONS` / `NAMES` in `tools/gen_icons.py`, then run `python3 tools/gen_icons.py` — it rewrites
the drawables, the `<activity-alias>` list in `AndroidManifest.xml` and `LauncherCatalog.kt`.

## ⚖️ Legal

Vidora is an independent project and is **not affiliated with, endorsed by, or sponsored by
YouTube or Google**. "YouTube" is a trademark of Google LLC. Using third-party clients and
downloading may be against YouTube's Terms of Service. Only download content you have the right
to (your own uploads, Creative Commons, or with the creator's permission) and respect copyright
law in your country.

Licensed under **GPL-3.0** (see `LICENSE`), as required by NewPipe Extractor.

### Licences

- [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) — GPL-3.0
- [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media), Jetpack Compose, OkHttp, Coil — Apache 2.0
- [Framework7 Icons](https://github.com/framework7io/framework7-icons) — MIT, © Vladimir Kharlampidi (iPhone-style icons, converted by `tools/icons/gen_ios_icons.py`)
- SponsorBlock, DeArrow and Return YouTube Dislike data — used through their public APIs

## ✏️ Editing the live About page (from a phone)

1. Open <https://github.com/sremonak/YouTube/blob/main/about/ABOUT.md> in the GitHub app or browser
   (until the PR is merged, use the `arena/4ab58c16-youtube` branch instead of `main`).
2. Tap the ✏️ pencil (or ⋯ → *Edit file*), change the text, then tap **Commit changes**.
3. Open Vidora → Settings → **About** — the new text appears within seconds (pull the refresh icon).

Supported formatting: `# Heading`, `## Sub-heading`, `- bullet`, `1. numbered`, `> note`, `---` line,
`**bold**`, `*italic*`, `[link text](https://…)` (a link alone on a line becomes a button) and
`![](https://…image.jpg)` images.

---

## 🇧🇩 বাংলা সারসংক্ষেপ

**Vidora** একটি আধুনিক Android অ্যাপ (Kotlin + Jetpack Compose)।

- 🚫 **কোনো বিজ্ঞাপন নেই** — অ্যাপের ভেতরে কোনো অ্যাড বা ট্র্যাকার নেই।
- 🎧 **ব্যাকগ্রাউন্ড প্লে** — স্ক্রিন বন্ধ বা অ্যাপ বন্ধ থাকলেও চলবে; নোটিফিকেশন ও লক-স্ক্রিন কন্ট্রোল।
- 🖼 **পিকচার-ইন-পিকচার (PiP)**, ফুলস্ক্রিন, অটো-রোটেট।
- ⬇️ **ডাউনলোড** — ভিডিও (144p থেকে 4K) অথবা শুধু অডিও (M4A/Opus), নিজের পছন্দের কোয়ালিটিতে; ফাইল সাইজ আগে দেখায়। `Movies/Vidora` ও `Music/Vidora` ফোল্ডারে সেভ হয়, অফলাইনে অ্যাপেই চালানো যায়।
- ⚙️ **প্লেয়ার** — কোয়ালিটি বদল, অডিও-অনলি মোড, স্পিড (0.25×–3×), লুপ, স্লিপ টাইমার, চ্যাপ্টার, যেখানে থেমেছিলেন সেখান থেকে চালু, কিউ, অটোপ্লে।
- ⏭ **SponsorBlock** — স্পন্সর অংশ নিজে থেকেই স্কিপ করে।
- 🎨 **নিজের মতো ডিজাইন** — থিম (লাইট/ডার্ক/AMOLED), Material You রঙ, ৮টি অ্যাকসেন্ট কালার, কর্নার রাউন্ডনেস, কার্ড/লিস্ট লেআউট, অ্যাপের নাম পরিবর্তন, **৬টি আইকন × ৪টি নাম** — Settings থেকে লঞ্চার আইকন ও নাম বদলানো যায়।
- 📚 **লাইব্রেরি** — সাবস্ক্রিপশন, হিস্টোরি, লাইকড, ওয়াচ লেটার — সব ফোনেই থাকে, কোনো অ্যাকাউন্ট লাগে না; ব্যাকআপ/রিস্টোর।
- 🔐 **YouTube লগইন (ঐচ্ছিক)** — **Settings → Account → Sign in with YouTube**। Google-এর নিজের পেজে লগইন করবেন, পাসওয়ার্ড Vidora কখনো দেখে না। লগইন করলে:
  - বয়স-সীমাবদ্ধ (age-restricted) ভিডিও দেখা যাবে, "confirm you're not a bot" সমস্যাও কাটবে।
  - আপনার Recommended, Subscriptions feed, Liked, Watch later ও History দেখা যাবে, তবু কোনো বিজ্ঞাপন থাকবে না।
  - এক ট্যাপে YouTube সাবস্ক্রিপশন ইমপোর্ট করা যাবে।
  - সেশন Android Keystore দিয়ে এনক্রিপ্ট করে শুধু ফোনেই রাখা হয় এবং শুধু youtube.com-এ পাঠানো হয়। Sign out করলে সব মুছে যায়।
  - WebView-এ Google লগইন ব্লক করলে "Paste cookies" অপশন ব্যবহার করুন।
- 📱 **রেসপন্সিভ** — ফোন, ট্যাবলেট, ফোল্ডেবল সব স্ক্রিনে মানিয়ে যায়।
- 👆 **জেসচার** — ডাবল-ট্যাপে ±১০ সেকেন্ড, চেপে ধরলে ২× স্পিড, ফুলস্ক্রিনে সোয়াইপ করে ব্রাইটনেস/ভলিউম, পিঞ্চ-জুম, সিক-বারে প্রিভিউ থাম্বনেইল।
- 💬 **ক্যাপশন ও অডিও ভাষা** — সাবটাইটেল বাছাই, অটো-অনুবাদ, ডাবিং করা ভিডিওর অডিও ভাষা বদল, ইকুয়ালাইজার ও বেস/ভলিউম বুস্ট।
- 🗨️ **কমেন্ট** (রিপ্লাই সহ), 👎 **ডিসলাইক সংখ্যা** (Return YouTube Dislike), **DeArrow** (ক্লিকবেইট-মুক্ত টাইটেল)।
- 📱 **Shorts** — ফুলস্ক্রিন ভার্টিকাল সোয়াইপ প্লেয়ার।
- 🚫 **ফিল্টার** — চ্যানেল বা শব্দ ব্লক, দেখা ভিডিও লুকানো।
- 🗂 **নিজের প্লেলিস্ট** ও **সাবস্ক্রিপশন গ্রুপ**; NewPipe/LibreTube/Takeout থেকে সাবস্ক্রিপশন ইমপোর্ট।
- 🔔 **নতুন ভিডিও নোটিফিকেশন**, হোম-স্ক্রিন **উইজেট**, **Quick Settings টাইল**, **Android Auto** ও **Android TV**।
- 🛡 **PoToken**, এক্সপায়ার্ড লিঙ্ক অটো-রিফ্রেশ, **অ্যাপের ভেতর থেকেই আপডেট**, ক্র্যাশ রিপোর্ট।
- 🆕 **নতুন** — লাইভ স্ট্রিমে **লাইভ চ্যাট**, **YouTube প্লেলিস্টে সেভ**, পুরো চ্যানেল **Download all**, নিজের পছন্দের **ফোল্ডার / SD কার্ডে** ডাউনলোড, **ইনকগনিটো মোড**, **ভয়েস সার্চ**, সার্চে দৈর্ঘ্য/তারিখ ফিল্টার, হোমে **Continue watching**, সিক-বারে চ্যাপ্টার ও SponsorBlock মার্কার, ভিডিও শেষে স্লিপ টাইমার, **Stats for nerds**, TV রিমোটের জন্য ফোকাস হাইলাইট।
- 🇧🇩 **পুরো অ্যাপ বাংলায়** — Android 13+-এ Settings → Apps → Vidora → Language থেকে বাংলা বেছে নিন।

**APK পাওয়ার উপায়:** GitHub-এর **Actions** ট্যাবে গিয়ে সর্বশেষ "Build APK" রান থেকে `Vidora-x.y.z` আর্টিফ্যাক্ট ডাউনলোড করুন, অথবা `v1.0.0` ট্যাগ পুশ করলে Releases-এ APK চলে আসবে।

⚠️ এটি YouTube/Google-এর অফিসিয়াল অ্যাপ নয়। শুধুমাত্র সেই কনটেন্ট ডাউনলোড করুন যেটার অধিকার আপনার আছে।
