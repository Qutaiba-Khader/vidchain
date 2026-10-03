#!/usr/bin/env python3
"""Text of a cassette refresh pull request (record-fixtures.yml, T6.1): what changed per cassette, compared with the
manifest committed in git. usage: refresh_pr.py <cassettes-dir> <out-dir> <run-url>"""
import json
import pathlib
import subprocess
import sys

d, out, run = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3]
old = subprocess.run(["git", "show", f"HEAD:{d / 'manifest.json'}"], capture_output=True, text=True)
before = {c["id"]: c for c in json.loads(old.stdout)["cassettes"]} if old.returncode == 0 else {}
after = {c["id"]: c for c in json.loads((d / "manifest.json").read_text())["cassettes"]}
rows = []
for cid in sorted(set(before) | set(after)):
    b, a = before.get(cid), after.get(cid)
    if not a:
        rows.append(f"| {cid} | removed | | |")
    elif not b:
        rows.append(f"| {cid} | new | | HTTP {a['status']}, {a['bytes']} B, `{a['sha256'][:12]}` |")
    elif b["sha256"] != a["sha256"] or b["status"] != a["status"]:
        rows.append(f"| {cid} | changed | HTTP {b['status']}, {b['bytes']} B, `{b['sha256'][:12]}` | HTTP {a['status']}, {a['bytes']} B, `{a['sha256'][:12]}` |")
    else:
        rows.append(f"| {cid} | same | `{b['sha256'][:12]}` | `{a['sha256'][:12]}` |")
changed = sum(not r.split("|")[2].strip() == "same" for r in rows)
out.mkdir(parents=True, exist_ok=True)
(out / "title.txt").write_text(f"Refresh fixture cassettes ({changed} changed)\n")
(out / "pr.md").write_text(
    f"Cassettes re-recorded by {run}.\n\n| cassette | change | before | after |\n|---|---|---|---|\n" + "\n".join(rows)
    + "\n\nReview the bodies before merging: CI replays exactly these bytes. Only openly licensed sources (Q12).\n")
print(f"{changed} cassette(s) changed")
