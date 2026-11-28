# Release authenticity and limitations

## What the app checks

- R8 shrinking, resource shrinking and class/member-name obfuscation are enabled for releases.
- Before initializing Graph, account access or playback, release builds require the original
  `com.vidora.app` package, a non-debuggable manifest and exactly the signing certificate pinned
  at build time. Missing/invalid signing metadata fails closed. Development `.debug` builds
  intentionally bypass startup verification.
- Android's APK signature validation detects edits to signed manifest/resources/code. Repacking
  an APK normally requires signing it again; a different certificate is rejected by the startup
  check (unless the check itself has been patched).
- Downloaded updates are checked for package, signer, non-debuggable flag and a strictly newer
  version code before installation, including the legacy installer fallback. Android's installer
  then performs the cryptographic APK signature verification. Invalid downloads are deleted.
- App backup is disabled and cleartext HTTP traffic is disallowed.

This is **defence in depth**, not extraction-proof encryption, DRM, Play Integrity attestation,
server-side account authorization or a guarantee that modified clients cannot run. An attacker
who controls a device/client can patch local checks. No Google password/account token is sent to
an integrity service. The app's supported in-app launcher aliases/name/icon settings remain valid.

## Critical: current signing key is public

`app/debug.keystore` was deliberately shared to keep past sideloaded updates compatible. Its
password is public. Anyone can re-sign a modified APK with that key; a certificate check CANNOT
distinguish such a copy. Obfuscation does not repair that trust boundary. Do not describe this
release as securely anti-clone or claim exclusive author identity.

For stronger distribution identity, the maintainer must migrate to a private release signing
key (the build already supports VIDORA_KEYSTORE_*). Do not publish private keys or send their
passwords in chat. Certificate rotation requires a tested Android signing-lineage migration;
otherwise existing users must back up their data and reinstall the privately signed app. This
change deliberately does not rotate the key, delete it, or break existing update compatibility.
A future private-key migration must update runtime/update pin policy as well.

## Links and open source

Normal About/profile/settings/crash-report screens no longer navigate to GitHub. Updates stay
in-app; missing APK assets show an error instead of opening a release web page. Repository lines
are filtered from old cached/live About text and URLs removed from release notes. This is a UI
choice, NOT endpoint secrecy: network traffic, assets and reverse engineering can reveal the
update/profile host. The public source repository is not made private.

An offline Licences dialog retains GPL licence text and required notices, without a source URL
or browser shortcut. The APK download/release page provides the matching source archive and
build instructions. This project and NewPipe Extractor are GPLv3;
recipients keep their rights to inspect, modify, rebuild and redistribute source. Authenticity
checks do not revoke those rights. The owner can still edit `about/developer.txt` and upload
`about/photo.jpg` directly from GitHub on a phone; owner-edit shortcuts are no longer in the app.

## Verification

CI runs `assembleRelease testDebugUnitTest`. IntegrityPolicyTest covers original/untrusted
signers, package changes, debuggable manifests, absent/multiple signatures and invalid pins.
PublicTextTest covers old/live repository links and update-note URLs. These are policy tests,
not proof of resistance to arbitrary APK patching.

Device checks before changing signing policy:

1. Install the unmodified release and check launch, Google sign-in and background playback.
2. Re-sign a copy with a DIFFERENT test key (without patching checks). On a disposable emulator,
   uninstall the original, install the copy and confirm startup is rejected before login.
3. Rename/replace icons in a disposable APK copy and re-sign with the test key: also rejected.
4. Verify normal Settings launcher name/icon changes still work.
5. Offer an APK with a wrong certificate/package or older version to the updater: rejected.
6. Check About, Settings, crash reports and Update/Later show no repository navigation; legal
   licence text remains available offline; matching source is linked on the download page.

The sandbox has no local Android SDK/emulator. Do not claim device checks ran unless recorded
separately. Private-key migration and server-backed attestation are not implemented here.

## Sign-in assistance is not native Google OAuth

The phone's account picker supplies only a user-selected email hint. It does not authenticate
YouTube or extract credentials from Google Play Services. It is now available from regular
sign-in as well as welcome; cancelling leaves the current screen unchanged.

The embedded sign-in reuses its own existing WebView cookies rather than clearing them on every
attempt (explicit sign-out still clears them). Password Autofill is enabled; an HTTPS host label
and a top-level Google/YouTube allowlist protect the sign-in surface. Google may block embedded
sign-in regardless. Passkey/phone-confirmation suggestions apply ONLY if Google offers those
methods; this change does not add native passkey/Credential Manager integration or promise
passwordless access.

Forgot-email and account-recovery links open official Google pages in the user's browser.
Browser and native Google sessions cannot be copied into Vidora. A fully native authorization
flow would require maintainer-owned OAuth configuration and a compatible YouTube API/account
architecture; an OAuth ID token alone cannot authenticate the current YouTube cookie APIs.
