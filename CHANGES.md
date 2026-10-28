# Owner-requested changes to the original app

Changes the owner asked for after testing VidChain on the phone (INBOX #8, 2026-10-04). They change what
the original app shows or does by default; none touches the download logic (the inherited bug fixes are
listed separately in BUILD-FIXES.md). `tools/drift_check.py` reads the `paths` block below; a file that
also carries FALLBACK-SEAM lines stays declared in `seams.lock` too, and `tools/seam_check.py` then checks
only its seam lines.

## T9 improvement round (owner, 2026-10-14)

The owner dropped the rule "with the fallbacks off, the app sends exactly the original's requests": VidChain is an
improved app, not a byte-identical twin. From T9 the original app's own fetch paths may change (each change is
listed here with its files), the full test pass judges what the user gets (files) instead of comparing requests
with the recording, and there is no "fallbacks off" mode any more (decision 10: the master switch is removed; every
method has its own switch and at least one always stays on). `seams.lock` still lists the seam lines, and
`tools/drift_check.py` still proves every difference from `upstream/` is declared in a ledger.

| Change | Files |
|---|---|
| T9 decision 2: one `yt-dlp -J` analysis per link feeds the picker (formats with sizes, title, picture, duration; YouTube no longer runs NewPipe plus a second yt-dlp for the sizes) and the download (`--load-info-json`: no hidden page load for cookies, no second extraction); the kept JSON holds no cookie, cookies are read fresh and sent host-only; an expired / refused replay is analysed once more, then the original way, then the fallback methods. The analysis of a shared link starts before the download history has loaded. X / Instagram / Facebook / TikTok links open their picker at once with a temporary title that the analysis fills in (the page is fetched once only when the analysis gives nothing) | ours: `VidChainAnalysis.kt`, `VidChainSocial.kt`, `AnalysisStore.kt`; `SharedVideoURLIntercept.kt`, `M3U8VideoDownloader.kt` (declared in BRANDING.md), `IntentInterceptActivity.kt`, `BrowserFragmentBody.kt`, `MotherActivity.kt`, `VideoLinkPasteEditor.kt` (declared in BUILD-FIXES.md), `SingleResolutionPrompter.kt` |
| T9 decision 1: the original app's own ways to fetch (yt-dlp, social page, direct file, browser find) are methods in the one Methods list with their own switches; switched off, the app does not start that engine and VidChain's methods take the download at once. VidChain's methods run in batches (about 3, up to 5 light ones; one process or WebView at a time; staggered; cancelled only after a proven success, losers stopped by a watchdog) in a learned per-site order (scorecard: site, method, cause, time only), and start beside a slow own download (the hedged start: no first bytes within the site's usual time); the download row shows which methods run | ours: `OwnPath.kt`, `race/*`, `FallbackCoordinator.kt`, `VidChainOwnPath.kt`; `DownloadSystem.kt` (declared in BUILD-FIXES.md: the own-path switch before an engine starts) |
| T9 decision 11: VidChain's own screens (settings, licences) gave no room for the bars and drew under the status bar, the title bar and the navigation bar on Android 15+ (edge to edge is enforced for apps targeting SDK 36); the app's own screens already fit the bars | ours: `VidChainInsets.kt`, `VidChainSettingsActivity.kt`, `VidChainLicencesActivity.kt`; the CI check `ci/emulator/overlap.py` on API 33, 35, 36 |
| Fresh installs start with "Enable Dark UI Mode" on (T8.1) | ours only: `VidChainDefaults.kt` |
| The user / account / profile block is gone from Settings, with the "Login / Register" flow (sign-in was already pointed at a closed port, REMOVED.md), the account details screen and every entry that only led to "This feature is currently not available..." (the username editor, "Other Advanced Settings" under Downloads) (T8.2) | `SettingsFragment.kt`, `SettingsOnClickLogic.kt`, `BaseActivity.kt` (`showUpcomingFeatures`), `UserAccountDetailsActivity.kt` + its layout, settings layout (REMOVED.md), manifest (BRANDING.md) |
| "Enable Daily Suggestions" is gone and stays off for everyone (the original app reads the value nowhere else: no notification, job or fetch) (T8.3) | `SettingsFragment.kt`, `SettingsOnClickLogic.kt`, `VidChainDefaults.kt` |
| "Other Advanced Settings" is gone from Downloads (it only showed "not available") and from Browser, with the browser screen behind it (none of its rows did anything; every working browser setting is on the main screen) (T8.4) | `SettingsOnClickLogic.kt`, `AdvBrowserSettingsActivity.kt` + its layout, manifest (BRANDING.md) |
| The splash screen is gone: the launcher opens the main screen directly. The splash did no work of its own: it showed an animation for a fixed 1.2 s and then opened the main screen; the app's start-up work runs in Application start before any screen either way (T8.5) | `LauncherActivity.kt`, `OpeningActivity.kt` + its layout, `BaseActivity.kt` (its permission-request exception for the splash), manifest (BRANDING.md) |
| Firebase (owner, 2026-10-28; project fbtest-9e699, app org.websnake.vidchain): Crashlytics crash and error reports, Analytics and custom logs behind ONE switch in VidChain settings ("Send crash reports and app statistics", default on; off = nothing sent, reports caught meanwhile dropped); every message is redacted first (no cookie, login, token, signed link, e-mail address or file path; the trace rule); a "Send a test crash" button and `am broadcast ... --es test crash` for checks; the advertising ID is not collected. An optional account (Firebase Authentication: e-mail + password, Google via Credential Manager) with the user's own Firestore profile users/{uid} (`firebase/firestore.rules`: owner only, strict schema). The Gradle plugins apply only when app/google-services.json exists (git-ignored; CI writes it from the GOOGLE_SERVICES_JSON secret, on pushes only; a release requires it). The "no tracking" gates (apk_check, removed_check) let Firebase through; every other tracker stays banned | `build.gradle`, `app/build.gradle`, `.gitignore`, `AndroidManifest.xml`, ours: `VidChainLog.kt`, `VidChainAccount.kt`, `VidChainCrashReport.kt`, `VidChainFallbackHost.kt`, `CrashReporting.kt`, `FirebaseAccount.kt`, `VidChainSettingsActivity.kt`, `firebase/` |
| The settings screen is redesigned from the owner's design (`design/VidChain Settings.dc.html`): sections Appearance & region, Downloads, Browser, VidChain fallbacks, Support & about; rounded cards; a switch with the real state on every on/off row; one-line descriptions written from what each setting does; the version card and the acknowledgement / @ShibaFoss footer kept. Also removed: "Default Download Folder" (its folder picker saved nothing; "Download Location" is the one that works), "Enable Adblocker" (the switch changed nothing anywhere in the app), the hidden update-check row, and the 0.5 s loading animation shown on every visit (T8.6) | settings layout, `SettingsFragment.kt`, `SettingsOnClickLogic.kt`, new resources `vidchain_settings.xml` (day + night), `vc_settings_*` drawables and colours |

