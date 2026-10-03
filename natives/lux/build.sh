#!/usr/bin/env bash
# Builds lux (github.com/iawia002/lux) for Android 64-bit ABIs (T5.6): Go with CGO through the NDK's clang, PIE,
# 16 KB pages, stripped, no build id, trimmed paths. Runs inside the digest-pinned golang container of natives.yml.
# usage: build.sh <lux-commit> <ndk-dir> <out-dir>
set -euo pipefail
commit=$1 ndk=$2 out=$3
git clone --quiet https://github.com/iawia002/lux /tmp/lux
git -C /tmp/lux checkout --quiet "$commit"
cd /tmp/lux
tc="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
for spec in "arm64-v8a aarch64-linux-android arm64" "x86_64 x86_64-linux-android amd64"; do
  set -- $spec
  abi=$1 triple=$2 goarch=$3
  mkdir -p "$out/$abi"
  CGO_ENABLED=1 GOOS=android GOARCH="$goarch" CC="$tc/${triple}28-clang" \
    go build -trimpath -buildmode=pie -buildvcs=false \
      -ldflags="-s -w -buildid= -linkmode=external -extldflags=-Wl,-z,max-page-size=16384" \
      -o "$out/$abi/liblux.so" .
  sha256sum "$out/$abi/liblux.so"
done
