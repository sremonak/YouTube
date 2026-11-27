# Discovery and playback engineering

## Boundaries
No authentication bypass, no automatic transfer to a different video, no private analytics server.
Search queries are unchanged. Home country relevance is not proof of creator location or language.
Existing APK signing and Update/Later flow remain unchanged.

## Implemented
- `DiscoveryRepository` streams each finished provider via Flow. Completed batches append without
  reordering visible rows. Each failed provider has its own retry; continuation remains in memory.
- `OpenVideoRepository` serializes requests per origin, deduplicates recent responses for 15 seconds,
  closes HTTP calls on cancellation, honours HTTP/API rate-limit pauses and uses bounded circuit
  cooldowns. The contactable User-Agent is not a UI repository link. No aggressive retry loop.
- Public Explore/search rows use a disposable Room snapshot store, bounded to 32 records of at most
  128 KiB each and seven days. Snapshot keys are SHA-256 digests. No signed playback URLs or account
  feeds are saved. Restored rows appear before revalidation; missing/offline sources don't erase them.
  Search persistence is disabled with search history off or incognito; privacy changes clear snapshots.
  This is snapshot caching, not serialization of provider continuation lambdas or a Paging3 migration.
- Home has explicit language query hints and curated local interests. Freshness, watched history and
  creator diversity rank incoming source batches. “Less like this” hides that video in Explore only;
  “Why this video?” explains the heuristic. Reset controls preserve the local library.
- Playback separates access/removal errors, rate limiting, expired-URL refresh, decoder fallback and
  transient network retries. Attempts and delay are bounded; position is retained. Forced refreshes
  cannot reuse a stale in-flight prefetch. No code claims access is guaranteed.
- Diagnostics are local, bounded, schema-limited events with numeric metrics. Arbitrary exception
  strings, URLs, queries, titles, IPs, cookies and tokens are not accepted. Users review before sharing.
  Incognito disables collection. No automatic reporting.
- Startup no longer blocks on a synchronous DataStore read. Low-RAM devices get the opaque UI fallback.
- A dedicated release-target Macrobenchmark/ProfileGenerator module measures startup/frame timing and
  produces profile files. Emulator timings are diagnostic, not real-device speed claims. Generated
  profiles require review/measurement before replacing the existing bundled profile. Because the
  measured target is the minified release APK, extracted profile descriptors must also be reconciled
  with that build's R8 mapping before reuse as source-level build input; raw traces are not silently
  copied into the shipping profile.
- Build actions are pinned to commits; dependency updates are proposed, not auto-merged. SHA-256 APK
  inventory is published with releases. Dependency checksums were bootstrapped from CI resolution, component/configuration reviewed and
  committed for strict verification. Signatures are not verified; initial trust still relies on the repositories. A checksum authenticates bytes against a reviewed baseline, not
  a publisher's identity. No keystore rotation or anti-modification guarantee is implied.

## Release verification
`assembleRelease testDebugUnitTest assembleDebugAndroidTest :benchmark:assembleBenchmark lintDebug`
precedes the deterministic API 34 emulator gate. That gate uses a self-generated 2-second AVC/AAC
fixture: guest Home/search, local Watch later, PeerTube/Commons routing/playback, activity recreation,
and Room close/reopen/clear. The same emulator also installs the retained v1.0.108 release,
completes its actual guest welcome UI, force-stops/relaunches it, installs the new release in-place
without uninstalling, and checks package UID plus guest-preference retention after upgrade/restart.
This is an installation/preference continuity smoke, not a full library/download migration test.
The debug-only test injection seam is ignored in release builds.
Only a successful gate can publish an APK. Reports are uploaded even on failure.

Real-network YouTube extraction tests remain separate (manual/weekly); upstream outages must not be
mistaken for deterministic regressions. The performance workflow stores JSON/traces/generated profiles.
Physical-phone battery, low-memory, PiP, long downloads and full library/download migration still
require device validation. The new signed upgrade/process-restart smoke must pass CI before being
reported as verified. Do not label unrun or failing checks as passed.

Performance execution: `./gradlew :benchmark:connectedBenchmarkAndroidTest` targets the release APK.
`ProfileGenerator` produces a guest startup/scroll/search profile; `PerformanceBenchmarks` records
startup and scroll metrics. Emulator output is not a claim of physical-phone improvement. Tests
that depend on network-fed content can vary and must not be treated as deterministic rendering
comparisons without a controlled dataset. The release fixture gate, not these timing tests, gates
APK publication. Gradle wrapper distribution bytes are also pinned to the Gradle release's SHA-256.
