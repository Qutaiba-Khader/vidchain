#!/usr/bin/env python3
"""Drive the unchanged app through every fixture (T1.3 golden twin) or a fault table (fault matrix).

For each scenario: write the fault control file read by world.py, force-stop the app, share the URL to
VidChain (exactly like the YouTube app / Morphe do: ACTION_SEND text/plain), then act like a user who
accepts the obvious: tap the app's own "Download Now" or the first quality offered. Record what the
user saw (texts on screen), which activity ended in front, and which files landed in the download folder.
With fallbacks on (T6.2), after the current method's phase the driver also waits for VidChain's fallback chain to
end (trace events "commit result=ok" or "chain.exhausted" in the app's trace file) and records the trace.
(The trace file, not logcat: a release build's Log.i lines did not reach logcat in the first run, 2026-10-03.)
usage: drive.py <scenarios.json> <out-dir> <faults-control-file> [--fallbacks on|off]"""
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import time

PKG = "org.websnake.vidchain"
DL_ROOTS = ["/storage/emulated/0/Download", f"/data/data/{PKG}/files", "/storage/emulated/0/Android/data"]
MARK = "/data/local/tmp/vidchain-scenario.mark"
SCEN = json.loads(pathlib.Path(sys.argv[1]).read_text())
OUT = pathlib.Path(sys.argv[2]); OUT.mkdir(parents=True, exist_ok=True)
CONTROL = pathlib.Path(sys.argv[3])
FALLBACKS = sys.argv[sys.argv.index("--fallbacks") + 1] if "--fallbacks" in sys.argv else "on"
TRACE_TAG = "VidChainTrace"
FALLBACK_WAIT = 300          # seconds a started chain may take (engines, WebView, ffmpeg) before the driver gives up


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
    """media files written since the scenario marker, public Download and the app's private folder"""
    roots = " ".join(DL_ROOTS)
    out = sh(f"find {roots} -type f -newer {MARK} 2>/dev/null | grep -v -E '/(youtubedl-android|no_backup|cache|code_cache|objectbox|shared_prefs|app_webview|databases|trace|\.vidchain-partial)/'")
    res = {}
    for f in filter(None, out.splitlines()):
        size = sh(f"stat -c %s '{f}'").strip()
        res[f] = int(size) if size.isdigit() else -1
    return res


def front():
    m = re.search(r"ResumedActivity: ActivityRecord\{\S+ \S+ (\S+)", sh("dumpsys activity activities | grep -E 'ResumedActivity'"))
    return m.group(1) if m else ""


def set_fallbacks(on):
    """the owner's master switch (FallbackSettings "vidchain_fallback" / fallbacks_enabled), written as root while the app is stopped"""
    sh(f"am force-stop {PKG}")
    d = f"/data/data/{PKG}/shared_prefs"
    local = OUT / "vidchain_fallback.xml"
    local.write_text("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
                     f"    <boolean name=\"fallbacks_enabled\" value=\"{'true' if on else 'false'}\" />\n</map>\n")
    sh(f"mkdir -p {d}")
    adb("push", str(local), f"{d}/vidchain_fallback.xml")
    sh(f"chown $(stat -c %u:%g /data/data/{PKG}) {d} {d}/vidchain_fallback.xml && chmod 660 {d}/vidchain_fallback.xml && restorecon -R {d}")
    print("fallbacks:", "on" if on else "off", "->", sh(f"cat {d}/vidchain_fallback.xml").strip().replace("\n", " "), flush=True)


TRACE_FILE = f"/data/data/{PKG}/files/trace/trace.log"
trace_from = 0


def trace_size():
    s = sh(f"stat -c %s {TRACE_FILE} 2>/dev/null").strip()
    return int(s) if s.isdigit() else 0


def trace():
    """trace events written since this scenario started (as root: the file is in the app's private storage)"""
    size = trace_size()
    start = trace_from if size >= trace_from else 0          # the file rotated meanwhile
    return [l for l in sh(f"tail -c +{start + 1} {TRACE_FILE} 2>/dev/null").splitlines() if " event=" in l]


