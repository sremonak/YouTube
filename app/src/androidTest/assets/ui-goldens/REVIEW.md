# Reviewed UI fixture baselines

Captured by API 34 Google APIs x86_64 / SwiftShader, animations disabled,
from commit `4cd8dd5aefc27adb2473114b22ee4e958f5037c0` in run `45983591488`.
Candidates were exported in PR 1 comments 7216295402–7216297314 and individually
opened for visual inspection before check-in.

Review: header/card/action do not overlap; text and fixed placeholder render in both
light and dark; Bengali glyphs render; large-font subtitle wraps and card text
ellipsizes rather than overflowing; overflow/refresh touch bounds pass 48dp assertions.
Landscape/tablet use forced logical available sizes (the capture scales to the physical
emulator display); these are fixture contracts, not physical tablet/fold certification.
The glass case checks the fallback/header treatment without a live backdrop.
Actual Home/Search keyboard/mini-player integration and manual TalkBack remain outside
this six-image matrix. No changed pixels were automatically accepted.

SHA-256:

- `amoled-glass.png`: `dd6af2430d56f7830d68eb9ce38adb483702baccb386e2f7b2063258ed8ca213`
- `dark-large-font.png`: `53ef36519ba0d9f5b8cb3b601d9ca612f7f7d41d8bed4d1452492a6e9eb8245d`
- `landscape.png`: `b9c4d479b9388b9aff38c1880ddb80cddbb57b8eaaca92c5f1ef4d067730c8e0`
- `light-compact.png`: `049f1461e911b1e8e5d30946c96ec97058ae7d1f1d8258cc9147e202ae531ed0`
- `light-phone.png`: `b7df0af4694ac201765c7a40ec2d557809e4af1827cb68044a45251c9eea307a`
- `tablet.png`: `719c422a6b1fd93161b4b18f489f7fe50631858fad57cdc80957fdcdf3f0608c`
