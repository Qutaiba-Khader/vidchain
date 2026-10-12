# Build fixes

The public upstream code (shibaFoss/AIO-Video-Downloader, branch `master`, commit in `VENDORED_FROM`)
does not compile on its own: the author keeps several files out of git. This file lists every file
added or changed **only so the code builds** (none of them carries a private value; none adds behaviour), and,
in its last section, the original app's bugs fixed at the owner's request.

`tools/drift_check.py` reads the `paths` block below and fails CI on any undeclared difference
between the app tree and `upstream/`.

| Path | Why |
|---|---|
| `app/src/main/res/values/strings_unit_ids.xml` | Git-ignored upstream; referenced by the code. Recreated with no private value: servers point at a closed local port (`http://127.0.0.1:9`), no ad unit ids. |
| `app/src/main/java/app/core/engines/backend/AIOBackend.kt` | Git-ignored upstream; called by AIOApp, the crash handler, DownloadSystem and the feedback screen. Recreated as no-ops (nothing is sent). |
| `app/src/main/java/app/core/engines/backend/AppUsageTimer.kt` | Git-ignored upstream; recreated as a no-op (no usage tracking). |
| `app/src/main/java/app/core/engines/backend/AIOSelfDestruct.kt` | Git-ignored upstream; recreated as a no-op (no remote kill switch). |
| `others/release_keystore.properties` | Read unconditionally by `app/build.gradle`. Placeholders only; CI injects the real signing key from protected secrets. |
| `.gitignore` | Upstream ignores the files above; the ignore lines are removed so they can be committed. |

Lint: upstream ships a lint error in its own layout (`activity_user_account_profile_1.xml`, `NotSibling`)
that used to need `-x lintVitalRelease`. Since T1.4 every inherited lint finding is frozen in
`app/lint-baseline-vidchain.xml` (the `lint {}` block in `app/build.gradle`, see BRANDING.md for that file),
so release builds run lint again and only NEW lint errors fail.

```paths
added app/src/main/res/values/strings_unit_ids.xml
added app/src/main/java/app/core/engines/backend/AIOBackend.kt
added app/src/main/java/app/core/engines/backend/AppUsageTimer.kt
added app/src/main/java/app/core/engines/backend/AIOSelfDestruct.kt
added others/release_keystore.properties
modified .gitignore
added app/lint-baseline-vidchain.xml
```

## Inherited bug fixes (owner INBOX #8, 2026-10-04)

The owner lifted the "current method unchanged" rule for these fixes of the original app's own bugs (T8.7). Each
change is marked `T8.7` in the code; the pure rules live in `fallback-core/.../fixes/` with JVM tests. Three files
also carry FALLBACK-SEAM lines and stay declared in `seams.lock` (its seam lines are still checked).

