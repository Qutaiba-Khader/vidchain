#!/usr/bin/env bash
# APK gate (dex + manifest), run in CI on every built APK:
#   - package org.websnake.vidchain; optional: versionName == $VERSION, signer == $CERT_SHA256
#   - no ad SDK, tracker SDK or developer/geolocation host anywhere in the APK (code, resources, assets, libs)
#   - stub sentinel: the backend stub classes contain no URL, no network, timer or Parse call
# It never lists sites (owner Q27/Q28).
# usage: tools/apk_check.sh <dir-with-apks>     needs ANDROID_HOME (build-tools: aapt2, apksigner, dexdump)
set -Eeuo pipefail
DIR=${1:?usage: apk_check.sh <dir>}
PKG=org.websnake.vidchain
BT=$(ls -d "${ANDROID_HOME:?ANDROID_HOME not set}"/build-tools/* | sort -V | tail -1)
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
fail=0
shopt -s nullglob
apks=("$DIR"/*.apk)
[[ ${#apks[@]} -gt 0 ]] || { echo "FAIL: no APK in $DIR"; exit 1; }

ads='Lcom/(google/android/gms/ads|google/ads|unity3d/ads|unity3d/services|unity3d/mediation|amazon/device/ads|applovin|ironsource|facebook/ads|vungle|startapp|chartboost|mbridge|bytedance/sdk/openadsdk|yandex/mobile/ads|inmobi|appodeal|adcolony)/'
trk='Lcom/(google/firebase|google/android/gms/measurement|appsflyer|adjust/sdk|onesignal|flurry|mixpanel|amplitude)/|Lio/sentry/'
hosts='([a-z0-9-]+\.)*(tubeaio|ip-api)\.com|ipinfo\.io|ipapi\.co|ipwho\.is|back4app\.(io|com)|parseapi\.[a-z.]+|[a-z0-9-]+\.supabase\.(co|in)|raw\.githubusercontent\.com/shibafoss/aio-video-downloader'

for apk in "${apks[@]}"; do
  name=$(basename "$apk")
  badging=$("$BT/aapt2" dump badging "$apk" 2>/dev/null || true)
  pkg=$(sed -n "s/.*package: name='\([^']*\)'.*/\1/p" <<<"$badging")
  ver=$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$badging")
  certs=$("$BT/apksigner" verify --print-certs "$apk" 2>&1 || true)
  cert=$(awk '/certificate SHA-256 digest/ {print $NF; exit}' <<<"$certs")
  rm -rf "$work/dex" && mkdir "$work/dex"
  unzip -oq "$apk" 'classes*.dex' -d "$work/dex" || { echo "$name: FAIL: no classes*.dex"; fail=1; continue; }
  s=$(cat "$work"/dex/*.dex | strings -n 6)
  all=$(unzip -p "$apk" | strings -n 6)
  a=$(grep -cE "$ads" <<<"$s" || true)
  t=$(grep -cE "$trk" <<<"$s" || true)
  h=$(grep -oiE "$hosts" <<<"$all" | tr 'A-Z' 'a-z' | sort -u | tr '\n' ' ' || true); h=${h% }
  # stub sentinel: disassemble only the backend stub classes
  stub=""
  for d in "$work"/dex/*.dex; do
    stub+=$("$BT/dexdump" -d "$d" 2>/dev/null | awk '
      /Class descriptor/ { inside = ($0 ~ /Lapp\/core\/engines\/backend\//) }
      inside { print }')
  done
  stub_classes=$(grep -c "Class descriptor" <<<"$stub" || true)
  stub_bad=$(grep -E 'const-string.*"https?://|Lokhttp3/|Ljava/net/|Lcom/parse/|Ljava/util/Timer|Lio/github/jan/supabase' <<<"$stub" | head -3 || true)
  echo "$name: pkg=$pkg ver=$ver cert=${cert:0:16}… ads=$a trackers=$t hosts=[${h}] stub-classes=$stub_classes"
  [[ "$pkg" == "$PKG" ]] || { echo "  FAIL: package is not $PKG"; fail=1; }
  [[ -n "$cert" ]] || { echo "  FAIL: not signed"; fail=1; }
  if [[ -n "${CERT_SHA256:-}" && "$cert" != "$CERT_SHA256" ]]; then echo "  FAIL: signed with an unexpected key"; fail=1; fi
  if [[ -n "${VERSION:-}" && "$ver" != "$VERSION" ]]; then echo "  FAIL: versionName is not $VERSION"; fail=1; fi
  [[ "$a" -eq 0 && "$t" -eq 0 && -z "$h" ]] || { echo "  FAIL: ad/tracker code or developer host present"; fail=1; }
  [[ "$stub_classes" -ge 3 ]] || { echo "  FAIL: stub sentinel saw $stub_classes backend classes (expected >= 3) - scan broken?"; fail=1; }
  [[ -z "$stub_bad" ]] || { echo "  FAIL: a backend stub gained network/timer/Parse code:"; echo "$stub_bad" | sed 's/^/    /'; fail=1; }
done
[[ $fail -eq 0 ]] && echo "PASS: ${#apks[@]} APK(s)" || exit 1
