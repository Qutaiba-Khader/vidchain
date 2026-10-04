# Owner-requested changes to the original app

Changes the owner asked for after testing VidChain on the phone (INBOX #8, 2026-10-04). They change what
the original app shows or does by default; none touches the download logic (the inherited bug fixes are
listed separately in BUILD-FIXES.md). `tools/drift_check.py` reads the `paths` block below; a file that
also carries FALLBACK-SEAM lines stays declared in `seams.lock` too, and `tools/seam_check.py` then checks
only its seam lines.

| Change | Files |
|---|---|
| Fresh installs start with "Enable Dark UI Mode" on (T8.1) | ours only: `VidChainDefaults.kt` |
| The user / account / profile block is gone from Settings, with the "Login / Register" flow (sign-in was already pointed at a closed port, REMOVED.md), the account details screen and every entry that only led to "This feature is currently not available..." (the username editor, "Other Advanced Settings" under Downloads) (T8.2) | `SettingsFragment.kt`, `SettingsOnClickLogic.kt`, `BaseActivity.kt` (`showUpcomingFeatures`), `UserAccountDetailsActivity.kt` + its layout, settings layout (REMOVED.md), manifest (BRANDING.md) |
| "Enable Daily Suggestions" is gone and stays off for everyone (the original app reads the value nowhere else: no notification, job or fetch) (T8.3) | `SettingsFragment.kt`, `SettingsOnClickLogic.kt`, `VidChainDefaults.kt` |
| "Other Advanced Settings" is gone from Downloads (it only showed "not available") and from Browser, with the browser screen behind it (none of its rows did anything; every working browser setting is on the main screen) (T8.4) | `SettingsOnClickLogic.kt`, `AdvBrowserSettingsActivity.kt` + its layout, manifest (BRANDING.md) |
| The splash screen is gone: the launcher opens the main screen directly. The splash did no work of its own: it showed an animation for a fixed 1.2 s and then opened the main screen; the app's start-up work runs in Application start before any screen either way (T8.5) | `LauncherActivity.kt`, `OpeningActivity.kt` + its layout, `BaseActivity.kt` (its permission-request exception for the splash), manifest (BRANDING.md) |

```paths
modified app/src/main/java/app/ui/main/fragments/settings/SettingsFragment.kt
modified app/src/main/java/app/ui/main/fragments/settings/SettingsOnClickLogic.kt
modified app/src/main/java/app/core/bases/BaseActivity.kt
deleted app/src/main/java/app/ui/others/information/UserAccountDetailsActivity.kt
deleted app/src/main/res/layout/activity_user_account_profile_1.xml
deleted app/src/main/java/app/ui/main/fragments/settings/activities/browser/AdvBrowserSettingsActivity.kt
deleted app/src/main/res/layout/activity_adv_browser_settings_1.xml
modified app/src/main/java/app/ui/others/startup/LauncherActivity.kt
deleted app/src/main/java/app/ui/others/startup/OpeningActivity.kt
deleted app/src/main/res/layout/activity_opening_1.xml
```
