#!/usr/bin/env bash
# Negative controls: every source gate must FAIL on a seeded violation (a gate that cannot fail
# proves nothing). Runs on a scratch copy; the real tree is never touched.
# usage: tools/gate_selftest.sh            (from anywhere; exit 1 if any gate failed to catch its seed)
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
work=$(mktemp -d); trap 'rm -rf -- "$work"' EXIT
missed=0

fresh() {  # $1 = scratch dir name; copies the tracked tree
  local d="$work/$1"; mkdir -p "$d"
  git -C "$ROOT" ls-files -z | (cd "$ROOT" && xargs -0 cp --parents -t "$d")
  printf '%s' "$d"
}
expect_fail() {  # $1 = label, rest = command that must exit non-zero
  local label=$1; shift
  if "$@" >"$work/out.txt" 2>&1; then
    echo "MISSED: $label - the gate passed on a seeded violation"; missed=1
  else
    echo "caught: $label ($(grep -m1 -E 'FAIL|UNDECLARED|OLD BRAND|forbidden|not pinned|pull_request_target|applicationId|limit|SEAM|ELF' "$work/out.txt" | cut -c1-90))"
  fi
}

d=$(fresh drift);   echo "// seeded" >> "$d/app/src/main/java/app/core/engines/downloader/DownloadSystem.kt"
expect_fail "drift check: undeclared edit of an upstream file" python3 "$d/tools/drift_check.py"

d=$(fresh stray);   echo seeded > "$d/app/src/main/stray.txt"
expect_fail "drift check: undeclared new file in the app tree" python3 "$d/tools/drift_check.py"

d=$(fresh seamedit); sed -i "s/^\(\s*\)INSTANCE = this$/\1INSTANCE = this; INSTANCE.hashCode()/" "$d/app/src/main/java/app/core/AIOApp.kt"
expect_fail "seam gate: upstream line changed in a seamed file" python3 "$d/tools/seam_check.py"

d=$(fresh seamstray); echo "// FALLBACK-SEAM:stray" >> "$d/app/src/main/java/app/core/engines/downloader/DownloadSystem.kt"
expect_fail "seam gate: seam in an undeclared upstream file" python3 "$d/tools/seam_check.py"

d=$(fresh seammixed); sed -i '/FALLBACK-SEAM:settings/d' "$d/app/src/main/java/app/ui/main/fragments/settings/SettingsFragment.kt"
expect_fail "seam gate: a seamed file with owner changes (CHANGES.md) lost its seam" python3 "$d/tools/seam_check.py"

d=$(fresh dupdecl); printf '```paths\nmodified app/src/main/java/app/core/AIOApp.kt\n```\n' >> "$d/REMOVED.md"; printf '```paths\nmodified app/src/main/java/app/core/AIOApp.kt\n```\n' >> "$d/CHANGES.md"
expect_fail "drift check: a file declared in two explaining ledgers" python3 "$d/tools/drift_check.py"

mkdir -p "$work/elf/lib/arm64-v8a"; python3 -c "import sys; sys.path.insert(0, '$ROOT/tools'); import elf; open('$work/elf/lib/arm64-v8a/libseed.so','wb').write(elf.make_seed(0x1000))"
expect_fail "ELF gate: a 4 KB aligned library" python3 "$ROOT/tools/elf_gate.py" "$work/elf"

d=$(fresh rename);  sed -i 's/applicationId "org.websnake.vidchain"/applicationId "com.aio.video_downloader"/' "$d/app/build.gradle"
expect_fail "rename audit: old applicationId" python3 "$d/tools/rename_audit.py"

d=$(fresh brand);   sed -i '0,/VidChain Images/s//AIO Images/' "$d/app/src/main/res/values/strings_library.xml"
expect_fail "rename audit: old brand in a string" python3 "$d/tools/rename_audit.py"

expect_fail "removed-features gate: original upstream code" python3 "$ROOT/tools/removed_check.py" "$ROOT/upstream"

d=$(fresh removed); sed -i 's#"http://127.0.0.1:9"#"https://raw.githubusercontent.com/shibaFoss/AIO-Video-Downloader"#' "$d/app/src/main/java/app/core/engines/updater/AIOUpdater.kt"
expect_fail "removed-features gate: self-updater URL restored" python3 "$d/tools/removed_check.py" "$d"

d=$(fresh leak);    printf 'see %s%s\n' "tubeaio" "-noads" > "$d/seeded.txt"
expect_fail "leak gate: private repository name" python3 "$d/tools/leak_gate.py" "$d/seeded.txt"

mkdir -p "$work/wf"; printf 'on: pull_request_target\npermissions: {}\njobs:\n  x:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: actions/checkout@v5\n' > "$work/wf/bad.yml"
expect_fail "workflow policy: pull_request_target + unpinned action" python3 "$ROOT/tools/workflow_policy.py" "$work/wf"

mkdir -p "$work/apk"; head -c 2048 /dev/zero > "$work/apk/app-arm64-v8a-release.apk"
printf '{"tolerance":0.0,"arm64-v8a":1000,"armeabi-v7a":1,"x86":1,"x86_64":1,"universal":1}' > "$work/budget.json"
expect_fail "size budget: APK above its budget" python3 "$ROOT/tools/size_budget.py" "$work/apk" "$work/budget.json"

d=$(fresh ledger);  printf '{"task": "T9.9", "date": "x", "new_findings": 2, "fixed": 1, "attempts": 1, "verdict": "clean", "commit": "x"}\n' >> "$d/docs/verdict-ledger.jsonl"
expect_fail "verdict ledger: unfixed finding without carried_to" python3 "$d/tools/ledger_check.py"

[[ $missed -eq 0 ]] && echo "OK: every gate caught its seeded violation" || exit 1