def chain_state(lines):
    """None = no chain started; "running"; "delivered"; "exhausted" """
    if any(" event=commit " in l and " result=ok" in l for l in lines):
        return "delivered"
    if any(" event=chain.exhausted" in l for l in lines):
        return "exhausted"
    if any(re.search(r" event=(failure|intent|attempt\.start)\b", l) for l in lines):
        return "running"
    return None


set_fallbacks(FALLBACKS == "on")
results = []
scenarios = SCEN["scenarios"]
if SCEN.get("warmup", True):
    # the first share after a fresh install is slower (first start work); run one throw-away scenario first
    scenarios = [{**scenarios[0], "id": "_warmup", "wait": 45}] + scenarios
for sc in scenarios:
    sid, url, wait = sc["id"], sc["url"], sc.get("wait", 50)
    CONTROL.write_text(json.dumps(sc.get("faults", {})))           # world.py re-reads it on change
    sh(f"am force-stop {PKG}")
    sh(f"touch {MARK}")
    trace_from = trace_size()
    time.sleep(1.1)
    before = {}
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
        if any(t.startswith("Analyzing URL") for t in texts):
            deadline = max(deadline, time.time() + 20)        # yt-dlp still working: give it time
            continue
        tap = None
        if "Not Now" in texts and "Not Now" not in actions:
            tap = "Not Now"                          # e.g. battery-optimisation prompt: decline, continue
        elif "Download Now" in texts and "Download Now" not in actions:
            tap = "Download Now"
        else:
            quality = next((t for t in texts if re.fullmatch(r"\d{3,4}p.*", t)), None)
            if quality and "quality" not in actions:
                tap, actions_tag = quality, "quality"
        if tap:
            x, y = next((x, y) for t, x, y in nodes if t == tap)
            sh(f"input tap {x} {y}")
            actions.append("quality" if re.fullmatch(r"\d{3,4}p.*", tap) else tap)
            if tap == "Download Now":
                deadline = max(deadline, time.time() + 40)    # let the download finish
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
    current_files = {k: v for k, v in files().items() if k not in before}
    lines, state = trace(), None
    if FALLBACKS == "on":
        # the current method's phase is over; a chain it triggered gets its own time to finish
        quiet_until, end = time.time() + 15, time.time() + FALLBACK_WAIT
        while time.time() < end:
            lines = trace(); state = chain_state(lines)
            if state in ("delivered", "exhausted") or (state is None and time.time() > quiet_until):
                break
            for t, x, y in ui():
                if t not in seen:
                    seen.append(t)
            time.sleep(3)
        time.sleep(3 if state == "delivered" else 0)      # let the commit settle before listing files
        lines = trace(); state = chain_state(lines)
    t2 = time.time()
    new = {k: v for k, v in files().items() if k not in before}
    hashes = {}
    for f, size in new.items():
        if 0 < size < 20_000_000:
            hashes[f] = (sh(f"sha256sum '{f}'").split() or [""])[0]
    adb("shell", "screencap", "-p", "/sdcard/s.png"); adb("pull", "/sdcard/s.png", str(OUT / f"{sid}.png"))
    rec = {"id": sid, "url": url, "faults": sc.get("faults", {}), "t_start": round(t0, 3), "t_end": round(t2, 3), "t_current_end": round(t1, 3),
           "fallbacks": FALLBACKS, "chain": state, "trace": lines[-200:],
           "current_files": sorted(f.replace("/storage/emulated/0/Download", "<Download>").replace(f"/data/data/{PKG}/files", "<private>") for f in current_files),
           "front_activity": front(), "actions": actions, "texts_seen": seen[:60],
           "files": [{"path": f.replace("/storage/emulated/0/Download", "<Download>").replace(f"/data/data/{PKG}/files", "<private>"), "bytes": s, "sha256": hashes.get(f, "")} for f, s in sorted(new.items())]}
    if sid != "_warmup":
        results.append(rec)
    tried = [m for l in lines for m in re.findall(r" event=attempt\.end .*?method=(\S+)", l)]
    print(f"{sid}: chain={state} methods={tried} actions={actions} files={[(r['path'].split('/')[-1], r['bytes']) for r in rec['files']]} front={rec['front_activity'].split('/')[-1]}", flush=True)
CONTROL.write_text("{}")
(OUT / "outcomes.json").write_text(json.dumps({"scenarios": results}, indent=1))
