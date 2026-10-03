#!/usr/bin/env python3
"""Validate the flow-tracing records (T1.4): docs/verdict-ledger.jsonl rows are complete and consistent,
docs/baseline-findings.json fingerprints are unique, and no row hides an unfixed finding without a
`carried_to` task or a blocker. Exit 1 on any problem."""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
bad = []
rows = [json.loads(l) for l in (ROOT / "docs" / "verdict-ledger.jsonl").read_text().splitlines() if l.strip()]
seen = set()
for i, r in enumerate(rows, 1):
    for k in ("task", "date", "new_findings", "fixed", "attempts", "verdict", "commit"):
        if k not in r:
            bad.append(f"row {i}: missing {k}")
    t = r.get("task", "")
    if not re.fullmatch(r"T\d\.\d+", t):
        bad.append(f"row {i}: bad task id {t!r}")
    if t in seen:
        bad.append(f"row {i}: {t} listed twice")
    seen.add(t)
    if r.get("attempts", 0) > 5:
        bad.append(f"row {i}: {t} used {r['attempts']} attempts - more than 5 is a real blocker for the owner")
    unfixed = r.get("new_findings", 0) - r.get("fixed", 0)
    if unfixed > 0 and not r.get("carried_to") and "blocker" not in r.get("verdict", ""):
        bad.append(f"row {i}: {t} has {unfixed} unfixed finding(s) without carried_to or a blocker verdict")
fps = [f["fingerprint"] for f in json.loads((ROOT / "docs" / "baseline-findings.json").read_text())["findings"]]
if len(fps) != len(set(fps)):
    bad.append("baseline-findings.json: duplicate fingerprints")
if bad:
    print("\n".join(bad)); sys.exit(1)
print(f"OK: {len(rows)} verdict rows, {len(fps)} frozen findings")
