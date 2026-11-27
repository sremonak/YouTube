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
