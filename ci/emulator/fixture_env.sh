#!/usr/bin/env bash
# Prepare a CI emulator (API 33 google_apis, -writable-system, -http-proxy -> fixture world) for app tests:
# fixture CA in the system store, every fixture host in /system/etc/hosts, VidChain installed,
# "All files access" granted, first start done, fixture CA appended to the bundled Python bundle.
# usage: fixture_env.sh <apk> <ca-pem>
set -u
APK=$1 CA=$2
PKG=org.websnake.vidchain
HOSTS="fixture files short members cdn drive www hls dash"
boot_wait() { adb wait-for-device; until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done; }

adb root >/dev/null; adb wait-for-device
adb shell avbctl disable-verification >/dev/null 2>&1 || true
adb disable-verity >/dev/null 2>&1 || true
if ! adb remount 2>&1 | grep -qi "remount succeeded"; then
  adb reboot; boot_wait; adb root >/dev/null; adb wait-for-device; adb remount >/dev/null
fi
hash=$(openssl x509 -inform PEM -subject_hash_old -in "$CA" -noout)
adb push "$CA" "/system/etc/security/cacerts/$hash.0" >/dev/null
adb shell chmod 644 "/system/etc/security/cacerts/$hash.0"
for h in $HOSTS; do adb shell "echo '203.0.113.10 $h.vidchain.test' >> /system/etc/hosts"; done
adb reboot; boot_wait; adb root >/dev/null; adb wait-for-device
adb shell svc wifi disable; adb shell svc data enable
adb shell settings put global window_animation_scale 0; adb shell settings put global transition_animation_scale 0
adb install -r -g "$APK" >/dev/null || { echo "FAIL: install"; exit 1; }
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
for _ in $(seq 1 60); do
  PYCERT=$(adb shell "find /data/data/$PKG -path '*python/usr/etc/tls/cert.pem' 2>/dev/null" | tr -d '\r' | head -1)
  [ -n "$PYCERT" ] && break; sleep 2
done
[ -n "$PYCERT" ] || { echo "FAIL: bundled Python not unpacked"; exit 1; }
adb push "$CA" /data/local/tmp/fixture-ca.pem >/dev/null
adb shell "cat /data/local/tmp/fixture-ca.pem >> '$PYCERT'"
sleep 20
adb shell am force-stop "$PKG"
echo "fixture emulator ready (CA $hash, hosts: $HOSTS, python bundle patched)"
