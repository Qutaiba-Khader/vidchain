#!/usr/bin/env python3
"""Decide whether release.yml publishes, and what. Writes key=value lines to $GITHUB_OUTPUT (or stdout).
  release:  release-contract.json release.version is set, app/build.gradle matches it, tag v<version> absent.
  dry run:  workflow_dispatch with dry_run=true - everything up to a verified draft, then the draft and tag are removed.
env: DRY_RUN=true|false, EXISTING_TAGS (newline list), GITHUB_RUN_NUMBER"""
import json
import os
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
contract = json.loads((ROOT / "release-contract.json").read_text())
gradle = (ROOT / "app" / "build.gradle").read_text()
name = re.search(r'versionName\s+"([^"]+)"', gradle).group(1)
code = int(re.search(r"versionCode\s+(\d+)", gradle).group(1))
dry = os.environ.get("DRY_RUN", "false") == "true"
tags = set(os.environ.get("EXISTING_TAGS", "").split())
want, want_code = contract["release"]["version"], contract["release"]["version_code"]
out = {"dry": str(dry).lower(), "version": name, "version_code": str(code), "run": "false", "tag": ""}

if dry:
    out.update(run="true", tag=f"v0.0.0-dryrun.{os.environ.get('GITHUB_RUN_NUMBER', '0')}")
elif want is None:
    print("release-contract.json: release.version is null - nothing to release")
elif not re.fullmatch(r"\d+\.\d+\.\d+", want):
    sys.exit(f"release.version {want!r} is not MAJOR.MINOR.PATCH")
elif (name, code) != (want, want_code):
    sys.exit(f"app/build.gradle has {name} ({code}) but release-contract.json wants {want} ({want_code})")
elif f"v{want}" in tags:
    print(f"v{want} already released - nothing to do")
else:
    out.update(run="true", tag=f"v{want}")

lines = "".join(f"{k}={v}\n" for k, v in out.items())
print(lines, end="")
if os.environ.get("GITHUB_OUTPUT"):
    with open(os.environ["GITHUB_OUTPUT"], "a") as f:
        f.write(lines)
