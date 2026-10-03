#!/usr/bin/env bash
# Inside the CI emulator (API 33 google_apis, -writable-system, -http-proxy -> local mitmproxy):
# trust the fixture CA system-wide, map fixture.vidchain.test, install the unmodified release APK,
# let it start (yt-dlp/Python is unpacked), add the CA to the bundled Python's cert.pem, then make the
# three HTTP stacks talk: shared links that the app hands to OkHttp, WebView and yt-dlp.
# usage: run_spike.sh <apk> <ca-pem>
set -u
APK=$1 CA=$2
PKG=org.websnake.vidchain
OUT=fixture-evidence; mkdir -p "$OUT"
log() { echo "[spike] $*"; }

adb root >/dev/null; adb wait-for-device
adb shell avbctl disable-verification >/dev/null 2>&1 || true
adb disable-verity >/dev/null 2>&1 || true
if ! adb remount 2>&1 | tee "$OUT/remount.txt" | grep -qi "remount succeeded"; then
  log "first remount needs a reboot"; adb reboot; adb wait-for-device
  until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
  adb root >/dev/null; adb wait-for-device; adb remount | tee -a "$OUT/remount.txt"
fi
hash=$(openssl x509 -inform PEM -subject_hash_old -in "$CA" -noout)
adb push "$CA" "/system/etc/security/cacerts/$hash.0" >/dev/null
adb shell chmod 644 "/system/etc/security/cacerts/$hash.0"
adb shell "echo '203.0.113.10 fixture.vidchain.test' >> /system/etc/hosts"
log "rebooting so every process sees the CA and hosts entry"
adb reboot; adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
adb root >/dev/null; adb wait-for-device
adb shell ls -l "/system/etc/security/cacerts/$hash.0" | tee "$OUT/ca-installed.txt"
adb shell svc wifi disable; adb shell svc data enable

adb install -r -g "$APK" || { log "FAIL: install"; exit 1; }
# the app asks for "All files access" (MANAGE_EXTERNAL_STORAGE) before it handles links; grant it like a user would
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 45                       # first start unpacks Python (youtubedl-android init) and runs start-up work
PYCERT=$(adb shell "find /data/data/$PKG -path '*python/usr/etc/tls/cert.pem' 2>/dev/null" | tr -d '\r' | head -1)
log "python cert bundle: ${PYCERT:-NOT FOUND}"
echo "${PYCERT:-NOT FOUND}" > "$OUT/python-cert-path.txt"
if [ -n "$PYCERT" ]; then
  adb push "$CA" /data/local/tmp/fixture-ca.pem >/dev/null
  adb shell "cat /data/local/tmp/fixture-ca.pem >> '$PYCERT'"
fi

share() { adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'$1'" -p "$PKG" >/dev/null; sleep "$2"; }
log "share 1: unlisted page -> in-app browser (WebView)"
share "https://fixture.vidchain.test/page.html" 30
adb shell input keyevent KEYCODE_BACK; sleep 3
log "share 2: listed site (vimeo) -> yt-dlp format listing (Python)"
share "https://vimeo.com/76979871" 60
adb shell input keyevent KEYCODE_BACK; sleep 3
log "share 3: YouTube -> NewPipe metadata + yt-dlp"
share "https://www.youtube.com/watch?v=aqz-KE-bpKQ" 60
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png "$OUT/screen.png" >/dev/null
adb logcat -d | grep -F "$PKG" | sed -E 's#https?://[^ ]+#<url>#g' | tail -400 > "$OUT/logcat-app.txt" || true
log "done"
