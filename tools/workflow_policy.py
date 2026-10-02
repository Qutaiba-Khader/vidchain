#!/usr/bin/env python3
"""Workflow policy gate (in addition to actionlint and zizmor):
  - no pull_request_target anywhere;
  - `secrets.` and `environment:` only in release.yml (signing never reachable from PR/fork workflows);
  - no `set -x` (would print secrets);
  - every workflow declares top-level `permissions:`;
  - every `uses:` of a third-party action is pinned to a full commit SHA.
usage: tools/workflow_policy.py [dir]   default .github/workflows; exit 1 on any violation."""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
D = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / ".github" / "workflows"
bad = []
files = sorted(list(D.glob("*.yml")) + list(D.glob("*.yaml")))
if not files:
    bad.append(f"no workflow files in {D}")
for f in files:
    t = f.read_text()
    code = "\n".join(l for l in t.splitlines() if not l.lstrip().startswith("#"))
    if "pull_request_target" in code:
        bad.append(f"{f.name}: pull_request_target is forbidden")
    if f.name != "release.yml":
        if re.search(r"\bsecrets\.", code):
            bad.append(f"{f.name}: uses secrets.* (only release.yml may)")
        if re.search(r"^\s*environment\s*:", code, re.M):
            bad.append(f"{f.name}: uses environment: (only release.yml may)")
    if re.search(r"\bset\s+-[a-zA-Z]*x", code):
        bad.append(f"{f.name}: set -x is forbidden")
    if not re.search(r"^permissions\s*:", code, re.M):
        bad.append(f"{f.name}: no top-level permissions:")
    for m in re.finditer(r"uses:\s*([^\s#]+)", code):
        ref = m.group(1)
        if ref.startswith("./"):
            continue
        if not re.search(r"@[0-9a-f]{40}$", ref):
            bad.append(f"{f.name}: action not pinned to a commit SHA: {ref}")
if bad:
    print("\n".join(bad))
    sys.exit(1)
print(f"OK: {len(files)} workflow(s) pass the policy")
