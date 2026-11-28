# UI and feed refresh engineering

## Feed freshness

The old memory page considered itself loaded forever while its object remained alive. Re-fetching the
same fixed country query also often returned an identical first page. Home now checks successful
fetch age on resume and once a minute while resumed at the top; a five-minute-old page revalidates.
Returning quickly from Settings retains the feed. Reading further down the feed does not trigger
background replacement. Explicit **Refresh feed** always requests new data and reports when providers
actually return the same items, rather than falsely promising new videos.

Public Explore rotates through all selected interests (or a small general discovery pool), adds a
rolling 30-day YouTube search date term and broadens the candidate pool with one continuation on
subsequent refreshes. Commons/PeerTube receive the current topic. Search queries are never rewritten.
This is not an official regional chart or a guarantee that every upstream response contains new items.
Signed-in, non-incognito users start on their YouTube recommendation tab; Explore remains available.

## Design and behavior

- Shared spacing, 48dp touch dimensions, sheet headings and sheet shape; custom accent foregrounds
  choose black/white by measured luminance contrast instead of always using white.
- Home keeps Search beside Update; Downloads/library/preferences move to Home options. Region/source
  explanations are on demand. Continue watching can be hidden without erasing local history.
- Search keeps source chips, with advanced type/date/duration/live filters in a resettable sheet.
  Keyboard-open layouts hide bottom chrome and apply IME padding. Incognito doesn't display stored
  search history or request background autocomplete suggestions.
- Guest library copy emphasizes local saved content rather than requiring account access.
- Player sheets share headings; seekbar has a 48dp hit area and accessibility set-progress semantics.
  Mini-player adapts controls/thumbnail size for narrow/large-font windows and confirms close/swipe-stop.
- Root chrome collects only current-video presence and active-download count, not all progress updates.
  Video cards collect only their own saved playback position. Visible state is lifecycle-aware.
  Root backdrop capture is disabled when no floating chrome needs it; blur radius is bounded.
  System-disabled animations use static loading placeholders and opaque app chrome.
- File import/export is bounded (8 MiB imports), interruptible background I/O, with progress/cancel,
  error messages, and explicit confirmation before replacing the local library. Existing Settings
  search highlighting and grouped categories are preserved instead of duplicating those controls.
- Available-width/font-scale navigation adaptation and compact cards improve small/multi-window/
  large-font layouts. This is not certification of every fold hinge/posture or physical phone.

## YouTube history

The user's consent is honored through the existing signed-in account session and Watch history sync
setting (already on by default). Guest and incognito sessions never report. A report is delayed until
15 seconds of actual continuous playback; seeking/buffering restarts that interval. Only the actual
media interval is reported, not a fabricated full-video watch. Tracking endpoints must be HTTPS on
an explicit YouTube host allowlist before account headers can be attached. Successful reports
invalidate account recommendation/history memory caches; failures remain visible in Account check.
This is best-effort authenticated compatibility behavior, not a guarantee of immediate algorithm
changes, an official public watch-history API, or an account/login restriction bypass.

## Release gate and visual review

The mandatory emulator gate includes changing-provider-result refresh, honest unchanged refresh,
search filter reset, existing guest discovery/save/playback and Room/privacy/signed-upgrade tests.
`UiVisualContractTest` captures offline fixed-content cases for light/dark/AMOLED+glass, 1.8x font,
compact list, landscape and tablet-sized windows. It asserts usable touch bounds and compares pixels
against reviewed source PNG assets (per-channel tolerance 12; at most 1% changed pixels). Missing
baselines fail rather than auto-accepting a new appearance. Captured candidates/reports are artifacts;
only explicit reviewed assets become regression baselines. The matrix is not a substitute for
manual TalkBack, physical foldable, battery or real-phone visual/performance review.

## Android 11 crash / repeated refresh follow-up

The 15-second watch-history job inherited the app's Default dispatcher and then read
Media3 getters off the main thread. It now explicitly uses Main through suspension.
A 40-second offline fixture waits for the real scheduled history job to complete, so
short guest fixtures can no longer hide this regression. CI also runs the guest/crash
suite on API 30 before the existing API 34 visual/signed-upgrade gate.

Home no longer displays the permanent region/recommendation and provider-status rows:
Refresh feed and Feed status live in Home options, pull-to-refresh remains, and refresh
feedback is transient. Search and Update stay in the toolbar. Public discovery advances
bounded in-memory continuation cursors rather than repeatedly fetching page two; unseen
retrieved candidates precede already displayed ones. Account history success invalidates
both the cache slot and live Paged reference so returning Home actually revalidates.
History sync defaults on but still respects sign-in, the user's sync setting and incognito.
No guest library is uploaded; YouTube may still return unchanged recommendations.

## Account/Shorts/subscriptions and direct navigation follow-up

Home actions are now separate 48dp icons rather than a profile dropdown; Downloads
has its own bottom destination. Device history remains the primary local history even
when an account is present, with a separate YouTube history destination. Measured
continuous playback intervals are stored separately from resume positions; existing
backups migrate with zero measured watch time, never invented full-video watches.

