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
| T8.12 (owner decision): the app's own downloader cuts the Referer to the host and never sends the request headers the page's player used, so a CDN that checks them refuses the download | the browser keeps the exact request headers per address (memory only, never a Cookie; T9: no master switch any more) and method S replays them (`VidChainHeaders`); the current method is unchanged | `BrowserWebClient.kt` (one call in shouldInterceptRequest; T8.12 re-fetch: its return goes through `VidChainGrabber.refetch`, the upstream code before it unchanged) |
| T8.13 (owner report 2026-10-13: a shared X link stayed on "Analyzing URL, Please Wait..." for good). X, Instagram, Facebook, TikTok links run the app's "social" page analysis: up to 11 page fetches of 30 s (3 + 4 + 4, the thumbnail and title steps fetch the failed page again) inside a coroutine `withTimeout(30 s)` that cannot act on blocking calls, and no error handler: about 6 minutes when the site does not answer (340 s measured on an Android 11 phone). The same in the paste dialog, the clipboard prompt and the browser's share intake | every "Analyzing URL" wait for a social link ends at 60 s on the main thread (`VidChainWaits.Deadline`) the way the app's own "server busy" path does (toast, the link in the browser); a late result is ignored and no further fetch starts; an error ends it at once; the page client gets a 90 s call limit | `IntentInterceptActivity.kt`, `MotherActivity.kt`, `BrowserFragmentBody.kt`, `VideoLinkPasteEditor.kt`, `URLUtilityKT.kt`, ours: `VidChainWaits.kt` |
| T8.13: the analysis of other links (yt-dlp format list) could not be stopped either (no process id), so T8.9's close ran only when yt-dlp ended; Cancel never ran its handler (the dialog's button removes the cancel listener before it closes), so the share screen stayed and the result popped up later | a 40 s deadline (the app's own 30 s job limit plus a margin) also stops the yt-dlp process; Cancel stops it and closes the share screen; the browser fallback honours the caller's "no browser" choice | `SharedVideoURLIntercept.kt`, `VideoParserUtility.kt` |
| T8.13: "Moving To Private/Gallery Folder" (no button, not cancelable) stayed for good when the copy failed, and when it took over 30 s (its success was dropped); "Fixing MP4" stayed for good when the fix failed | a failure reports through the existing error path; a long move may take up to an hour; the MP4 dialog always closes | `DownloadDataModel.kt`, `FinishedDownloadOptions.kt` |
| T8.13: the redirect check at the start of every regular download had no network timeout ("Filtering URL" for good when the server never answers) | 30 s connect / 60 s read; on a timeout the download goes on as before | `URLUtility.java` |
| T8.13: the resolution picker refuses Download until the page title arrives, and that fetch could take minutes | after 20 s the picker makes up the title the way it already does when no title is found | `VideoResolutionPicker.kt` (declared in BRANDING.md) |
| T9 issue 1: yt-dlp got `--socket-timeout 30000`: the app keeps the setting in milliseconds, yt-dlp reads seconds (about 8 hours), so a dead server was never given up | the value is passed in seconds (`YtDlpTimeout`, SmallFixesTest) | `M3U8VideoDownloader.kt` (declared in BRANDING.md) |
| T9 issue 2: the HLS info lookup caught every exception, also a coroutine's cancellation, and then reported an error for a lookup that had been stopped | cancellation is passed on and a stopped lookup reports nothing (`CancelAware`) | `M3U8InfoExtractor.kt` |
| T9 issue 3: the yt-dlp handle was set only after the start-up yt-dlp update (a network download), so a share during that update crashed on an unset value | it is set right after initialisation | `AIOApp.kt` |
| T9 issue 5: site checks matched any host that contains the name: "dropbox.com" counted as X ("x.com"), "reddit.com" as X's short links ("t.co") | a host matches a site only when it is the domain or a subdomain of it (`HostMatch`, SmallFixesTest) | `SupportedURLs.kt` |
| T9 issue 7: a format lookup whose yt-dlp process was killed (T8.13 deadline / Cancel) could be started again by its retry loop | a stopped lookup is not retried (`StoppableRetry`, `VideoParserUtility.stopYtdlpProcess`) | `VideoParserUtility.kt`, `SharedVideoURLIntercept.kt` |
| T9 decision 6: the browser fetched the open page (up to 6 tries each) on every 200 ms tick while a site yt-dlp knows was open - about 5 page requests a second | the page is checked once per address change; the VidChain grabber asks again after its first find | `WebVideoParser.kt`, ours: `VidChainGrabber.kt` |
| T9 decision 7 (kept only where measured better, numbers in STATUS): every screen resume re-ran the yt-dlp/FFmpeg set-up with a network update and downloaded the ad-filter list again; progress was saved about 4 times a second (two files, the cookie file and a database row); the regular downloader wrote 8 KB at a time and checked the file and built a log text before every read; the unused login client was built on the main thread at start | set-up and ad filters once per launch, the yt-dlp update at most once a day (`VidChainOnce`); progress saved at most every 2.5 s and the notification redrawn at most once a second, status changes at once (`VidChainSaveThrottle`); 256 KB reads, the file checked every 4 MB; the login client built off the main thread | `AIOApp.kt`, `BaseActivity.kt` (declared in CHANGES.md), `DownloadDataModel.kt`, `DownloadNotification.kt`, `RegularDownloadPart.kt`, ours: `VidChainOnce.kt`, `VidChainSaveThrottle.kt` |
| T9 decision 4 (owner, 2026-10-14: nothing may run in the background unless something downloads or fetches): the app's 200 ms timer ran for as long as the app lived and forced a garbage collection every 30 s; the download system started a coroutine scope on every tick and asked Android to start its foreground service every second, also with nothing to download; every screen resume started that service; the service restarted itself whenever Android stopped it | the timer runs only while a screen or a download listens and stops by itself after the last one; no forced garbage collection; the download system listens only while a download waits or runs, then lets the service stop; the service is asked for only when it is not running and restarts only while a download needs it | `AIOTimer.kt`, `DownloadSystem.kt`, `AIOForegroundService.java`, `RestartIdleServiceReceiver.kt`, `AIOApp.kt`, `BaseActivity.kt` (declared in CHANGES.md) |

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
modified app/src/main/java/app/ui/main/fragments/browser/BrowserFragmentBody.kt
modified app/src/main/java/app/core/engines/video_parser/dialogs/VideoLinkPasteEditor.kt
modified app/src/main/java/app/ui/main/fragments/downloads/fragments/finished/FinishedDownloadOptions.kt
modified app/src/main/java/lib/networks/URLUtility.java
added app/src/main/java/org/websnake/vidchain/app/VidChainWaits.kt
modified app/src/main/java/app/ui/main/fragments/browser/webengine/M3U8InfoExtractor.kt
modified app/src/main/java/app/core/engines/video_parser/parsers/SupportedURLs.kt
modified app/src/main/java/app/core/AIOTimer.kt
modified app/src/main/java/app/core/engines/downloader/DownloadSystem.kt
modified app/src/main/java/app/core/engines/services/RestartIdleServiceReceiver.kt
added app/src/main/java/org/websnake/vidchain/app/VidChainOnce.kt
modified app/src/main/java/app/core/engines/downloader/DownloadNotification.kt
added app/src/main/java/org/websnake/vidchain/app/VidChainSaveThrottle.kt
```
