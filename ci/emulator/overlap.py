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


OLD_NAMES = {"ITYPE_STATUS_BAR": "statusBars", "ITYPE_NAVIGATION_BAR": "navigationBars", "ITYPE_CAPTION_BAR": "captionBar"}


def bars(window_text):
    """visible system bar rectangles: {"statusBars": [(l,t,r,b)], ...} (API 33 names them ITYPE_STATUS_BAR etc.)"""
    out = {}
    for m in re.finditer(r"InsetsSource[^\n]*?type=(statusBars|navigationBars|displayCutout|captionBar|ITYPE_STATUS_BAR|ITYPE_NAVIGATION_BAR|ITYPE_CAPTION_BAR)[^\n]*?frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\][^\n]*?visible=(true|false)", window_text):
        if m.group(6) == "true":
            r = tuple(map(int, m.groups()[1:5]))
            if r[2] > r[0] and r[3] > r[1]:
                out.setdefault(OLD_NAMES.get(m.group(1), m.group(1)), []).append(r)
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


def check(xml_text, window_text, package=PKG, scroll=None):
    """[scroll]: None = a screen that does not scroll; "top" / "end" / "middle" = a scrolling list at that position.
    uiautomator reports a list row's area without the list's padding clip, so a row that runs off the list's bottom
    edge (at "top") or its top edge (at "end") is the list continuing, not a row under a bar."""
    root = ET.fromstring(xml_text[xml_text.index("<"):])
    system = bars(window_text)
    titles, content = [], []

    def walk(node, in_title, in_scroll):
        rid = node.get("resource-id", "")
        here = in_title or bool(TITLE_IDS.search(rid))
        scrolled = in_scroll or node.get("scrollable") == "true"
        if node.tag == "node" and node.get("package") == package:
            r = rect(node)
            if r and r[2] > r[0] and r[3] > r[1]:
                if TITLE_IDS.search(rid):
                    titles.append(r)
                elif not here and (node.get("text") or node.get("clickable") == "true" or node.get("checkable") == "true"):
                    content.append((r, node.get("text") or node.get("content-desc") or rid.split("/")[-1] or node.get("class", ""), in_scroll))
        for c in node:
            walk(c, here, scrolled)

    walk(root, False, False)
    titles = sorted(set(titles))
    problems = []
    screen_h = max((c[0][3] for c in content), default=0)
    for r, label, in_list in content:
        # one text block filling a scrolling view (the licences text) always reaches both edges: its rows are clipped
        # by the view's padding, which uiautomator does not report - judge the rows, not the block
        if in_list and screen_h and (r[3] - r[1]) > 0.6 * screen_h:
            continue
        # in a scrolling list: rows running off the list's far edge are the list going on (see [scroll])
        skip_top = in_list and scroll in ("end", "middle")
        skip_bottom = in_list and scroll in ("top", "middle")
        for kind, rects in system.items():
            if kind == "displayCutout":
                continue
            if (kind == "statusBars" and skip_top) or (kind == "navigationBars" and skip_bottom):
                continue
            for b in rects:
                if area(r, b):
                    problems.append(f"'{label[:40]}' {r} under the {kind} {b}")
        if not skip_top:
            for t in titles:
                if area(r, t):
                    problems.append(f"'{label[:40]}' {r} under the title bar {t}")
    return problems, system, titles


VIEW = re.compile(r"^(\s*)([\w.$]+)\{[0-9a-f]+ ([VIG.])[F.][E.][D.][H.][V.]([C.])[L.][X.] \S* (-?\d+),(-?\d+)-(-?\d+),(-?\d+)(?: #[0-9a-f]+ (\S+))?\}")
LEAF_TYPES = re.compile(r"(TextView|Button|Switch|SwitchCompat|CheckBox|EditText|ImageButton)$")


def views(activity_text, package=PKG):
    """the resumed screen's views from `dumpsys activity top` (for screens that never go idle, so uiautomator cannot
    dump them): [(abs rect, class, id, clickable, has_children, in_title)]. Positions are layout positions: a scrolled
    ScrollView's children are not moved, so use this only for screens at rest (scroll "top")."""
    sec = None
    for part in re.split(r"\n\s*ACTIVITY ", activity_text):
        if part.startswith(package + "/"):
            sec = part
            break
    if sec is None or "View Hierarchy:" not in sec:
        return []
    lines = sec.split("View Hierarchy:", 1)[1].splitlines()
    out, stack = [], []          # stack of (indent, abs_left, abs_top, visible, in_title, entry, clip)
    for ln in lines:
        m = VIEW.match(ln)
        if not m:
            if ln.strip() and not ln.strip().startswith("DecorView") and len(ln) - len(ln.lstrip()) <= 4:
                break             # the next section
            continue
        ind = len(m.group(1))
        while stack and stack[-1][0] >= ind:
            stack.pop()
        px, py, pvis, ptitle = (stack[-1][1], stack[-1][2], stack[-1][3], stack[-1][4]) if stack else (0, 0, True, False)
        pclip = stack[-1][6] if stack else (-10**6, -10**6, 10**6, 10**6)
        l, t, r, b = (int(m.group(i)) for i in range(5, 9))
        rid = m.group(9) or ""
        vis = pvis and m.group(3) == "V"
        title = ptitle or bool(re.search(r"id/(action_bar|toolbar)$", rid))
        full = (px + l, py + t, px + r, py + b)
        # what the user can see of it: a parent clips its children (a list's rows below its end are scrolled, not hidden)
        clip = (max(full[0], pclip[0]), max(full[1], pclip[1]), min(full[2], pclip[2]), min(full[3], pclip[3]))
        rect = clip
        if out and stack and out[-1][0] is not None:
            pass
        entry = [rect, m.group(2), rid, m.group(4) == "C", False, title, vis]
        if stack:
            stack[-1][5][4] = True    # the parent has a child
        stack.append((ind, full[0], full[1], vis, title, entry, clip))
        out.append(entry)
    return [(e[0], e[1], e[2], e[3], e[4], e[5]) for e in out if e[6] and e[0][2] > e[0][0] and e[0][3] > e[0][1]]


def check_views(activity_text, window_text, package=PKG):
    """the same check as check(), from `dumpsys activity top` (screen at rest; no text, so leaves and tappables)"""
    system = bars(window_text)
    vs = views(activity_text, package)
    titles = sorted({v[0] for v in vs if re.search(r"id/(action_bar|toolbar)$", v[2])})
    problems = []
    for rect, cls, rid, clickable, has_children, in_title in vs:
        if in_title or not (clickable or (not has_children and LEAF_TYPES.search(cls))):
            continue
        label = rid.split("/")[-1] or cls.split(".")[-1]
        for kind, rects in system.items():
            if kind == "displayCutout":
                continue
            for b in rects:
                if area(rect, b):
                    problems.append(f"'{label}' {rect} under the {kind} {b}")
        for t in titles:
            if area(rect, t):
                problems.append(f"'{label}' {rect} under the title bar {t}")
    return problems, system, titles, vs


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
