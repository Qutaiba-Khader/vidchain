#!/usr/bin/env python3
"""Size budget: every release APK must stay within tools/size-budget.json (+ tolerance).
usage: tools/size_budget.py <dir-with-apks> [budget.json]"""
import json
import pathlib
import re
import sys

d = pathlib.Path(sys.argv[1])
budget = json.loads(pathlib.Path(sys.argv[2] if len(sys.argv) > 2 else
                                 pathlib.Path(__file__).resolve().parent / "size-budget.json").read_text())
tol = budget["tolerance"]
bad, seen = [], 0
for apk in sorted(d.glob("*.apk")):
    m = re.search(r"(arm64-v8a|armeabi-v7a|x86_64|x86|universal)", apk.name)
    if not m:
        bad.append(f"{apk.name}: no ABI in file name")
        continue
    abi, size = m.group(1), apk.stat().st_size
    limit = int(budget[abi] * (1 + tol))
    seen += 1
    print(f"{apk.name}: {size/1e6:.1f} MB (budget {budget[abi]/1e6:.1f} MB, limit {limit/1e6:.1f} MB)")
    if size > limit:
        bad.append(f"{apk.name}: {size} bytes > limit {limit}")
if seen == 0:
    bad.append("no APK checked")
if bad:
    print("\n".join(bad))
    sys.exit(1)
print(f"OK: {seen} APK(s) within budget")
