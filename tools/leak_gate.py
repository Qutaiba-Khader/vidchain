#!/usr/bin/env python3
"""Leak gate: no tracked file may contain a token whose sha256 is in tools/forbidden-hashes.txt.
Complements gitleaks (generic secret patterns) with project-specific private names, hosts and keys.
usage: tools/leak_gate.py [path ...]   default = every file tracked by git; exit 1 on any hit."""
import hashlib
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
HASHES = {l.strip() for l in (ROOT / "tools" / "forbidden-hashes.txt").read_text().splitlines()
          if l.strip() and not l.startswith("#")}
TOKEN = re.compile(rb"[A-Za-z0-9._\-]{6,}")

paths = sys.argv[1:] or subprocess.run(["git", "-C", str(ROOT), "ls-files", "-z"], capture_output=True,
                                       check=True).stdout.decode().split("\0")
hits, scanned = [], 0
for rel in filter(None, paths):
    p = (ROOT / rel) if not pathlib.Path(rel).is_absolute() else pathlib.Path(rel)
    if not p.is_file():
        continue
    scanned += 1
    data = p.read_bytes()
    for m in TOKEN.finditer(data):
        tok = m.group(0)
        for cand in {tok, tok.rstrip(b"."), tok.strip(b"-_.")}:
            if hashlib.sha256(cand).hexdigest() in HASHES:
                line = data.count(b"\n", 0, m.start()) + 1
                hits.append(f"{rel}:{line}: forbidden token (sha256 {hashlib.sha256(cand).hexdigest()[:12]}…)")
if scanned < 50 and not sys.argv[1:]:
    hits.append(f"only {scanned} files scanned - the scan itself is broken")
if hits:
    print("\n".join(sorted(set(hits))))
    sys.exit(1)
print(f"OK: {scanned} files, {len(HASHES)} forbidden tokens, no hit")
