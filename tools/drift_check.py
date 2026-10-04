#!/usr/bin/env python3
"""Drift check: the app tree may differ from upstream/ only where a change is declared.

Every path that differs from upstream/ (modified, added inside the upstream layout, or
deleted) must be declared in one of the change ledgers:
  BUILD-FIXES.md  files needed to compile the public code (author's git-ignored files)
  BRANDING.md     the new app identity (package, name, icon, links)
  REMOVED.md      removals of ads, tracking, self-updater, kill switch, developer sync
  seams.lock      one-line FALLBACK-SEAM calls into upstream-origin files
  CHANGES.md      owner-requested changes to the original app (INBOX #8: removals, defaults, settings screen)
A file may be declared in seams.lock AND in one other ledger (both `modified`): its seam lines are then checked
by tools/seam_check.py and every other difference is the change that other ledger declares and explains.
Each ledger declares paths in a fenced block that starts with ```paths, one per line:
  <modified|added|deleted> <repo-relative path>
New top-level areas that do not exist upstream (tools/, fallback-core/, ...) are ours
and are not compared. Exit 1 on any undeclared drift or any stale declaration.
"""
import filecmp
import os
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
UP = ROOT / "upstream"
LEDGERS = ["BUILD-FIXES.md", "BRANDING.md", "REMOVED.md", "CHANGES.md", "seams.lock"]
# Our own areas and files (never compared, even if upstream has a file of the same name);
# everything else is compared with upstream/.
OURS_TOP = {".git", "upstream", "tools", "fallback-core", "engines", "natives", "python", "ci",
            ".github", "docs", "signing", "VENDORED_FROM", "NOTICE", "THIRD_PARTY_NOTICES.md",
            "ATTRIBUTION.md", "PROVENANCE.md", "BUILD-FIXES.md", "REMOVED.md", "seams.lock",
            "natives.lock", "release-contract.json", ".gitleaks.toml", "README.md",
            "BRANDING.md", "PRIVACY.md", "CHANGELOG.md", "CHANGES.md"}
# Build output and local machine files never count as drift.
IGNORE = re.compile(r"(^|/)(build|\.gradle|\.idea|\.kotlin|captures)(/|$)|(^|/)local\.properties$|\.iml$")


def declared():
    out = {}
    for name in LEDGERS:
        p = ROOT / name
        if not p.exists():
            continue
        for block in re.findall(r"```paths\n(.*?)```", p.read_text(), re.S):
            for line in block.splitlines():
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                kind, path = line.split(None, 1)
                if kind not in ("modified", "added", "deleted"):
                    sys.exit(f"{name}: bad kind {kind!r} in line {line!r}")
                if path in out:
                    # allowed once: seams.lock + one explaining ledger, both "modified" (seams.lock is read last)
                    if name == "seams.lock" and kind == "modified" and out[path][0] == "modified" and out[path][1] != "seams.lock":
                        continue
                    sys.exit(f"{name}: {path} declared twice")
                out[path] = (kind, name)
    return out


def files(base):
    for dirpath, dirnames, filenames in os.walk(base):
        rel_dir = pathlib.Path(dirpath).relative_to(base).as_posix()
        prefix = "" if rel_dir == "." else rel_dir + "/"
        dirnames[:] = [d for d in dirnames if not IGNORE.search(prefix + d)
                       and not (base == ROOT and prefix + d in (".git", "upstream"))]
        for f in filenames:
            rel = (pathlib.Path(dirpath) / f).relative_to(base).as_posix()
            if not IGNORE.search(rel):
                yield rel


def main():
    if not UP.is_dir():
        sys.exit("upstream/ missing - run tools/vendor.sh --init")
    decl = declared()
    actual = {}
    up = set(files(UP))
    for rel in up:
        if rel.split("/", 1)[0] in OURS_TOP:
            continue  # ours entirely (e.g. README.md is rewritten, not patched)
        mine = ROOT / rel
        if not mine.exists():
            actual[rel] = "deleted"
        elif not filecmp.cmp(UP / rel, mine, shallow=False):
            actual[rel] = "modified"
    for rel in files(ROOT):
        if rel.split("/", 1)[0] in OURS_TOP or rel in up:
            continue
        actual[rel] = "added"

    bad = []
    for rel, kind in sorted(actual.items()):
        if rel not in decl:
            bad.append(f"UNDECLARED {kind}: {rel}")
        elif decl[rel][0] != kind:
            bad.append(f"WRONG KIND: {rel} is {kind}, {decl[rel][1]} says {decl[rel][0]}")
    for rel, (kind, ledger) in sorted(decl.items()):
        if rel not in actual:
            bad.append(f"STALE ({ledger}): {rel} declared {kind} but identical to upstream/")
    if bad:
        print("\n".join(bad))
        sys.exit(1)
    by = {}
    for rel, (kind, ledger) in decl.items():
        by[ledger] = by.get(ledger, 0) + 1
    print(f"OK: {len(actual)} declared changes versus upstream/ " + ", ".join(f"{k}={v}" for k, v in sorted(by.items())))


if __name__ == "__main__":
    main()
