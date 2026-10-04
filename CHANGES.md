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
```
