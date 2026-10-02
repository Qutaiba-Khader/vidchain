#!/usr/bin/env bash
# Complete corresponding source for a release (NCUF 4.1, GPL): the whole repository at the release commit
# (app, our code, the untouched upstream/ snapshot, build scripts) plus where to get the exact sources of the
# bundled third-party binaries.
# usage: sources_bundle.sh <tag> <commit> <out-dir>
set -Eeuo pipefail
TAG=$1 SHA=$2 OUT=$3
work=$(mktemp -d); trap 'rm -rf -- "$work"' EXIT
git archive --format=tar --prefix="vidchain-$TAG/" "$SHA" > "$work/src.tar"
cat > "$work/THIRD_PARTY_SOURCES.txt" <<TXT
Exact sources of the third-party binaries inside VidChain $TAG (see THIRD_PARTY_NOTICES.md):
- youtubedl-android 0.18.1 (GPL-3.0; bundles yt-dlp, CPython, FFmpeg, QuickJS): https://github.com/yausername/youtubedl-android/tree/v0.18.1
  Maven sources: https://repo1.maven.org/maven2/io/github/junkfood02/youtubedl-android/
- NewPipe Extractor v0.24.8 (GPL-3.0): https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.24.8
- desugar_jdk_libs_nio 2.1.5 (GPL-2.0 with Classpath Exception): https://github.com/google/desugar_jdk_libs
- All other Java/Kotlin libraries: the exact versions are pinned in gradle/libs.versions.toml in this archive;
  their source jars are published next to the binaries on Maven Central / Google Maven.
Natives built by this project (natives.lock) ship their recipes in natives/ inside this archive.
TXT
tar --append -f "$work/src.tar" --transform "s#^#vidchain-$TAG/#" -C "$work" THIRD_PARTY_SOURCES.txt
mkdir -p "$OUT"
zstd -q -19 -T0 "$work/src.tar" -o "$OUT/SOURCES-$TAG.tar.zst"
ls -l "$OUT/SOURCES-$TAG.tar.zst"
