# Third-party notices

The app includes the components below, each under its own licence. Licence texts ship in the app's
open-source licences screen and in the `SOURCES-<tag>` bundle of every release.
Status: initial list from `gradle/libs.versions.toml` and `app/build.gradle` (T0.1). Every entry is
re-checked against the resolved dependency tree and the release bill of lading in task T6.3, and the
fallback components are added as they land (P2-P5).

| Component | Version | Licence |
|---|---|---|
| AIO Video Downloader (original app) | see VENDORED_FROM | Non-Commercial Use Free License (LICENSE.md) |
| youtubedl-android (library, ffmpeg) | 0.18.1 | GPL-3.0 |
| - bundled yt-dlp | runtime | Unlicense |
| - bundled CPython | 3.12 | PSF-2.0 |
| - bundled FFmpeg | per youtubedl-android build | LGPL-2.1+ / GPL-2.0+ (per its build configuration) |
| - bundled QuickJS | per youtubedl-android build | MIT |
| NewPipe Extractor | v0.24.8 | GPL-3.0 (transitive dependencies listed in T6.3) |
| AndroidX (core-ktx, appcompat, constraintlayout, viewpager2, cardview, webkit, biometric, lifecycle-process) | per catalog | Apache-2.0 |
| AndroidX Media3 (exoplayer, hls, dash, datasource, ui, session) | 1.8.0 | Apache-2.0 |
| Kotlin coroutines | 1.10.2 | Apache-2.0 |
| OkHttp | 5.3.2 | Apache-2.0 |
| Retrofit (+ converter-gson) | 3.0.0 | Apache-2.0 |
| Ktor client | 3.3.3 | Apache-2.0 |
| supabase-kt (bom, postgrest-kt) | 3.2.6 | MIT |
| Parse SDK Android | 4.3.0 | BSD-3-Clause |
| ObjectBox (android, kotlin) | 5.0.1 | Apache-2.0 (Java/Kotlin API); native library under ObjectBox's own terms |
| Gson | 2.13.2 | Apache-2.0 |
| DSL-JSON | 2.0.2 | BSD-3-Clause |
| FST | 3.0.3 | Apache-2.0 |
| jsoup | 1.21.2 | MIT |
| mp4parser isoparser | 1.1.22 | Apache-2.0 |
| SimpleStorage (com.anggrayudi:storage) | 2.2.0 | Apache-2.0 |
| Lottie | 6.7.1 | Apache-2.0 |
| Glide | 5.0.5 | BSD-2-Clause (parts MIT and Apache-2.0) |
| CircleImageView | 3.1.0 | Apache-2.0 |
| PermissionX | 1.8.1 | Apache-2.0 |
| desugar_jdk_libs_nio | 2.1.5 | GPL-2.0 with Classpath Exception |
