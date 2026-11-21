# Vidora — ad-free video client for Android

Vidora is a modern, fully native Android app (Kotlin + Jetpack Compose + Media3) for watching
YouTube content **without ads**, with **background playback**, **picture-in-picture**,
**video/audio downloads in the quality you choose**, SponsorBlock and a fully customisable
design — no Google account, no tracking, everything stored on your device.

> বাংলা সারসংক্ষেপ নিচে দেওয়া আছে ⬇️

---

## ✨ Features

| Area | What you get |
|---|---|
| **No ads** | Streams are fetched directly with [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) — no ads, no ad SDKs, no trackers inside the app. |
| **Background play** | Keeps playing with the screen off or the app closed. Notification, lock-screen, Bluetooth & headset controls (Media3 `MediaSessionService`). Video decoding pauses in the background to save battery. |
| **Picture-in-picture** | Auto-PiP when leaving the app (Android 12+ seamless auto-enter) or manual PiP button. |
| **Downloads** | Download **video** (144p → 4K) or **audio only** (M4A/AAC or WebM/Opus, every bitrate) with size estimates. High-resolution video+audio is merged on-device losslessly using `MediaMuxer` (no FFmpeg, small APK). Resumable 8 MB chunks, progress notifications, Wi-Fi-only option, retry/cancel, open/share/delete. Files go to `Movies/Vidora` and `Music/Vidora`. Offline playback inside the app. |
| **Player** | Quality picker, audio-only mode, speed 0.25×–3×, loop, sleep timer, skip silence, chapters, resume where you left off, queue (play next / add to queue / shuffle), autoplay next, fullscreen with auto-rotate. |
| **SponsorBlock** | Automatically skips sponsors, intros, self-promo, etc. — choose which categories. |
| **Discover** | Home feed (personal "For you" mix built locally from your history, Live, Music, Gaming, Podcasts, Movies), search with suggestions & history, filters (videos/channels/playlists/music), channels with tabs, playlists with Play-all/Shuffle. |
| **Library (local)** | Subscriptions + subscription feed, history, liked videos, watch later — all private, no login. Export/Import backup as JSON. |
| **Customisation** | Theme (System/Light/Dark/AMOLED black), Material You dynamic colour, 8 accent colours, corner roundness slider, card or compact-list layout, avatars on/off, custom in-app display name, **6 launcher icons × 4 launcher names** switchable from Settings. |
| **Responsive** | Phones, foldables and tablets: bottom bar on phones, navigation rail + side-by-side player on large screens, adaptive grids, edge-to-edge. |
| **Open links** | Share any YouTube link to Vidora, or open `youtube.com` / `youtu.be` links with it. |

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
│   ├── LibraryStore.kt             local history / likes / watch later / subscriptions (JSON)
│   └── SponsorBlock.kt             SponsorBlock API client
├── player/
│   ├── PlayerManager.kt            single ExoPlayer, quality/audio-only/queue/sleep/SponsorBlock
│   ├── PlaybackService.kt          MediaSessionService → background playback + notification
│   └── StreamInterceptor.kt        client-matching headers for googlevideo requests
├── download/
│   ├── Downloads.kt                quality choices + WorkManager queue
│   ├── DownloadWorker.kt           chunked, resumable downloader (foreground worker)
│   ├── Muxer.kt                    lossless video+audio merge (MediaMuxer)
│   └── Storage.kt                  MediaStore publishing (Movies/Vidora, Music/Vidora)
├── ui/                             Compose screens, theme, components, download sheet
└── util/                           formatting, launcher icon switching
tools/gen_icons.py                  generates launcher icons + activity-aliases
```

**Tech:** Kotlin 2.3, Jetpack Compose (Material 3), Media3 ExoPlayer 1.9, WorkManager, DataStore,
Coil, OkHttp, kotlinx.serialization, NewPipe Extractor 0.27.1. Min SDK 26 (Android 8.0), target SDK 36.

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
- 📱 **রেসপন্সিভ** — ফোন, ট্যাবলেট, ফোল্ডেবল সব স্ক্রিনে মানিয়ে যায়।

**APK পাওয়ার উপায়:** GitHub-এর **Actions** ট্যাবে গিয়ে সর্বশেষ "Build APK" রান থেকে `Vidora-x.y.z` আর্টিফ্যাক্ট ডাউনলোড করুন, অথবা `v1.0.0` ট্যাগ পুশ করলে Releases-এ APK চলে আসবে।

⚠️ এটি YouTube/Google-এর অফিসিয়াল অ্যাপ নয়। শুধুমাত্র সেই কনটেন্ট ডাউনলোড করুন যেটার অধিকার আপনার আছে।
