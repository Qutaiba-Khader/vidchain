# VidChain

VidChain (package `org.websnake.vidchain`) is an Android video downloader **based on [AIO Video Downloader](https://github.com/shibaFoss/AIO-Video-Downloader)
by shibaFoss** - an independent, non-commercial derivative, **not** the official app and not endorsed by
its author (see `ATTRIBUTION.md`).

- No ads, no tracking, no self-updater, no remote kill switch, no developer cloud sync.
- Everything runs on the phone; no servers.
- Planned: when the original download method fails, further on-device methods are tried one after
  another (the original method always runs first, unchanged).

## Install

- **Obtainium (updates itself):** [Add VidChain to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%257B%2522id%2522%253A%2522org.websnake.vidchain%2522%252C%2522url%2522%253A%2522https%253A%252F%252Fgithub.com%252FQutaiba-Khader%252Fvidchain%2522%252C%2522author%2522%253A%2522Qutaiba-Khader%2522%252C%2522name%2522%253A%2522VidChain%2522%252C%2522additionalSettings%2522%253A%2522%257B%255C%2522autoApkFilterByArch%255C%2522%253Atrue%252C%255C%2522includePrereleases%255C%2522%253Afalse%252C%255C%2522sortMethodChoice%255C%2522%253A%255C%2522date%255C%2522%257D%2522%257D)
- **Or download the APK:** [phones (arm64-v8a)](https://github.com/Qutaiba-Khader/vidchain/releases/latest/download/vidchain-arm64-v8a.apk) ·
  [older phones (armeabi-v7a)](https://github.com/Qutaiba-Khader/vidchain/releases/latest/download/vidchain-armeabi-v7a.apk) · [x86_64](https://github.com/Qutaiba-Khader/vidchain/releases/latest/download/vidchain-x86_64.apk) ·
  [x86](https://github.com/Qutaiba-Khader/vidchain/releases/latest/download/vidchain-x86.apk) · [universal](https://github.com/Qutaiba-Khader/vidchain/releases/latest/download/vidchain-universal.apk)
- VidChain installs next to the original AIO Video Downloader (different package: `org.websnake.vidchain`).
- Every release carries its complete source (`SOURCES-<tag>.tar.zst`) and a `bill-of-lading.json` with checksums.

Signing certificate SHA-256 (every VidChain APK is signed with it):
`c4497a1304b54773de0c2c961e89c0b0c832c09b21eac45b2053de3c52c320c2`

Privacy: VidChain collects nothing (`PRIVACY.md`).

Licence: `LICENSE.md` (Non-Commercial Use Free License, from the original app) plus third-party
licences in `THIRD_PARTY_NOTICES.md`. Source provenance: `PROVENANCE.md`, `VENDORED_FROM`, `upstream/`.
