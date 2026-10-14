#!/usr/bin/env python3
"""Decision 11 (T9, owner 2026-10-14): find app content drawn under a system bar or under the screen's own title bar.

The check reads the screen as Android reports it:
  - system bars from `dumpsys window` (InsetsSource type=statusBars / navigationBars / displayCutout, visible only);
  - the app's views from `uiautomator dump` (bounds are the visible, clipped area).
A "content" node is any node of the app's package that shows text, can be tapped, or can be checked, and is not part
of a title bar itself (a toolbar / action bar or anything inside one). It overlaps when its visible area crosses a
system bar or the title bar by more than TOLERANCE pixels.

  overlap.py <ui.xml> <dumpsys-window.txt> [--package org.websnake.vidchain]   prints problems, exit 1 if any
Used by screens.py after every screen; also importable (check(xml, window_text))."""
import re
import sys
import xml.etree.ElementTree as ET

PKG = "org.websnake.vidchain"
TOLERANCE = 4
TITLE_IDS = re.compile(r":id/(action_bar|action_bar_container|toolbar|support_action_bar|decor_content_parent_toolbar)$")
BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def bars(window_text):
    """visible system bar rectangles: {"statusBars": [(l,t,r,b)], ...}"""
    out = {}
    for m in re.finditer(r"InsetsSource[^\n]*?type=(statusBars|navigationBars|displayCutout|captionBar)[^\n]*?frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\][^\n]*?visible=(true|false)", window_text):
        if m.group(6) == "true":
            r = tuple(map(int, m.groups()[1:5]))
            if r[2] > r[0] and r[3] > r[1]:
                out.setdefault(m.group(1), []).append(r)
    for k in out:
        out[k] = sorted(set(out[k]))
    return out


def rect(node):
    m = BOUNDS.fullmatch(node.get("bounds", ""))
    return tuple(map(int, m.groups())) if m else None


def area(a, b):
    w = min(a[2], b[2]) - max(a[0], b[0])
    h = min(a[3], b[3]) - max(a[1], b[1])
    return (w, h) if w > TOLERANCE and h > TOLERANCE else None


def check(xml_text, window_text, package=PKG):
    root = ET.fromstring(xml_text[xml_text.index("<"):])
    system = bars(window_text)
    titles, content = [], []

    def walk(node, in_title):
        rid = node.get("resource-id", "")
        here = in_title or bool(TITLE_IDS.search(rid))
        if node.tag == "node" and node.get("package") == package:
            r = rect(node)
            if r and r[2] > r[0] and r[3] > r[1]:
                if TITLE_IDS.search(rid):
                    titles.append(r)
                elif not here and (node.get("text") or node.get("clickable") == "true" or node.get("checkable") == "true"):
                    content.append((r, node.get("text") or node.get("content-desc") or rid.split("/")[-1] or node.get("class", "")))
        for c in node:
            walk(c, here)

    walk(root, False)
    problems = []
    for r, label in content:
        for kind, rects in system.items():
            if kind == "displayCutout":
                continue
            for b in rects:
                if area(r, b):
                    problems.append(f"'{label[:40]}' {r} under the {kind} {b}")
        for t in titles:
            if area(r, t):
                problems.append(f"'{label[:40]}' {r} under the title bar {t}")
    return problems, system, titles


def main():
    a = sys.argv[1:]
    if len(a) < 2:
        sys.exit(__doc__)
    pkg = a[a.index("--package") + 1] if "--package" in a else PKG
    problems, system, titles = check(open(a[0]).read(), open(a[1]).read(), pkg)
    print(f"system bars {system}; title bars {titles}")
    for p in problems:
        print("OVERLAP", p)
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