```paths
modified app/src/main/java/app/ui/main/fragments/settings/SettingsFragment.kt
modified app/src/main/java/app/ui/main/fragments/settings/SettingsOnClickLogic.kt
modified app/src/main/java/app/core/bases/BaseActivity.kt
deleted app/src/main/java/app/ui/others/information/UserAccountDetailsActivity.kt
deleted app/src/main/res/layout/activity_user_account_profile_1.xml
deleted app/src/main/java/app/ui/main/fragments/settings/activities/browser/AdvBrowserSettingsActivity.kt
deleted app/src/main/res/layout/activity_adv_browser_settings_1.xml
modified app/src/main/java/app/ui/others/startup/LauncherActivity.kt
modified app/src/main/res/layout/frag_settings_1_main_1.xml
added app/src/main/res/values/vidchain_settings.xml
added app/src/main/res/values-night/vidchain_settings.xml
added app/src/main/res/color/vc_settings_switch_thumb.xml
added app/src/main/res/color/vc_settings_switch_track.xml
added app/src/main/res/drawable/vc_settings_switch_thumb.xml
added app/src/main/res/drawable/vc_settings_switch_track.xml
added app/src/main/res/drawable/vc_settings_card.xml
added app/src/main/res/drawable/vc_settings_row_bg.xml
added app/src/main/res/drawable/vc_settings_outline_button.xml
added app/src/main/res/drawable/vc_settings_chevron.xml
deleted app/src/main/java/app/ui/others/startup/OpeningActivity.kt
deleted app/src/main/res/layout/activity_opening_1.xml
modified app/src/main/java/app/ui/main/fragments/browser/webengine/SingleResolutionPrompter.kt
added app/src/main/java/org/websnake/vidchain/app/VidChainAnalysis.kt
added app/src/main/java/org/websnake/vidchain/app/VidChainSocial.kt
added app/src/main/java/org/websnake/vidchain/app/VidChainOwnPath.kt
modified build.gradle
added app/src/main/java/org/websnake/vidchain/app/VidChainLog.kt
added app/src/main/java/org/websnake/vidchain/app/VidChainAccount.kt
```