History delivery now uses authenticated WEB tracking only, rejects redirects/unsafe
hosts, surfaces transport errors, and checks the remote history feed before saying
"Confirmed". A successful HTTP acknowledgement alone is labelled sent/unconfirmed.
The playback threshold is three seconds or half a short video's duration (minimum
500ms), with up to three attempts per playback. An explicit Sync recorded history
operation retries up to 50 measured pending records, respecting session/privacy.
Prior resume-only records cannot be reconstructed into honest watch intervals.

Account-feed invalidation emits a revision collected by open Home/account/subscription
screens. Repeated identical recommendation first pages advance actual server pages,
not just a random reorder. Account requests bypass HTTP caches. Dedicated FEshorts
browse has bounded search fallback, continuation support and visible refresh/retry;
unrelated long videos are no longer inserted to disguise an empty Shorts feed.
Subscription groups request their own channels; channel failures are visible, and a
mixed local PeerTube list no longer disables the signed-in YouTube subscription feed.

CI fixtures cover delivery-vs-confirmation, rejection/cancellation, recommendation
page progression, Shorts/fallback/continuations, partial subscription failures, old
history migration, and direct Home/Downloads/Shorts navigation. They do not prove a
real private YouTube account accepted a write: no user session or Google account is
available in CI. The app must show that account-specific result instead of promising
that all upstream/account/network-dependent features always work.

## Android 11 foreground-service deadline / calm Home follow-up

Media3 can enqueue startForegroundService during playing and deliver that start after
pause/stop or an empty timeline. PlaybackService previously relied entirely on a future
Media3 READY notification. It now acknowledges every delivered onStartCommand with a
synchronous ServiceCompat.startForeground **before** session/player lazy construction.
A pre-existing Media3 notification is reused; the bootstrap and the provider share ID
1001/channel playback, so controls/artwork are not replaced by a second notification.
Bound-only idle Home does not promote the service. A bounded startup cleanup removes
an idle bootstrap; ongoing/paused media ownership returns to Media3 without an update
loop. Idle service restarts are non-sticky; this does not autoplay a saved queue.

The mandatory API 30/API 34 gate starts the real foreground service with an empty
player, repeats queued starts during rapid local playback/stop, crosses the real Android
watchdog interval, and checks idle foreground/notification cleanup. These are emulator
regressions, not certification of the Symphony vendor firmware.

The Home utility icon tray is removed. The toolbar contains only brand, Search and
Update; feed-category chips remain navigation. Library/Account/Settings stay under You,
Downloads remains a bottom destination, pull/reselect refresh remains, and TalkBack has
a custom Refresh feed action. Continue watching has an in-section Hide action and a
persisted Content-settings switch. Region/sync explanations live in Settings rather
than an always-visible Home row. Shared card/sheet visual baselines remain unchanged.

## Coordinated collection/player upgrade (user-approved scope)

This update adds workflows, not a second Home toolbar. Existing sleep, gesture,
equalizer, download and playlist features are not counted as new.

- Saved moments: explicitly named local video timestamps, player settings sheet,
  searchable/sortable library destination, rename/remove/share, and JSON backup
  migration with an empty default for old libraries. Incognito hides moments and
  rejects saving at the confirmation boundary. A bookmark is never measured watch
  time or proof of a YouTube history write. Opening a saved moment uses a completed
  local download when available, otherwise the original video link; timestamp
  sharing depends on the destination provider supporting its time parameter.
- Queue: bounded reorder, shuffle only upcoming entries, clear upcoming, deduplicated
  batch append, and save the queue as a local playlist. Changing order must preserve
  the current media item/position and update its queue index.
- Device library: tokenized title/creator search, stable sort, explicit duration
  buckets (unknown/live excluded when a length bucket is chosen), multi-selection,
  select matching/deselect, play/shuffle/queue, playlist from a selection, and
  confirmed local-only removal. Removing local likes/history never silently sends
  unlike/history-deletion requests to the account; the confirmation explains scope.
- Downloads: query/type/status intersections, stable sort, completed-byte summary
  excluding unfinished estimates, retry eligibility guards and matching-active
  cancellation confirmation. Completed files are protected by a fresh status check.
  Retry restarts work; this is NOT HTTP range-resume or pause. Async persistence
  snapshots the latest state under the write mutex, avoiding stale queued saves.
- Playlists: search/filter/sort views, independent duplicate, and persist a chosen
  sort without removing hidden items. Manual reordering is disabled in filtered or
  sorted views to avoid misleading indices. Playlist tools replace a crowded header.
- Shorts: persistent Fit/Fill, accessible playback tap label, per-video retry and
  continuation retry without discarding the current feed. Search has a local-history
  clear confirmation independent of watch/account history.

CollectionUpgradeTest covers pure filters, bucket boundaries, ordering, queue identity,
download eligibility/sizes, and old/new bookmark serialization. The mandatory API 30
and 34 CollectionUpgradeGateTest exercises actual selection/confirmation, bookmark
privacy/backup, timed offline playback/queue changes, protected batch cancellation,
independent playlist duplication and search-history clearing. New screen captures are
review candidates only; existing visual baselines are not autoaccepted. Android 11
foreground watchdog and signed v108 upgrade gates remain mandatory. No private account
or physical OEM firmware is available in CI.
