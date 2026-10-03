#!/usr/bin/env python3
"""Drive the unchanged app through every fixture (T1.3 golden twin) or a fault table (fault matrix).

For each scenario: write the fault control file read by world.py, force-stop the app, share the URL to
VidChain (exactly like the YouTube app / Morphe do: ACTION_SEND text/plain), then act like a user who
accepts the obvious: tap the app's own "Download Now" or the first quality offered. Record what the
user saw (texts on screen), which activity ended in front, and which files landed in the download folder.
usage: drive.py <scenarios.json> <out-dir> <faults-control-file>"""
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import time

PKG = "org.websnake.vidchain"
DL_ROOT = "/storage/emulated/0/Download"
SCEN = json.loads(pathlib.Path(sys.argv[1]).read_text())
OUT = pathlib.Path(sys.argv[2]); OUT.mkdir(parents=True, exist_ok=True)
CONTROL = pathlib.Path(sys.argv[3])


def adb(*args, timeout=60):
    p = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    return p.stdout.replace("\r", "")


def sh(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def ui():
    sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
    xml = sh("cat /sdcard/ui.xml")
    nodes = []
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        t = m.group(1).strip()
        if t:
            x1, y1, x2, y2 = map(int, m.groups()[1:])
            nodes.append((t, (x1 + x2) // 2, (y1 + y2) // 2))
    return nodes


def files():
    out = sh(f"find {DL_ROOT} -type f 2>/dev/null")
    res = {}
    for f in filter(None, out.splitlines()):
        size = sh(f"stat -c %s '{f}'").strip()
        res[f] = int(size) if size.isdigit() else -1
    return res


def front():
    m = re.search(r"mResumedActivity: .*? ([\w.]+/[\w.$]+)", sh("dumpsys activity activities | grep mResumedActivity"))
    return m.group(1) if m else ""


results = []
for sc in SCEN["scenarios"]:
    sid, url, wait = sc["id"], sc["url"], sc.get("wait", 50)
    CONTROL.write_text(json.dumps(sc.get("faults", {})))           # world.py re-reads it on change
    sh(f"am force-stop {PKG}")
    sh(f"rm -rf '{DL_ROOT}'/* 2>/dev/null")
    before = files()
    time.sleep(1)
    t0 = time.time()
    sh(f"am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT '{url}' -p {PKG}")
    seen, actions, stable = [], [], 0
    last = {}
    deadline = t0 + wait
    while time.time() < deadline:
        time.sleep(3)
        nodes = ui()
        for t, _, _ in nodes:
            if t not in seen:
                seen.append(t)
        texts = [t for t, _, _ in nodes]
        tap = None
        if "Download Now" in texts and "Download Now" not in actions:
            tap = "Download Now"
        else:
            quality = next((t for t in texts if re.fullmatch(r"\d{3,4}p.*", t)), None)
            if quality and "quality" not in actions:
                tap, actions_tag = quality, "quality"
        if tap:
            x, y = next((x, y) for t, x, y in nodes if t == tap)
            sh(f"input tap {x} {y}")
            actions.append("quality" if re.fullmatch(r"\d{3,4}p.*", tap) else tap)
            continue
        now = {k: v for k, v in files().items() if k not in before}
        if now and now == last and all(v > 0 for v in now.values()):
            stable += 1
            if stable >= 2:
                break
        else:
            stable = 0
        last = now
    t1 = time.time()
    new = {k: v for k, v in files().items() if k not in before}
    hashes = {}
    for f, size in new.items():
        if 0 < size < 20_000_000:
            hashes[f] = (sh(f"sha256sum '{f}'").split() or [""])[0]
    adb("shell", "screencap", "-p", "/sdcard/s.png"); adb("pull", "/sdcard/s.png", str(OUT / f"{sid}.png"))
    rec = {"id": sid, "url": url, "faults": sc.get("faults", {}), "t_start": round(t0, 3), "t_end": round(t1, 3),
           "front_activity": front(), "actions": actions, "texts_seen": seen[:60],
           "files": [{"path": f.replace(DL_ROOT, "<Download>"), "bytes": s, "sha256": hashes.get(f, "")} for f, s in sorted(new.items())]}
    results.append(rec)
    print(f"{sid}: actions={actions} files={[(r['path'].split('/')[-1], r['bytes']) for r in rec['files']]} front={rec['front_activity'].split('/')[-1]}", flush=True)
CONTROL.write_text("{}")
(OUT / "outcomes.json").write_text(json.dumps({"scenarios": results}, indent=1))
