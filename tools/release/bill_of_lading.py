#!/usr/bin/env python3
"""Bill of lading: what exactly is in a release, for anyone to check.
usage: bill_of_lading.py <dir-with-release-files> <tag> <commit> <ci-run-url> > bill-of-lading.json"""
import hashlib
import json
import pathlib
import re
import subprocess
import sys

d, tag, commit, run_url = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4]
ROOT = pathlib.Path(__file__).resolve().parents[2]
gradle = (ROOT / "app" / "build.gradle").read_text()
vendored = dict(l.split(": ", 1) for l in (ROOT / "VENDORED_FROM").read_text().splitlines() if ": " in l)
wrapper = re.search(r"distributionUrl=.*gradle-(.+)-(?:bin|all)\.zip", (ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties").read_text())
sys.path.insert(0, str(ROOT / "tools" / "release"))
import method_matrix  # noqa: E402
matrix, bad = method_matrix.check(d)
if bad:
    sys.exit("\n".join(bad))
files = []
for f in sorted(d.iterdir()):
    if f.is_file() and f.name != "bill-of-lading.json":
        files.append({"name": f.name, "bytes": f.stat().st_size, "sha256": hashlib.sha256(f.read_bytes()).hexdigest()})
print(json.dumps({
    "tag": tag,
    "commit": commit,
    "ci_run": run_url,
    "package": json.loads((ROOT / "release-contract.json").read_text())["package"],
    "versionName": re.search(r'versionName\s+"([^"]+)"', gradle).group(1),
    "versionCode": int(re.search(r"versionCode\s+(\d+)", gradle).group(1)),
    "signing_cert_sha256": [l for l in (ROOT / "signing" / "cert-sha256.txt").read_text().split() if not l.startswith("#")][-1],
    "based_on": {"repo": vendored.get("repo"), "commit": vendored.get("commit"), "tarball_sha256": vendored.get("tarball_sha256")},
    "toolchain": {"gradle_wrapper": wrapper.group(1) if wrapper else None,
                  "java": subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.splitlines()[0]},
    "natives": json.loads((ROOT / "natives.lock").read_text()) if (ROOT / "natives.lock").exists() else {},
    "methods_per_apk": matrix,
    "files": files,
}, indent=2))
