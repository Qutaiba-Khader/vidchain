#!/usr/bin/env bash
# Android NDK r28c for the natives builds (natives.yml, canary.yml): checksum from Google's repository manifest.
# usage: ndk.sh <install-parent-dir>   -> <dir>/android-ndk-r28c
set -euo pipefail
dest=$1
curl -fsSL -o /tmp/ndk.zip https://dl.google.com/android/repository/android-ndk-r28c-linux.zip
echo "a7b54a5de87fecd125a17d54f73c446199e72a64  /tmp/ndk.zip" | sha1sum -c -
unzip -q /tmp/ndk.zip -d "$dest"
rm /tmp/ndk.zip
