#!/usr/bin/env python3
"""Record cassettes (run by .github/workflows/record-fixtures.yml, never by hand-editing):
fetch every source in cassettes/sources.json from the real internet and store body + status + selected
headers + sha256 + date + tool versions. The result is reviewed and committed; world.py replays it.
usage: record.py <cassettes-dir>"""
import datetime
import hashlib
import json
import os
import pathlib
import subprocess
import sys

D = pathlib.Path(sys.argv[1])
src = json.loads((D / "sources.json").read_text())["sources"]
curl_v = subprocess.run(["curl", "--version"], capture_output=True, text=True).stdout.splitlines()[0]
out = []
for s in src:
    cdir = D / s["id"]
    cdir.mkdir(parents=True, exist_ok=True)
    p = subprocess.run(["curl", "-sS", "-L", "--max-time", "60", "-A", "VidChain-fixture-recorder/1 (+https://github.com/Qutaiba-Khader/vidchain)",
                        "-D", str(cdir / "headers.txt"), "-o", str(cdir / "body.bin"), "-w", "%{http_code}", s["url"]],
                       capture_output=True, text=True, check=True)
    body = (cdir / "body.bin").read_bytes()
    ctype = next((l.split(":", 1)[1].strip() for l in (cdir / "headers.txt").read_text().splitlines()[::-1]
                  if l.lower().startswith("content-type:")), "text/html")
    (cdir / "headers.txt").unlink()
    out.append({"id": s["id"], "url": s["url"], "status": int(p.stdout), "headers": {"Content-Type": ctype},
                "sha256": hashlib.sha256(body).hexdigest(), "bytes": len(body), "licence": s["licence"],
                "recorded": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
                "tool": curl_v,
                "recorded_by": f'{os.environ.get("GITHUB_SERVER_URL", "local")}/{os.environ.get("GITHUB_REPOSITORY", "")}/actions/runs/{os.environ.get("GITHUB_RUN_ID", "")}'})
    print(f"recorded {s['id']}: HTTP {p.stdout}, {len(body)} bytes")
(D / "manifest.json").write_text(json.dumps({"cassettes": out}, indent=2) + "\n")
