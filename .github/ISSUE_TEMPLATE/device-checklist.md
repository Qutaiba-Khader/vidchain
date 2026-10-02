---
name: Owner device checklist
about: Short check of a release on the owner's phone (non-blocking for the build pipeline)
title: "Device checklist: vX.Y.Z"
labels: device-checklist
---

Release: vX.Y.Z — https://github.com/Qutaiba-Khader/vidchain/releases/tag/vX.Y.Z

Tick what works; write what does not. Nothing here blocks the pipeline - findings become tasks.

- [ ] Obtainium: added with the README link (public repo, no token) and installed `vidchain-arm64-v8a.apk`
- [ ] VidChain installed **next to** the old AIO Video Downloader (the old app is still there and still opens)
- [ ] App name "VidChain", new icon, no ads, no "watch ad to download"
- [ ] Share one YouTube link from the YouTube app (Share -> VidChain) -> pick a quality -> the download finishes and the file plays
- [ ] Settings: no "check for update" row, no cloud sign-in; "Feedback" opens a GitHub issue page
- [ ] Morphe `external_downloader_name`: switch to `org.websnake.vidchain` only from the v0.3.0 checklist on (stays on the old app until then)

Notes:
