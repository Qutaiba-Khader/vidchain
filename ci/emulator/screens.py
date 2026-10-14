#!/usr/bin/env python3
"""Screenshots and the bar-overlap check (T8.6/T8.8; T9 decision 11).

Visits every screen the user can reach without a download (main tabs, settings scrolled, VidChain settings and
licences, bookmarks, history, feedback) plus the share flow (analysis dialog, picker) with a small public test
clip, in every navigation mode and rotation asked for. After each look it saves the screenshot and the UI dump and
runs overlap.py: no text, button or switch may sit under the status bar, the navigation bar or the screen's own
title bar. The media player is full screen on purpose and is not visited.

usage: screens.py <out-dir> [--check] [--rotations 0,1] [--nav gestural,threebutton] [--share-url URL]
  --check   exit 1 when any overlap was found (the report is written either way: <out-dir>/overlap-report.md)"""
import json
import pathlib
import re
import subprocess
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import overlap  # noqa: E402

PKG = "org.websnake.vidchain"
A = sys.argv[1:]
OUT = pathlib.Path(A[0]); OUT.mkdir(parents=True, exist_ok=True)
CHECK = "--check" in A
ROTATIONS = [int(x) for x in (A[A.index("--rotations") + 1] if "--rotations" in A else "0").split(",")]
NAVS = (A[A.index("--nav") + 1] if "--nav" in A else "").split(",") if "--nav" in A else [""]
SHARE_URL = A[A.index("--share-url") + 1] if "--share-url" in A else "https://rclone-public.websnake.org/storage/vidchain-test/screens/clip.mp4"


def adb(*a, timeout=60):
    try:
        return subprocess.run(["adb", *a], capture_output=True, text=True, timeout=timeout).stdout.replace("\r", "")
    except subprocess.TimeoutExpired:          # e.g. `uiautomator dump` never sees an idle screen behind a spinner
        return ""


