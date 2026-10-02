# Build fixes

The public upstream code (shibaFoss/AIO-Video-Downloader, branch `master`, commit in `VENDORED_FROM`)
does not compile on its own: the author keeps several files out of git. This file lists every file
added or changed **only so the code builds**. None of them carries a private value; none adds behaviour.

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

Build command note: the release build needs `-x lintVitalRelease` (an upstream layout,
`activity_user_account_profile_1.xml`, fails lint with a `NotSibling` error). This is a CI flag,
not a source change.

```paths
added app/src/main/res/values/strings_unit_ids.xml
added app/src/main/java/app/core/engines/backend/AIOBackend.kt
added app/src/main/java/app/core/engines/backend/AppUsageTimer.kt
added app/src/main/java/app/core/engines/backend/AIOSelfDestruct.kt
added others/release_keystore.properties
modified .gitignore
```
