#!/usr/bin/env python3
"""Screenshots for the owner and the review (T8.6/T8.8): main screen, the redesigned settings screen (scrolled),
VidChain's settings. Fresh install, so the app is in its default (dark) theme. Also saves each settings UI dump,
which records every switch's checked state.
usage: screens.py <out-dir>"""
import pathlib, re, subprocess, sys, time

PKG = "org.websnake.vidchain"
OUT = pathlib.Path(sys.argv[1]); OUT.mkdir(parents=True, exist_ok=True)


def adb(*a):
    return subprocess.run(["adb", *a], capture_output=True, text=True, timeout=60).stdout.replace("\r", "")


def dump(name=None):
    adb("shell", "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
    xml = adb("shell", "cat /sdcard/ui.xml")
    if name:
        (OUT / f"{name}.xml").write_text(xml)
    return xml


def tap_text(text):
    m = re.search(r'<node [^>]*?text="' + re.escape(text) + r'"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump())
    if not m:
        return False
    x1, y1, x2, y2 = map(int, m.groups())
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    return True


def shot(name):
    subprocess.run(f"adb exec-out screencap -p > '{OUT / (name + '.png')}'", shell=True, timeout=60)


adb("shell", "am", "force-stop", PKG)
adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
time.sleep(10)
shot("1-main")
print("settings tab:", tap_text("Settings"))
time.sleep(3)
w, h = map(int, re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size").split("Override")[-1]).groups())
for i in range(5):
    shot(f"2-settings-{i}")
    dump(f"2-settings-{i}")
    adb("shell", "input", "swipe", str(w // 2), str(h * 7 // 10), str(w // 2), str(h * 3 // 10), "600")
    time.sleep(1.5)
found = False
for _ in range(6):                       # scroll back up until the VidChain row is on screen, then open it
    if tap_text("VidChain fallbacks"):
        found = True; break
    adb("shell", "input", "swipe", str(w // 2), str(h * 4 // 10), str(w // 2), str(h * 6 // 10), "600")
    time.sleep(1)
print("vidchain row:", found)
time.sleep(3)
shot("3-vidchain-settings")
adb("shell", "input", "keyevent", "4")
adb("shell", "am", "force-stop", PKG)