| Bug (original app) | Fix | Files |
|---|---|---|
| a/c. A page fetch looped forever while the page failed (`while (index < numOfRetry \|\| htmlBody.isNullOrEmpty())`, no pause, a new HTTP client per pass): ~9,000 requests a minute on a 404, and an HLS link whose playlist 404s ran the app out of memory, again on every restart (crash loop) | at most 4 attempts, 0.5 / 1 / 2 s apart, none after a 4xx other than 408/429; one shared HTTP client (`BoundedRetry`, BoundedRetryTest) | `URLUtilityKT.kt` |
| The page-title lookup returned the text "kotlin.Unit" instead of the title | returns the title it received | `VideoParserUtility.kt` |
| b. A file saved as video/audio that is an HTML page, empty or shorter than announced ended as "Completed"; a 403/404 left an empty file and a download "downloading" forever; a part the server ended early left it waiting forever | such downloads end as "Download Failed" (a chain may start) and the unusable file is removed (`BadFile`, BadFileTest) | `RegularDownloader.kt`, `RegularDownloadPart.kt` |
| d. After a shared link went to the browser, later shares were ignored until the app restarted (the main screen stayed in the share's task and kept the first link) | the link opens in the app's own task; the main screen takes each new link; the share flag is cleared on that path too | `IntentInterceptActivity.kt`, `MotherActivity.kt`, `SharedVideoURLIntercept.kt` |
| e. Picking a yt-dlp "Generic" format (a page's single file) asked yt-dlp for that format plus a separate audio stream that does not exist: a failed entry and an empty placeholder file; a known silent video was downloaded without its audio | the picker passes yt-dlp's audio codec the way the app reads it (`YtDlpFormats.appAcodec`, YtDlpPickerTest) | ours: `VidChainShareRescue.kt` |
| f8. Cookies handed to yt-dlp had no domain, so yt-dlp dropped them | the cookie file names the page's exact host (host-only) | `DownloaderUtils.kt`, `VideoParserUtility.kt`, `DownloadDataModel.kt` |
| f9. The Referer was always cut to the origin | the full page address when the file is on the same host, the origin otherwise (a browser's default) | `DownloadURLHelper.kt` |
| T8.9 (owner report 2026-10-11: freeze / "not responding" when sharing after the phone was idle). Start-up loaded the whole download history on the main thread, and every loaded download listed the whole files/ folder again (N x folder size) | the history loads in the background (readers already wait on `isInitializing`, now set before the load); the folder is listed again only when it changed (`MaxIdCache`, MaxIdCacheTest) | `AIOApp.kt`, `UniqueNumberUtils.kt` |
| T8.9: a failed or timed-out link analysis (30 s job limit) left the non-cancelable "Analyzing URL" dialog up for good | the dialog closes and the page opens in the browser | `SharedVideoURLIntercept.kt` |
| T8.9: the always-on dataSync foreground service had no `onTimeout` (Android 15 kills the app after 6 h) and crashed when Android refused its start | `onTimeout` stops it; a refused start stops the service instead of crashing | `AIOForegroundService.java` |
| T8.10 (owner report 2026-10-12: "Oops, App Was Crashed" sheet at start). The crash handler saved the crash and returned: on the main thread that leaves the app open with a dead screen thread (frozen, then "not responding"), and the report went only to a hidden file in the download folder, which needs the storage permission | a main-thread crash is handed to Android's own handler (the app closes cleanly); a redacted report (trace Redactor, last 5) is kept in the app's private folder and the crash screen's GitHub issue text carries the newest one (`VidChainCrashReport`); the upstream crash flag is saved in the background, so a main-thread crash also leaves a marker file the launcher checks | `AIOCrashHandler.kt`, `LauncherActivity.kt` (declared in CHANGES.md) |
| T8.10 (found on the test phone): no WebView handled `onRenderProcessGone`. When Android kills the shared WebView renderer under memory pressure (typically in the background), Chromium then kills the whole app, running downloads included (reproduced on the A50 by killing the renderer: "Render process ... kill (OOM or update) wasn't handled by all associated webviews, killing application") | every WebView handles it: a browser tab is dropped and the current one reopened at the same address (blank on a second loss within 30 s); the popup, M3U8 cookie and catcher WebViews are destroyed and their job ends as a failed load (`VidChainWebRecovery`) | `BrowserWebClient.kt`, `BrowserWebChromeClient.kt`, `M3U8VideoDownloader.kt` (declared in BRANDING.md) |
| T8.10 (found on the test phone): a shared text with a link in it ("Look https://...") was relaunched as a new share screen with the bare link (T8.9); on the A50 that second screen closed without a trace, so the link was lost silently | the same share screen goes on with the bare link in its intent and runs the app's own analysis (the picker opens); the seam returns instead of closing the screen | ours: `VidChainShareRescue.kt`; seam `IntentInterceptActivity.kt` share-entry |
| T8.11 (owner checklist A, found on the test phone): on a site yt-dlp knows (Reddit), the grabber's yt-dlp check ran first; when it found no thumbnail it reset the button AND cleared the videos the page had already loaded, so a playing Reddit video gave "No Videos Found" | the page's own videos are kept and offered when that check finds nothing (`showSniffedVideos`) | `WebVideoParser.kt` |
| T8.12 (owner decision): the app's own downloader cuts the Referer to the host and never sends the request headers the page's player used, so a CDN that checks them refuses the download | the browser keeps the exact request headers per address (memory only, never a Cookie, off with the fallbacks switch) and method S replays them (`VidChainHeaders`); the current method is unchanged | `BrowserWebClient.kt` (one call in shouldInterceptRequest; T8.12 re-fetch: its return goes through `VidChainGrabber.refetch`, null when off, the upstream code before it unchanged) |

```paths
modified app/src/main/java/lib/networks/URLUtilityKT.kt
modified app/src/main/java/app/core/engines/video_parser/parsers/VideoParserUtility.kt
modified app/src/main/java/app/core/engines/downloader/RegularDownloader.kt
modified app/src/main/java/app/core/engines/downloader/RegularDownloadPart.kt
modified app/src/main/java/app/ui/others/information/IntentInterceptActivity.kt
modified app/src/main/java/app/ui/main/MotherActivity.kt
modified app/src/main/java/app/ui/main/fragments/downloads/intercepter/SharedVideoURLIntercept.kt
modified app/src/main/java/lib/networks/DownloaderUtils.kt
modified app/src/main/java/app/core/engines/downloader/DownloadDataModel.kt
modified app/src/main/java/app/core/engines/downloader/DownloadURLHelper.kt
modified app/src/main/java/app/core/AIOApp.kt
modified app/src/main/java/lib/process/UniqueNumberUtils.kt
modified app/src/main/java/app/core/engines/services/AIOForegroundService.java
modified app/src/main/java/app/core/AIOCrashHandler.kt
added app/src/main/java/org/websnake/vidchain/app/VidChainCrashReport.kt
added app/src/main/java/org/websnake/vidchain/app/VidChainWebRecovery.kt
modified app/src/main/java/app/ui/main/fragments/browser/webengine/BrowserWebClient.kt
modified app/src/main/java/app/ui/main/fragments/browser/webengine/BrowserWebChromeClient.kt
modified app/src/main/java/app/ui/main/fragments/browser/webengine/WebVideoParser.kt
```
