#!/usr/bin/env bash
# Re-creates upstream/ from the pinned upstream commit and verifies it against VENDORED_FROM.
# upstream/ is a read-only provenance record (licence + "what changed" baseline); it is never synced.
#   tools/vendor.sh            verify upstream/ matches VENDORED_FROM (default)
#   tools/vendor.sh --init     first import: download, record sha256, extract
set -euo pipefail
cd "$(dirname "$0")/.."

REPO="shibaFoss/AIO-Video-Downloader"
COMMIT="cd975f230e865e8f8150456a9dbf5760cfdbafc0"
URL="https://codeload.github.com/${REPO}/tar.gz/${COMMIT}"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

curl -fsSL "$URL" -o "$tmp/upstream.tar.gz"
sha=$(sha256sum "$tmp/upstream.tar.gz" | cut -d' ' -f1)
mkdir "$tmp/x"
tar -xzf "$tmp/upstream.tar.gz" -C "$tmp/x" --strip-components=1
tree_sha=$(cd "$tmp/x" && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)

if [[ "${1:-}" == "--init" ]]; then
  [[ -e upstream ]] && { echo "upstream/ already exists - refusing to overwrite" >&2; exit 1; }
  mv "$tmp/x" upstream
  cat > VENDORED_FROM <<EOF
repo: https://github.com/${REPO}
branch: master
commit: ${COMMIT}
tarball: ${URL}
tarball_sha256: ${sha}
tree_sha256: ${tree_sha}
imported: $(date -u +%Y-%m-%dT%H:%M:%SZ)
purpose: licence and provenance record only - no upstream sync (owner decision Q20 = C)
EOF
  echo "upstream/ imported (tree ${tree_sha})"
  exit 0
fi

want=$(awk -F': ' '$1=="tree_sha256"{print $2}' VENDORED_FROM)
have=$(cd upstream && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)
[[ "$tree_sha" == "$want" ]] || { echo "FAIL: pinned commit no longer yields the recorded tree ($tree_sha != $want)" >&2; exit 1; }
[[ "$have" == "$want" ]] || { echo "FAIL: upstream/ was modified ($have != $want)" >&2; exit 1; }
echo "OK: upstream/ matches ${COMMIT} (tree ${want})"
