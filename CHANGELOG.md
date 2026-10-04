# Changelog

All releases are on https://github.com/Qutaiba-Khader/vidchain/releases (each with its complete source and a bill of
lading). Versions before 1.0.0 were marked "not fully tested yet": they passed the fast checks and a short emulator
traffic test; the one full emulator test pass ran before 1.0.0.

## 1.0.0 (not released yet)
- Checked by the one full emulator test pass: fallbacks off compared with the recording of the original app, fallbacks
  on and 11 faults compared with the expected outcome of every test link (results in the release notes).
- Review fixes: a deleted download's id (reused by the app) no longer inherits its old fallback state; deleting a
  download stops its fallbacks; a resumed download waiting for a slot and a yt-dlp download that is still merging are
  not failures; files count as delivered only once saved and listed; fallbacks the system interrupted continue with
  their next method; engines without progress for 3 minutes are stopped; no fallback request reaches your local network
  through a redirect; cookies go to their exact site only and never over plain http; trace lines never keep signed-link
  tokens; torrents of non-media files are kept; disguised error pages and cut-off files are never accepted.
- Sharing: magnet links and texts with a link in them are taken before the app would say "Invalid URL"; VidChain's
  yt-dlp offer waits for the app's own browser and stays away once the app started its own download.
- Open-source licences screen (Settings -> VidChain fallbacks).

## 0.5.0
- aria2c (A: several connections, magnet links, .torrent, Metalink), gallery-dl (G), you-get (U), Streamlink (T) and
  lux (X, 64-bit phones) with their own Python and native runtimes; per-ABI method matrix checked on every build.

## 0.4.0
- yt-dlp nightly (N), isolated from the app's own yt-dlp, fetched from GitHub and SHA-256 checked.

## 0.3.0
- File-host links (L), page scan (H), WebView page player catcher (W), ffmpeg (F), NewPipe streams (P), Media3 (M),
  Android download manager (D).

## 0.2.0
- Plain download (O), redirect unwrap (R), session retry (S), yt-dlp on any link (Y, also for links the app turns
  away), YouTube client retry (C).

## 0.1.0
- The fallback coordinator, the delivery check, the trace log, Settings -> VidChain fallbacks (a self-check method only).

## 0.0.1
- The original app without ads, tracking, self-updater, kill switch and developer cloud sync; own package and signature.
