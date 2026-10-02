#!/usr/bin/env bash
# T0.2 identity checks, run on the CI emulator by release.yml (T0.6):
#   1. the original app (any build of com.aio.video_downloader) and VidChain install side by side
#      (no INSTALL_FAILED_CONFLICTING_PROVIDER / permission / package clash);
#   2. VidChain's FileProvider is registered under ${applicationId}.provider;
#   3. VidChain starts to its launcher activity with both apps installed.
# usage: identity_check.sh <vidchain.apk> <original-app.apk>
set -euo pipefail
NEW_APK=$1 OLD_APK=$2
PKG=org.websnake.vidchain OLD_PKG=com.aio.video_downloader
adb install -r -g "$OLD_APK"
adb install -r -g "$NEW_APK"
# whole outputs captured first: grep -q closing a pipe early would SIGPIPE the writer and, under
# pipefail, turn a match into a failure
pkgs=$(adb shell pm list packages | tr -d '\r')
grep -qx "package:$OLD_PKG" <<<"$pkgs" || { echo "FAIL: original app not installed (installed: $(grep -iE "aio|vidchain" <<<"$pkgs" | tr "\n" " "))"; exit 1; }
grep -qx "package:$PKG" <<<"$pkgs" || { echo "FAIL: VidChain not installed"; exit 1; }
dump=$(adb shell dumpsys package "$PKG" | tr -d '\r')
grep -q "$PKG.provider" <<<"$dump" || { echo "FAIL: FileProvider $PKG.provider not registered"; exit 1; }
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 8
adb shell pidof "$PKG" >/dev/null || { echo "FAIL: VidChain not running after launch"; exit 1; }
echo "OK: side-by-side install, provider $PKG.provider registered, app launched"
