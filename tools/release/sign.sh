#!/usr/bin/env bash
# Sign-only step (runs in the protected `release` environment, never runs Gradle):
# zipalign every APK for 16 KB pages, sign with the VidChain key, prove the certificate is the pinned one,
# and write the final asset names from release-contract.json.
# usage: sign.sh <in-dir> <out-dir>   env: KEYSTORE (path), STORE_PW, KEY_ALIAS, KEY_PW, ANDROID_HOME
set -Eeuo pipefail
IN=$1 OUT=$2
: "${KEYSTORE:?}" "${STORE_PW:?}" "${KEY_ALIAS:?}" "${KEY_PW:?}"
BT=$(ls -d "${ANDROID_HOME:?}"/build-tools/* | sort -V | tail -1)
PIN=$(grep -v '^#' signing/cert-sha256.txt | tr -d '[:space:]')
mkdir -p "$OUT"
for abi in arm64-v8a armeabi-v7a x86 x86_64 universal; do
  src="$IN/app-$abi-release.apk"
  dst="$OUT/$(python3 -c "import json;print(json.load(open('release-contract.json'))['assets']['$abi'])")"
  [[ -f "$src" ]] || { echo "FAIL: missing $src"; exit 1; }
  "$BT/zipalign" -f -P 16 4 "$src" "$dst.aligned"
  "$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass env:STORE_PW --ks-key-alias "$KEY_ALIAS" --key-pass env:KEY_PW \
    --out "$dst" "$dst.aligned"
  rm -f "$dst.aligned" "$dst.idsig"
  "$BT/zipalign" -c -P 16 4 "$dst" >/dev/null || { echo "FAIL: $dst not 16 KB aligned"; exit 1; }
  cert=$("$BT/apksigner" verify --print-certs "$dst" | awk '/certificate SHA-256 digest/ {print $NF; exit}')
  [[ "$cert" == "$PIN" ]] || { echo "FAIL: $dst signed with $cert, pinned $PIN"; exit 1; }
  echo "signed $(basename "$dst") cert=${cert:0:16}…"
done
