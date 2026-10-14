#!/usr/bin/env python3
"""Phone timings for the T9 improvement round (run on CT 200 with the USB test phone attached).

  timing.py coldstart [runs]                      launcher -> first frame (am start -W), app stopped before each run
  timing.py share <url> [runs] [--warm] [--download] [--tap-after S]
        share a link the way other apps do; time share -> picker (and, with --download, Download pressed -> the
        app reports the download complete). Cold = the app is stopped first; --warm keeps the process from the
        previous run alive.
  timing.py idle <minutes>                        the app in the background: CPU seconds it used, foreground service

Times come from the phone's own clock: the accessibility event stream (`uiautomator events`, EventTime = uptime ms)
and /proc/uptime read in the same shell as the share, so adb latency does not count. Nothing about the link or the
page is printed except the link given on the command line. Prints one JSON line per run and a summary."""
import json
import re
import statistics
import subprocess
import sys
import threading
import time

SERIAL = "R58M463R4JP"
PKG = "org.websnake.vidchain"
SHARE = f"{PKG}/app.ui.others.information.IntentInterceptActivity"
PICKER = ("Download Now", "Download Available", "Download The File To Private Folder", "Pick Video Resolution To Generate File Name")   # "Download Available": a direct file
EVENT = re.compile(r"EventType: (\w+); EventTime: (\d+);.*?PackageName: ([\w.]+);.*?Text: \[([^\]]*)\]")


def adb(*args, timeout=60):
    return subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, text=True, timeout=timeout).stdout.replace("\r", "")


