# Changelog

All releases are on https://github.com/Qutaiba-Khader/vidchain/releases (each with its complete source and a bill of
lading). Versions before 1.0.0 were marked "not fully tested yet": they passed the fast checks and a short emulator
traffic test; the one full emulator test pass ran before 1.0.0.

## 1.1.0
- Checked by the full emulator test pass ([run 37225016775](https://github.com/Qutaiba-Khader/vidchain/actions/runs/37225016775), 55 / 55).

From the owner's second round (INBOX #8):
- New installs start in dark mode; an existing choice is kept. VidChain's own screens follow the switch.
- Settings redesigned: cards per section, a switch showing the real state of every on/off setting, one-line
  descriptions of what each setting does. Removed what could not work or did nothing: the account / sign-in block
  and the account screen, every "This feature is currently not available" entry, "Enable Daily Suggestions" (kept
  off), both "Other Advanced Settings" (and the browser screen behind it, whose rows did nothing), "Default Download
  Folder" (its folder picker saved nothing; "Download Location" is the setting that works), "Enable Adblocker" (it
  changed nothing in the app).
- No splash screen: the app opens its main screen directly (the splash only waited 1.2 s on an animation).
- Fixed bugs inherited from the original app: a page that answers 404 is no longer fetched thousands of times a minute,
  and an HLS link whose playlist fails no longer crashes the app on every start; a downloaded web page, empty file or
  cut-off file is no longer called "Completed" (it fails, and VidChain's fallbacks can try other methods); after one
  shared link opened in the browser, later shares work again without restarting; picking a page's single "Generic"
  format downloads it instead of failing; cookies reach yt-dlp; the Referer keeps the page address on the same site.
- VidChain also notices a download that fails within seconds of the app starting, and a new download that the app
  gives an old download's number.

## 1.0.1
From the owner's first phone test of 1.0.0:
- The quality picker shows each option's size (exact, or approximate marked with ≈) and only the resolutions the video
  really has; before, a YouTube link whose formats the app could not read showed nine resolutions, all "N/A".
- The battery-optimization request appears at most once, never when it is already off, and "Disable Now" asks Android
  directly for this app.
- New installs download to the public Downloads folder; the private folder stays a choice, and existing settings are kept.

## 1.0.0
- Checked by the one full emulator test pass ([run 37168907909](https://github.com/Qutaiba-Khader/vidchain/actions/runs/37168907909),
  55 / 55): fallbacks off identical to the recording of the original app on all 22 test links, fallbacks on and 11
  faults as expected for every link.
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
