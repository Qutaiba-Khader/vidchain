#!/usr/bin/env python3
"""Rename audit (T0.2): the app identity is VidChain / org.websnake.vidchain.

Fails when:
  - applicationId is not the new package,
  - any "com.aio" use in the app module is not classified in tools/rename-allowlist.txt,
  - any string resource value still shows the old brand ("AIO" as a word, or upstream's official links),
  - a manifest taskAffinity or provider authority is hard-coded instead of using ${applicationId}.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
APP = ROOT / "app"
PKG = "org.websnake.vidchain"
errors = []

gradle = (APP / "build.gradle").read_text()
m = re.search(r'applicationId\s+"([^"]+)"', gradle)
if not m or m.group(1) != PKG:
    errors.append(f"applicationId is {m.group(1) if m else None!r}, expected {PKG!r}")

rules = []
for line in (ROOT / "tools" / "rename-allowlist.txt").read_text().splitlines():
    if line.strip() and not line.startswith("#"):
        cat, rx = line.split(None, 1)
        rules.append((cat, re.compile(rx)))

counts = {}
for p in sorted(APP.rglob("*")):
    rel = p.relative_to(ROOT).as_posix()
    if not p.is_file() or "/build/" in f"/{rel}" or p.suffix not in (".kt", ".java", ".xml", ".gradle", ".json", ".pro"):
        continue
    for no, line in enumerate(p.read_text(errors="replace").splitlines(), 1):
        if "com.aio" not in line:
            continue
        hit = next((c for c, rx in rules if rx.search(line)), None)
        if hit:
            counts[hit] = counts.get(hit, 0) + 1
        else:
            errors.append(f"UNCLASSIFIED com.aio: {rel}:{no}: {line.strip()[:120]}")

for p in sorted((APP / "src" / "main" / "res").glob("values*/*.xml")):
    rel = p.relative_to(ROOT).as_posix()
    for no, line in enumerate(p.read_text().splitlines(), 1):
        for m in re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', line):
            name, value = m.groups()
            if re.search(r"\bAIO\b", value) or "shibaFoss/AIO-Video-Downloader" in value:
                errors.append(f"OLD BRAND in string value: {rel}:{no} {name}")

manifest = (APP / "src" / "main" / "AndroidManifest.xml").read_text()
for attr in ("taskAffinity", "authorities"):
    for v in re.findall(attr + r'="([^"]+)"', manifest):
        if v and "${applicationId}" not in v:
            errors.append(f"hard-coded android:{attr}={v!r} (use ${{applicationId}})")

if sum(counts.values()) < 50:
    errors.append(f"only {sum(counts.values())} com.aio uses seen - the scan itself is broken (expected ~120)")
if errors:
    print("\n".join(errors))
    sys.exit(1)
print(f"OK: applicationId {PKG}; classified com.aio uses: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
