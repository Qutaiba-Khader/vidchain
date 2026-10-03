#!/usr/bin/env python3
"""Seam gate: every upstream-origin file that carries FALLBACK-SEAM lines differs from upstream/ ONLY by added
one-line seams (`// FALLBACK-SEAM:<id>`). No upstream line may be removed or changed, and no seam may appear in a
file that seams.lock does not declare. app/build.gradle is checked for its seam lines only (it also carries the
branding hunks declared in BRANDING.md).
Exit 1 on any violation."""
import difflib
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
UP = ROOT / "upstream"
MARK = re.compile(r"//\s*FALLBACK-SEAM:[a-z0-9-]+")
MIXED = {"app/build.gradle"}


def declared():
    text = (ROOT / "seams.lock").read_text()
    out = {}
    for block in re.findall(r"```paths\n(.*?)```", text, re.S):
        for line in block.splitlines():
            if line.strip() and not line.strip().startswith("#"):
                kind, path = line.split(None, 1)
                out[path.strip()] = kind
    return out


def main():
    errors = []
    decl = declared()
    for path, kind in decl.items():
        if kind != "modified":
            continue
        ours = (ROOT / path).read_text().splitlines()
        theirs = (UP / path).read_text().splitlines()
        seams = 0
        for line in difflib.ndiff(theirs, ours):
            if line.startswith("- "):
                errors.append(f"{path}: upstream line removed or changed: {line[2:].strip()[:100]}")
            elif line.startswith("+ "):
                if MARK.search(line):
                    seams += 1
                else:
                    errors.append(f"{path}: added line is not a FALLBACK-SEAM line: {line[2:].strip()[:100]}")
        if seams == 0:
            errors.append(f"{path}: declared in seams.lock but carries no seam")
    # seam markers anywhere else in upstream-origin files
    for up in UP.rglob("*"):
        if not up.is_file() or up.suffix not in (".kt", ".java", ".gradle", ".kts", ".xml"):
            continue
        rel = up.relative_to(UP).as_posix()
        p = ROOT / rel
        if rel in decl or rel in MIXED or not p.is_file():
            continue
        try:
            text = p.read_text()
        except UnicodeDecodeError:
            continue
        if MARK.search(text):
            errors.append(f"{rel}: carries a FALLBACK-SEAM line but is not declared in seams.lock")
    for rel in MIXED:
        ups = set((UP / rel).read_text().splitlines())
        for line in (ROOT / rel).read_text().splitlines():
            if MARK.search(line) and line in ups:
                errors.append(f"{rel}: seam line already upstream?")
    for e in errors:
        print("SEAM:", e)
    n = sum(1 for k in decl.values() if k == "modified")
    print(f"seam_check: {n} seamed files, {len(errors)} violation(s)")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