def sh(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def dump():
    sh("rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml >/dev/null 2>&1", timeout=90)
    return sh("cat /sdcard/ui.xml 2>/dev/null")


def nodes(xml):
    for m in re.finditer(r'<node [^>]*?>', xml):
        n = m.group(0)
        g = lambda k: (re.search(k + r'="([^"]*)"', n) or [None, ""])[1]
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            yield {"text": g("text"), "id": g("resource-id"), "desc": g("content-desc"), "x": (x1 + x2) // 2, "y": (y1 + y2) // 2}


def tap(pred, xml=None, last=False):
    hits = [n for n in nodes(xml or dump()) if pred(n)]
    if not hits:
        return False
    n = hits[-1 if last else 0]
    sh(f"input tap {n['x']} {n['y']}")
    return True


def by_id(name):
    return lambda n: n["id"] == f"{PKG}:id/{name}"


def by_text(text):
    return lambda n: n["text"] == text


report, problems_total = [], 0
screen_size = tuple(map(int, re.search(r"(\d+)x(\d+)", sh("wm size").split("Override")[-1]).groups()))


def look(name, spinner=False, scroll=None):
    """screenshot + dump + overlap check of what is on screen now ([spinner]: a busy dialog that may not be dumped)"""
    global problems_total
    time.sleep(1.5)
    xml = dump()
    win = sh("dumpsys window")
    (OUT / f"{name}.xml").write_text(xml)
    (OUT / f"{name}.insets.txt").write_text("\n".join(l for l in win.splitlines() if "InsetsSource" in l or "mCurrentFocus" in l))
    subprocess.run(f"adb exec-out screencap -p > '{OUT / (name + '.png')}'", shell=True, timeout=60)
    if "<hierarchy" not in xml:
        # no dump: a screen with a spinner never goes idle (allowed for the analysis dialog only); anything else not
        # checked is a failure, never a silent pass (the first survey "passed" 60 screens it could not read)
        probs = [] if spinner else ["not checked: no UI dump"]
        system, titles = {}, []
        print(f"{name}: no UI dump{' (spinner, allowed)' if spinner else ''}", flush=True)
    else:
        try:
            probs, system, titles = overlap.check(xml, win, PKG, scroll)
        except Exception as e:                    # an unreadable dump is reported, not fatal
            probs, system, titles = [f"check failed: {e}"], {}, []
        if not system.get("statusBars") and not probs:
            probs = ["not checked: the window reported no system bars"]
    front = (re.search(r"mCurrentFocus=Window\{\S+ \S+ ([^}\s]+)", win) or [None, "?"])[1]
    report.append({"screen": name, "front": front, "problems": probs, "bars": system, "titles": titles})
    problems_total += len(probs)
    print(f"{name}: {front.split('/')[-1]} {len(probs)} overlap(s)", flush=True)
    for p in probs:
        print("   ", p, flush=True)
    write_report()


def write_report():
    """written after every screen, so a run that stops half way still leaves its findings"""
    api_ = sh("getprop ro.build.version.sdk").strip()
    lines = [f"# Bar overlap check (API {api_})", "", f"**{problems_total} overlap(s) on {len(report)} screens**", "",
             "| screen | activity | overlaps |", "|---|---|---|"]
    for r in report:
        lines.append(f"| {r['screen']} | {r['front'].split('/')[-1]} | {'; '.join(r['problems'])[:600] or 'none'} |")
    (OUT / "overlap-report.md").write_text("\n".join(lines) + "\n")
    (OUT / "overlap-report.json").write_text(json.dumps(report, indent=1))
    return lines


def swipe_up():
    w, h = screen_size if ROT % 2 == 0 else screen_size[::-1]
    sh(f"input swipe {w // 2} {h * 7 // 10} {w // 2} {h * 3 // 10} 500")


def scrolled(name, ends_only=False):
    """a scrolling screen: at its top, (in the middle,) and at its end (the first and last rows must clear the bars)"""
    look(f"{name}-top", scroll="top")
    if not ends_only:
        swipe_up(); time.sleep(1)
        look(f"{name}-middle", scroll="middle")
    for _ in range(12):
        swipe_up()
    time.sleep(1.5)
    look(f"{name}-end", scroll="end")


def start(cls):
    sh(f"am start -n {PKG}/{cls}")
    time.sleep(3)


def visit(prefix):
    sh(f"am force-stop {PKG}")
    sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
    time.sleep(8)
    tap(by_text("Not Now"))                       # e.g. the battery-optimisation question
    for tab in ("home", "browser", "tasks"):
        tap(by_id(f"btn_{tab}_tab"))
        look(f"{prefix}main-{tab}", scroll="top")
    tap(by_id("btn_settings_tab"))
    scrolled(f"{prefix}settings")
    start("org.websnake.vidchain.fallback.ui.VidChainSettingsActivity")
    scrolled(f"{prefix}vidchain-settings")
    start("org.websnake.vidchain.fallback.ui.VidChainLicencesActivity")
    scrolled(f"{prefix}vidchain-licences", ends_only=True)
    for cls, name in (("app.ui.main.fragments.browser.activities.BookmarksActivity", "bookmarks"),
                      ("app.ui.main.fragments.browser.activities.HistoryActivity", "history"),
                      ("app.ui.others.information.UserFeedbackActivity", "feedback")):
        start(cls)
        look(f"{prefix}{name}")
    sh(f"am force-stop {PKG}")


def share(prefix):
    """the share flow: the analysis dialog, then the picker (last: its yt-dlp run slows the emulator down)"""
    sh(f"am force-stop {PKG}")
    sh(f"am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT '{SHARE_URL}' -p {PKG}")
    time.sleep(2)
    look(f"{prefix}share-analysing", spinner=True)
    for _ in range(15):
        time.sleep(2)
        if any(n["text"] in ("Download Now", "Download The File To Private Folder") for n in nodes(dump())):
            break
    look(f"{prefix}share-picker")
    sh("input keyevent 4")
    sh(f"am force-stop {PKG}")


# root lets `am start` open the app's own (not exported) screens; google_apis emulator images allow it
adb("root"); time.sleep(3); adb("wait-for-device")
sh(f"appops set {PKG} MANAGE_EXTERNAL_STORAGE allow")
sh(f"dumpsys deviceidle whitelist +{PKG}")
api = sh("getprop ro.build.version.sdk").strip()
for nav in NAVS:
    if nav:
        out = sh(f"cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.{nav}")
        print(f"navigation {nav}: {out.strip() or 'ok'}", flush=True)
        time.sleep(3)
    for ROT in ROTATIONS:
        sh("settings put system accelerometer_rotation 0")
        sh(f"settings put system user_rotation {ROT}")
        time.sleep(2)
        visit(f"api{api}-{nav or 'nav'}-rot{ROT}-")
ROT = 0
sh("settings put system user_rotation 0")
time.sleep(2)
share(f"api{api}-{NAVS[-1] or 'nav'}-rot0-")

lines = write_report()
print("\n".join(lines[:3]))
if CHECK and problems_total:
    sys.exit(1)
