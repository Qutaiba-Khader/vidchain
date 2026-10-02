#!/usr/bin/env bash
# Runs inside the CI emulator (release.yml): Wi-Fi off so the -tcpdump capture sees all traffic,
# start canary, install + launch VidChain, let the start-up code run, end canary, collect evidence.
# Then the side-by-side identity checks (T0.2). The capture itself never leaves the runner (Q14);
# analyse_traffic.sh turns it into host lists.
# usage: runtime_test.sh <dir-with-signed-apks> <original-app-x86_64.apk>
set -u
APKS=$1 OLD_APK=$2
PKG=org.websnake.vidchain
OUT=runtime-evidence
mkdir -p "$OUT"

# -tcpdump records only the mobile-data interface; once Wi-Fi is up the capture goes blind.
adb shell svc wifi disable
adb shell svc data enable
for _ in $(seq 1 30); do
  adb shell dumpsys connectivity 2>/dev/null | grep -A2 'Active default network' | grep -qi 'MOBILE\|CELLULAR' && break
  sleep 2
done
sleep 5
adb shell ping -c 1 -W 3 vidchain-canary-start.example.com >/dev/null 2>&1 || true

apk=$(ls "$APKS"/*x86_64*.apk | head -1)
echo "installing $apk"
adb install -r -g "$apk" || { echo "FAIL: install"; exit 1; }
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 90                                   # start-up network work has a 30 s cap

adb shell ping -c 1 -W 3 vidchain-canary-end.example.com >/dev/null 2>&1 || true
sleep 10

pid=$(adb shell pidof "$PKG" | tr -d '\r')
adb logcat -d | grep -F "$PKG" | sed -E 's#https?://[^ ]+#<url>#g; s#[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[a-z]{2,}#<email>#g' > "$OUT/logcat-app.txt" || true
adb logcat -d -b crash > "$OUT/logcat-crash.txt" || true
adb shell dumpsys activity activities | grep -E "ResumedActivity" > "$OUT/resumed.txt" || true
adb shell screencap -p /sdcard/shot.png && adb pull /sdcard/shot.png "$OUT/screenshot.png" >/dev/null

echo "process id: ${pid:-none}"; cat "$OUT/resumed.txt"
fatal=$(grep -A3 "FATAL EXCEPTION" "$OUT/logcat-crash.txt" | grep -c "$PKG" || true)
echo "fatal exceptions in $PKG: $fatal"
[ -n "$pid" ] || { echo "FAIL: app not running after start-up"; exit 1; }
[ "$fatal" -eq 0 ] || { echo "FAIL: app crashed"; exit 1; }
grep -q "$PKG" "$OUT/resumed.txt" || { echo "FAIL: app is not in the foreground"; exit 1; }
echo "app started and stayed up"

# T0.2 identity checks: the original app installs next to VidChain, provider registered, launch works.
bash ci/emulator/identity_check.sh "$apk" "$OLD_APK" | tee "$OUT/identity.txt"
exit "${PIPESTATUS[0]}"
