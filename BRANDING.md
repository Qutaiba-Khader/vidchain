# Branding and identity

App identity (owner decision Q3 = B, chosen by the agent in T0.2): **VidChain**, package
`org.websnake.vidchain`, repository `Qutaiba-Khader/vidchain`, new icon (no upstream name or logo).
The Kotlin namespace stays `com.aio` for upstream-origin code (Q19 = A); every remaining `com.aio`
use is classified in `tools/rename-allowlist.txt` and checked by `tools/rename_audit.py`.

| Change | Where |
|---|---|
| applicationId `org.websnake.vidchain` | `app/build.gradle` |
| Lint baseline block (T1.4: inherited findings frozen in `app/lint-baseline-vidchain.xml`, calendar-dependent checks disabled) | `app/build.gradle` |
| Own version line: versionName `MAJOR.MINOR.PATCH`, versionCode `MAJOR*10000 + MINOR*100 + PATCH` (first: 0.0.1 / 1), set together with `release-contract.json` | `app/build.gradle` |
| taskAffinity `${applicationId}.mother` (no clash with another install of the original app) | `AndroidManifest.xml` |
| App name, folder names, category names, help texts, player/service titles: "AIO" -> "VidChain" | `values/strings_library.xml`, `values-bn/strings_library.xml`, `strings_unit_ids.xml` (BUILD-FIXES) |
| Official page / privacy / terms links -> this repository (`PRIVACY.md`, `LICENSE.md`) | same string files |
| Fallback file titles `..._By_VidChain_...`; category label strips the "VidChain" prefix | `M3U8VideoDownloader.kt`, `VideoResolutionPicker.kt`, `FinishedTasksViewHolder.kt` |
| New adaptive launcher icon (+ themed monochrome layer) | `mipmap-anydpi-v26/ic_launcher_v2*.xml`, `drawable/ic_vidchain_*.xml` |
| Notification small icon replaced by the VidChain mark (same resource name, no code change) | `drawable/ic_launcher_logo_v4.png` -> `.xml` |

```paths
modified app/build.gradle
modified app/src/main/AndroidManifest.xml
modified app/src/main/res/values/strings_library.xml
modified app/src/main/res/values-bn/strings_library.xml
modified app/src/main/java/app/core/engines/downloader/M3U8VideoDownloader.kt
modified app/src/main/java/app/ui/main/fragments/downloads/intercepter/VideoResolutionPicker.kt
modified app/src/main/java/app/ui/main/fragments/downloads/fragments/finished/FinishedTasksViewHolder.kt
modified app/src/main/res/mipmap-anydpi-v26/ic_launcher_v2.xml
modified app/src/main/res/mipmap-anydpi-v26/ic_launcher_v2_round.xml
deleted app/src/main/res/drawable/ic_launcher_logo_v4.png
added app/src/main/res/drawable/ic_launcher_logo_v4.xml
added app/src/main/res/drawable/ic_vidchain_background.xml
added app/src/main/res/drawable/ic_vidchain_foreground.xml
```