def sh(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def su(cmd, timeout=60):
    return sh(f"su -c \"{cmd}\"", timeout)


def uptime_ms(text):
    return int(float(text.split()[0]) * 1000)


class Events:
    """the phone's accessibility events in the background: (uptime ms, type, package, text)"""

    def __init__(self):
        self.items, self.lock = [], threading.Lock()
        self.p = subprocess.Popen(["adb", "-s", SERIAL, "shell", "uiautomator", "events"], stdout=subprocess.PIPE,
                                  stderr=subprocess.DEVNULL, text=True)
        threading.Thread(target=self._read, daemon=True).start()
        time.sleep(1.5)

    def _read(self):
        for line in self.p.stdout:
            m = EVENT.search(line)
            if m:
                with self.lock:
                    self.items.append((int(m.group(2)), m.group(1), m.group(3), m.group(4)))

    def first(self, since_ms, pred):
        with self.lock:
            return next((e for e in self.items if e[0] >= since_ms and pred(e)), None)

    def stop(self):
        self.p.terminate()


def nodes():
    sh("uiautomator dump /sdcard/vc-ui.xml >/dev/null 2>&1")
    xml = sh("cat /sdcard/vc-ui.xml")
    out = []
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if m.group(1).strip():
            x1, y1, x2, y2 = map(int, m.groups()[1:])
            out.append((m.group(1).strip(), (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def tap(text_pred):
    for t, x, y in nodes():
        if text_pred(t):
            out = sh(f"cat /proc/uptime; input tap {x} {y}")
            return uptime_ms(out), t
    return None, None


def completed_since(mark):
    """download records the app marked complete since the marker file was touched"""
    out = su(f"find /data/data/{PKG}/files -maxdepth 1 -name '*_download.json' -newer {mark} -exec grep -l '\\\"isComplete\\\":true' {{}} +")
    return [l for l in out.splitlines() if l.strip().endswith(".json")]


TAP_AFTER = 0.0          # --tap-after S: the user looks at the picker for S seconds before tapping Download


def share_once(url, warm, download):
    if not warm:
        sh(f"input keyevent KEYCODE_HOME; am force-stop {PKG}")
        time.sleep(1.5)
    else:
        sh("input keyevent KEYCODE_HOME")
        time.sleep(1.0)
    mark = "/data/local/tmp/vc-timing.mark"
    su(f"touch {mark}")
    time.sleep(1.1)
    events = Events()                          # one stream per run: `uiautomator dump` cannot run beside it
    out = sh(f"cat /proc/uptime; am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT '{url}' -n {SHARE} >/dev/null")
    t0 = uptime_ms(out)
    rec = {"url": url, "mode": "warm" if warm else "cold"}
    deadline = time.time() + 120
    pick = None
    direct = bool(re.search(r"\.(mp4|webm|mkv|mov|zip|mp3|m4a)(\?|$)", url, re.I))   # its dialog sends no naming event
    while not direct and time.time() < deadline and not pick:
        time.sleep(0.3)
        pick = events.first(t0, lambda e: e[2] == PKG and any(p in e[3] for p in PICKER))
    events.stop()
    time.sleep(0.5)
    if not pick:
        # some dialogs (a direct file's "Download Available") send no event that names them: look at the screen instead
        while time.time() < deadline and not pick:
            if any(any(p in t for p in PICKER) for t, _, _ in nodes()):
                pick = (uptime_ms(sh("cat /proc/uptime")),)
            else:
                time.sleep(0.3)
    if not pick:
        rec["picker_s"] = None
        # T9: when nothing could read the link, VidChain's own methods look for the video and save it to Finished
        # downloads with no picker: wait for that file too (up to 10 minutes)
        if download:
            deadline = time.time() + 600
            while time.time() < deadline:
                done = completed_since(mark)
                if done:
                    rec["result"] = "complete without picker (VidChain)"
                    rec["vidchain_s"] = round(uptime_ms(sh("cat /proc/uptime")) / 1000 - t0 / 1000, 1)
                    return rec
                time.sleep(3)
        rec["result"] = "no picker in 120 s"
        return rec
    rec["picker_s"] = round((pick[0] - t0) / 1000, 2)
    if not download:
        return rec
    time.sleep(max(1.0, TAP_AFTER))
    texts = [t for t, _, _ in nodes()]
    # a resolution picker: choose the best resolution offered like a user, then confirm
    qs = sorted((t for t in texts if re.fullmatch(r"\d{3,4}p.*", t)), key=lambda t: -int(re.match(r"\d+", t).group()))
    if qs:
        tap(lambda t: t == qs[0])
        rec["quality"] = qs[0]
        time.sleep(1.0)
    else:
        # no "720p"-style row (e.g. "Audio Only", "640x360", "unknown"): the first row of the format grid, as a user would
        xml = sh("cat /sdcard/vc-ui.xml")
        i = xml.find('id/video_format_container"')
        m = re.search(r'<node [^>]*?text="([^"]+)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml[i:]) if i >= 0 else None
        if m:
            x1, y1, x2, y2 = map(int, m.groups()[1:])
            sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
            rec["quality"] = m.group(1)
            time.sleep(1.0)
    t1, _ = tap(lambda t: t == "Download Now")
    if t1 is None:
        rec["result"] = "no Download Now button"
        return rec
    deadline = time.time() + 300
    while time.time() < deadline:
        done = completed_since(mark)
        if done:
            rec["download_s"] = round((uptime_ms(sh("cat /proc/uptime")) - t1) / 1000, 2)
            rec["result"] = "complete"
            return rec
        time.sleep(0.5)
    rec["download_s"] = None
    rec["result"] = "not complete in 300 s"
    return rec


def coldstart(runs):
    act = sh(f"cmd package resolve-activity --brief -c android.intent.category.LAUNCHER {PKG}").strip().splitlines()[-1]
    times = []
    for _ in range(runs):
        sh(f"input keyevent KEYCODE_HOME; am force-stop {PKG}")
        time.sleep(2)
        out = sh(f"am start -W -n {act}")
        m = re.search(r"TotalTime: (\d+)", out)
        if m:
            times.append(int(m.group(1)))
            print(json.dumps({"coldstart_ms": int(m.group(1))}), flush=True)
        time.sleep(3)
    sh("input keyevent KEYCODE_HOME")
    if times:
        print(json.dumps({"coldstart_median_ms": statistics.median(times), "runs": len(times)}))


def cpu_ticks(pid):
    f = su(f"cat /proc/{pid}/stat").split(")")[-1].split()
    return int(f[11]) + int(f[12])            # utime + stime (fields 14, 15)


def idle(minutes):
    pid = sh(f"pidof {PKG}").strip()
    if not pid:
        sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
        time.sleep(8)
        pid = sh(f"pidof {PKG}").strip()
    sh("input keyevent KEYCODE_HOME")
    time.sleep(30)                             # the app settles in the background
    hz = 100
    a = cpu_ticks(pid)
    time.sleep(minutes * 60)
    b = cpu_ticks(pid)
    fg = "isForeground=true" in sh(f"dumpsys activity services {PKG}")
    print(json.dumps({"idle_minutes": minutes, "cpu_s": round((b - a) / hz, 2), "cpu_pct": round((b - a) / hz / (minutes * 60) * 100, 3),
                      "foreground_service": fg, "same_process": sh(f"pidof {PKG}").strip() == pid}))


def main():
    a = sys.argv[1:]
    if not a:
        sys.exit(__doc__)
    if a[0] == "coldstart":
        coldstart(int(a[1]) if len(a) > 1 else 10)
    elif a[0] == "idle":
        idle(float(a[1]) if len(a) > 1 else 10)
    elif a[0] == "share":
        url = a[1]
        runs = next((int(x) for x in a[2:] if x.isdigit()), 3)
        warm, download = "--warm" in a, "--download" in a
        global TAP_AFTER
        TAP_AFTER = float(a[a.index("--tap-after") + 1]) if "--tap-after" in a else 0.0
        res = []
        try:
            for i in range(runs):
                r = share_once(url, warm and i > 0, download)
                res.append(r)
                print(json.dumps(r), flush=True)
                time.sleep(3)
        finally:
            sh("input keyevent KEYCODE_HOME")
        p = [r["picker_s"] for r in res if r.get("picker_s") is not None]
        d = [r["download_s"] for r in res if r.get("download_s") is not None]
        print(json.dumps({"url": url, "tap_after_s": TAP_AFTER, "picker_median_s": statistics.median(p) if p else None,
                          "download_median_s": statistics.median(d) if d else None, "runs": len(res)}))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
