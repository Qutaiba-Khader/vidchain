# Removed features

Ads, tracking, the self-updater, the remote kill switch and developer cloud sync are removed from
VidChain. The UI does not offer what cannot work. Nothing here blocks, hides or filters any site
(owner decisions Q27/Q28: every site stays available, home shortcuts stay as upstream).

| What | How | Where |
|---|---|---|
| Ads | Upstream `master` ships no ad SDK and has the ad gate off (`IS_PREMIUM_USER` / `IS_ULTIMATE_VERSION_UNLOCKED` = true); the gates below keep it that way. | `tools/removed_check.py` §1, §4; `tools/apk_check.sh` |
| Crash reports, download log, feedback upload (Parse / Back4App) | `AIOBackend` is a no-op; the Parse server URL points at a closed local port. | BUILD-FIXES.md (stub + `strings_unit_ids.xml`) |
| Usage tracking | `AppUsageTimer` is a no-op. | BUILD-FIXES.md (stub) |
| Remote kill switch | `AIOSelfDestruct` is a no-op. | BUILD-FIXES.md (stub) |
| Developer cloud sync / accounts (Parse settings sync, Supabase login) | Server URLs point at a closed local port; the sign-in block is gone from Settings (since v1.1.0 the whole account block is removed, CHANGES.md). | `strings_unit_ids.xml` (BUILD-FIXES), settings screen (CHANGES.md) |
| Self-updater (would install the original app over this one) | Update URL points at a closed local port; the "check for update" row is gone from Settings (redesigned screen, CHANGES.md). Updates come from GitHub Releases / Obtainium. | `AIOUpdater.kt`, settings screen (CHANGES.md) |
| "Feedback sent" claims | Feedback and the crash dialog open a pre-filled GitHub issue in this repository instead of claiming to send to a server. | `UserFeedbackActivity.java` |

Kept on purpose: the in-app browser's ad-block filter list is still fetched from the original
project's GitHub repository (a public data file, no user data sent).

Gates: `tools/removed_check.py` (source; it fails on `upstream/` as its negative control) and
`tools/apk_check.sh` (dex/manifest deny-list + stub sentinel on every built APK).

```paths
modified app/src/main/java/app/core/engines/updater/AIOUpdater.kt
modified app/src/main/java/app/ui/others/information/UserFeedbackActivity.java
```
