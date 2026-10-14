# Changelog

All releases are on https://github.com/Qutaiba-Khader/vidchain/releases (each with its complete source and a bill of
lading). Versions before 1.0.0 were marked "not fully tested yet": they passed the fast checks and a short emulator
traffic test; the one full emulator test pass ran before 1.0.0.

## 1.2.1
Owner's report (2026-10-14): Web Video Caster downloaded a Facebook reel at once, while VidChain's test said every
method failed on Facebook.
- That test link was a removed Facebook video; on the owner's reel 1.2.0 already shows the picker in 1.3 s and saves
  the video (1080p) about 14 s after Download. The test now uses the reel.
- Method H (reads the video off the page, like a browser's video detector) now works on Facebook and is the fastest
  one there (2-4 s): it asks for the page the way a browser does (Facebook refused the bare request with an error
  page), looks at it as a phone's browser when it showed nothing, and reads the player's own files (Facebook's video
  addresses have no ".mp4"): the shown video only, never one the page preloads next, in HD (720p) before SD. When
  the user picked a higher quality than the page offers, H leaves the download to the methods that can deliver it.
- yt-dlp never gets a phone browser's User-Agent for Facebook (its Facebook reader fails on the mobile page).
- A page built to look like endless escaped addresses can no longer stall or crash method H.
- Method D (Android's own downloader) gives up after 75 s without a first byte, like the app's own download (it
  waited up to 10 minutes on a server that never answered; found by the full test pass).

Found by testing 31 real links on the Android 11 phone (2026-10-15):
- yt-dlp was never updated: its daily update ran inside a 12-second job and the day was counted before it ran, so
  the phone kept the yt-dlp the app shipped (2025.11.12) and Dailymotion, among others, failed. It now updates on
  its own, counts the day only when it worked, and the nightly yt-dlp tries a page the stable one cannot read.
- When nothing could read a shared link, the app only opened its browser. Now VidChain's own methods look for the
  video on the page (its hidden browser listens to the page's requests, as Web Video Caster's detection does, and
  reads the page's players) and save it to Finished downloads, with a message when they start and when they end.
- A download that only passes through "stopped" while it starts is no longer taken for a failure, and a reused
  download number no longer inherits the earlier download's history.

## 1.2.0 (T9 improvement round)
- Checked by the full emulator test pass ([run 38600707739](https://github.com/Qutaiba-Khader/vidchain/actions/runs/38600707739), 33 / 33: every test link and every link under an injected fault ends as expected).

Owner's crash report (2026-10-14, Samsung S25 Ultra, Android 17): the phone froze for about a minute and its system
screen restarted.
- The cause: when Android refused to let the app's download service run in the foreground (its daily time budget was
  used up, or the app was in the background), the service stopped itself, asked to be restarted, and was refused
  again: 4,105 starts in 80 seconds filled Android's own memory. The bug came from the original app.
- Now the service never restarts itself, enters the foreground first and stops quietly when Android says no, and
  every request for it passes a limit that survives a restart (one per 10 s, none for 10 minutes after a refusal
  until you open the app). It runs only while a download or a VidChain rescue needs it.

Faster:
- Social links (X, Instagram, ...) are analysed once and the download reuses that analysis: on the Android 11 test
  phone X opens its picker in 1.5 s (was 2.8 s) and downloads in 7.9 s (was 13.5 s); Instagram 1.5 s / 7.3 s
  (was 2.5 s / 47 s). YouTube keeps its own way (measured slower the new way).
- Large files download about twice as fast (94 MB file: 11-13 s, was 29 s and more): bigger buffer, fewer saves and
  notification redraws.
- When the app's own download gets no data, VidChain's methods start beside it (a few at a time, in the order that
  worked best on each site) instead of only after it failed; a download with no data moves on after 75 s (was 5 min).
- Nothing runs when nothing downloads: no timer, no service, no forced memory clean-up (idle CPU 0.5 s per 10 min,
  was 10.5 s).

Changed:
- There is no "fallbacks" master switch any more (owner, 2026-10-14): each method has its own switch in Settings ›
  VidChain fallbacks › Methods, including the app's own four ways ("Original: …"), and the last one that is on cannot
  be switched off. A link that no switched-on method can handle says so on its download row. A saved "all off" is
  repaired to every method on at start.
- VidChain's own screens no longer draw under the title and navigation bars on Android 15 and newer.

Fixed (found by the reviews):
- A download that took longer than 30 s from a browser page, or after the new analysis, could lose its list entry.
- Reloading a page left the video button on "no video found".
- A torrent was stopped after 30 minutes; the screen timer could run twice; a removed download could be written back.

## 1.1.5
- Checked by the full emulator test pass ([run 38442777392](https://github.com/Qutaiba-Khader/vidchain/actions/runs/38442777392), 55 / 55).

Owner's report (2026-10-13): a shared X link stayed on "Analyzing URL, Please Wait..." for good.
- The cause: links from X, Instagram, Facebook and TikTok go through the original app's own page check, which read the page
  up to 11 times, 30 s each, under a 30 s limit that could not stop it. When X did not answer, the dialog stayed about
  6 minutes (measured on an Android 11 phone). Now every "Analyzing URL" wait ends by itself: after 60 s for these
  links, 40 s for others. The link then opens in the browser, as the app already did when its check failed, and
  VidChain also looks the video up itself when the fallbacks are on. A slow but working X page still shows the picker.
- Cancel on "Analyzing URL" now really cancels: the share screen closes and no result pops up later; the yt-dlp lookup
  behind it is stopped.

Found by the review (the original app's own bugs):
- "Moving To Private Folder" / "Moving To Gallery Folder" stayed on screen for good when the copy failed or took more
  than 30 s, and "Fixing MP4" when the fix failed (it could also close the app).
- A new download could stay on "Filtering URL" for good: the redirect check had no network time limit.
- The resolution picker refused Download until the page title arrived, which could take minutes; after 35 s it now
  makes up the title the way it already did when no title was found.

## 1.1.4
- Checked by the full emulator test pass ([run 38241779554](https://github.com/Qutaiba-Khader/vidchain/actions/runs/38241779554), 55 / 55).

From the owner's list of remaining ideas (2026-10-12):
- YouTube: when YouTube's ordinary player types are refused, method C now also tries the "mobile web" and "web" types
  with proof-of-origin tokens made on the phone itself (a hidden page runs YouTube's own check, without your login
  or cookies). Tested on the phone: a 4K video that the other player types could not get was saved this way.
- An expired download link is asked for again on the same page; if the same file is found (same size, or the same
  address without the changing parts), your download continues from where it stopped instead of starting over.
- The fallbacks replay the exact request headers the page's own player used for a video address.
- The browser and the hidden page method (W) now check the page's requests the way Web Video Caster does: VidChain
  asks for possible videos itself, reads the first bytes, and finds playlists and video files that hide behind
  addresses ending in .jpg, .txt or with no ending at all. The page gets the same answer; anything unusual (a
  redirect, an error, a robot check) is left to the browser. In the browser this starts only after the page shows a
  video, and it is off with the fallbacks switch.
- A yt-dlp download stuck at "Connecting To The Server" is now stopped before the fallbacks start.

Found while testing on the phone:
- On Android 11 (and phones with the same system tools) every fallback that runs Python, ffmpeg or aria2 was stopped
  about 0.15 s after it started, so those methods never worked on such phones. Fixed.

## 1.1.3
- Checked by the full emulator test pass ([run 38208140239](https://github.com/Qutaiba-Khader/vidchain/actions/runs/38208140239), 55 / 55).

From the owner's checklist (2026-10-12):
- A stronger video grabber in the browser: it now looks inside every part of the page, also the player boxes of other
  sites embedded in it. It sees what the video actually plays, playlists recognised by their content (not only by
  their address), the state of common web players (JW Player, Flowplayer, Clappr, Video.js, OK/VK, Vimeo), and
  og:video tags. Stream pieces and adverts are left out. It is part of the VidChain fallbacks: with their switch off
  the browser behaves exactly like the original app.
- Reddit: the grabber said "No Videos Found" while the video played; it now lists it (it downloads with sound).
- The hidden page method (W) of the fallbacks uses the same grabber.
- A yt-dlp download stuck at "Connecting To The Server" for 5 minutes now counts as failed, so the fallbacks start
  (before, a post whose video was gone stayed there for good).
- yt-dlp gets the app's QuickJS as its JavaScript runtime for YouTube in methods Y and C (method N already had it).

Found by the four-lane code review and fixed:
- A web page could close the app by sending the grabber a binary message; one could flood it with fake links.
- Crash reports are cleaned more: secrets written as JSON or "name: value", e-mail addresses and file paths are removed.

## 1.1.2
- Checked by the full emulator test pass ([run 38187521573](https://github.com/Qutaiba-Khader/vidchain/actions/runs/38187521573), 55 / 55).

From the owner's requests (2026-10-12):
- New launcher icon (orange "Cloud download"), also as the themed icon on Android 13+ and the notification icon.
- The launcher name is now "AIO VidChain", so searching "aio" in the launcher finds it.
- Release files carry the version at the end of their names, e.g. `vidchain-arm64-v8a-1.1.2.apk`.
- A crash on the main screen thread now closes the app cleanly. Before, the app stayed open with a dead screen thread,
  which looked like a freeze ("not responding"), and the "Oops, App Was Crashed" sheet only appeared on the next start.
- The crash sheet's "Send Feedback" opens a GitHub issue that already holds the crash report (redacted: no cookies,
  tokens or link parameters). Nothing is sent unless you submit the issue.

Found while testing on a real phone (Galaxy A50 over USB):
- When Android reclaims memory it can kill the browser engine behind the app's web pages; that used to close the
  whole app, running downloads included. Now the app keeps running and the open browser tab reloads.
- A shared text with a link in it ("Look https://...") was lost without a message; the quality picker now opens.

## 1.1.1
- Checked by the full emulator test pass ([run 38148282013](https://github.com/Qutaiba-Khader/vidchain/actions/runs/38148282013), 55 / 55).

From the owner's report (freeze or "not responding" when sharing after the phone was idle):
- Sharing no longer freezes the app at start: the download history now loads in the background (before, it loaded
  on the main screen thread, and each saved download listed the whole app folder again, so the wait grew with the
  number of downloads). If the share arrives while the history is still loading, the app shows "wait till the app
  loads" instead of freezing.
- The share screen is no longer created twice when the phone theme differs from the app theme.
- A link analysis that fails or takes longer than 30 s no longer leaves the "Analyzing URL" dialog on screen: it
  closes and the page opens in the browser.
- Shared text that contains a link (for example "look at this https://...") is handled again.
- On Android 15 the download service no longer gets the app closed after a few hours in the background.

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
