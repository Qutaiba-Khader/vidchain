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
change is marked `T8.7` in the code; the pure rules live in `fallback-core/.../fixes/` with JVM tests. Two files
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
```
